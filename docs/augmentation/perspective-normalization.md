# Perspectief-Normalisatie

Bron van waarheid voor hoe rl_pawn op beide teams (rood en blauw) speelt met een model dat uitsluitend op blauw-perspectief is getraind.

---

## 1. Kernprincipe

Het ONNX-model is getraind op BC-data vanuit blauw perspectief (team=1). Bots op rood (team=0) krijgen een runtime-transformatie die hun observaties naar blauw-perspectief roteert via 180 graden rotatie. Het model "denkt" altijd dat het blauw speelt.

Acties zijn relatief (forward/left/right/yaw delta/pitch delta) en werken voor beide teams zonder transformatie.

```
    BLAUWE BOT (team=1)                     RODE BOT (team=0)

    Game state                              Game state
        |                                       |
    Feature resolution                      Feature resolution
    (native blauw)                          (native rood)
        |                                       |
        |                                  Perspectief-
        |                                  normalisatie
        |                                  (180 graden rotatie)
        |                                       |
        v                                       v
    +-------- ZELFDE MODEL (blauw perspectief) --------+
        |                                       |
    Actie (relatief)                        Actie (relatief)
        |                                       |
    Experience .npz                         Experience .npz
    (blauw features)                        (blauw features)
        |                                       |
        +---------------- SAC trainer ---------+
                       (uniforme data)
```

---

## 2. Kaartsymmetrie: CTF-AndAction

CTF-AndAction heeft 180 graden rotationele symmetrie rond de oorsprong. Elke doorgang en obstakel aan de blauwe zijde heeft een gespiegelde tegenhanger aan de rode zijde.

```
    +Y (noord)
       ^
       |       RODE BASIS
       |       R = (-bx, -by)
       |
  -X <-+-----> +X (oost)
       |
       |       BLAUWE BASIS
       |       B = (bx, by)
       v
    -Y (zuid)
```

### Wat 180 graden rotatie doet

| Grootheid | Origineel | Na 180 graden rotatie |
|---|---|---|
| Positie (x, y, z) | (x, y, z) | (-x, -y, z) |
| Yaw (kijkrichting) | theta | theta + 180 graden |
| Velocity (vx, vy, vz) | (vx, vy, vz) | (-vx, -vy, vz) |
| Hoogte (z) | z | z (ongewijzigd) |
| Pitch | pitch | pitch (ongewijzigd) |

---

## 3. Waar De Normalisatie Draait

```
    Realtime input builder
        |
        | per frame in het sequence window:
        v
    Feature resolution: float[] resolved = [alle features]
        |
        v
    Als team != 1 (blauw):
        normalisatie(resolved)     <-- IN-PLACE transformatie
        |
        v
    Cache opslag (resolved[] bevat nu blauw-perspectief)
        |
        +------------------------+
        v                        v
    Tensor assembly          Experience collection
    -> ONNX inference        -> .npz (blauw features)
```

De normalisatie vindt plaats voor de cache, zodat gecachede features al in canonical perspectief staan. Zowel model-inference als experience collection gebruiken dezelfde genormaliseerde data.

---

## 4. Transformatietabel

De perspectief-normalisatie past vier operaties toe per frame, in volgorde:

### Stap 1: Negatie (42 features)

Features die van teken wisselen onder 180 graden rotatie:

| Categorie | Features | Aantal | Reden |
|---|---|---|---|
| Absolute positie | `self_locationX_norm`, `self_locationY_norm` | 2 | (x,y) wordt (-x,-y) |
| Absolute snelheid | `self_velocityX_norm`, `self_velocityY_norm` | 2 | Snelheidsvector gespiegeld |
| World-cartesisch collision | Alle `self_*_world_cos` en `self_*_world_sin` | 32 | cos(theta+180) = -cos(theta) |
| Enemy dodge-richting | `enemy0/1/2_dodgeDir_sin`, `enemy0/1/2_dodgeDir_cos` | 6 | World-space absoluut: sin(theta+pi) = -sin(theta) |

### Stap 2: Paar-Swaps (16 paren = 32 features)

Features die van positie wisselen onder 180 graden rotatie:

**Yaw-relatieve collision -- 8 paren (ring-permutatie):**

```
    fwd         <->  back
    fwdRight30  <->  backLeft30
    fwdRight45  <->  backLeft45
    fwdRight60  <->  backLeft60
    right       <->  left
    backRight60 <->  fwdLeft60
    backRight45 <->  fwdLeft45
    backRight30 <->  fwdLeft30
```

Bij 180 graden vallen alle stralen exact op een ring-positie -- geen interpolatie nodig.

**World-axis collision -- 8 paren:**

| Paar A | Paar B |
|---|---|
| `self_posXCollision_norm` | `self_negXCollision_norm` |
| `self_posYCollision_norm` | `self_negYCollision_norm` |
| `self_posXPosY30Collision_norm` | `self_negXNegY30Collision_norm` |
| `self_posXPosY45Collision_norm` | `self_negXNegY45Collision_norm` |
| `self_posXPosY60Collision_norm` | `self_negXNegY60Collision_norm` |
| `self_negXPosY30Collision_norm` | `self_posXNegY30Collision_norm` |
| `self_negXPosY45Collision_norm` | `self_posXNegY45Collision_norm` |
| `self_negXPosY60Collision_norm` | `self_posXNegY60Collision_norm` |

> De eerdere per-enemy yaw-relatieve collision-swaps zijn **verwijderd** uit `buildSwapList()`: `enemy{N}_fwdCollision_norm` is een raytrace in het lichaamsframe van die vijand zelf — onder de wereld-flip roteert de vijand mee en blijft de waarde identiek, dus de swap corrumpeerde het signaal. Met permutatie-invariante pooling is het punt sowieso irrelevant.

### Stap 3: ViewRotation Sin/Cos Negatie (2 features)

| Feature | Transformatie | Wiskundige basis |
|---|---|---|
| `self_viewRotationX_sin` | Negeren | sin(theta + pi) = -sin(theta) |
| `self_viewRotationX_cos` | Negeren | cos(theta + pi) = -cos(theta) |

### Stap 4: team_norm Override (1 feature)

| Feature | Transformatie |
|---|---|
| `self_team_norm` | Overschreven naar 1.0 (canonical blauw) |

### Totaaloverzicht

| Operatie | Aantal features |
|---|---|
| Negatie (stap 1) | 42 |
| Swaps (stap 2) | 32 (16 paren) |
| ViewRotation (stap 3) | 2 |
| team_norm (stap 4) | 1 |
| **Totaal getransformeerd** | **77** |
| Ongewijzigd | ~42 (egocentrisch + status) |

---

## 5. Wat NIET Getransformeerd Wordt

### Egocentrische Features (Wiskundig Invariant)

Alle `relSin/relCos`, `forwardDist/rightDist` en `distance_norm` features zijn invariant onder gelijktijdige 180 graden rotatie van alle posities en yaw:

```
    Blauw op (bx, by), yaw = theta, doel op (gx, gy):
        relAngle = atan2(gy - by, gx - bx) - theta

    Rood op (-bx, -by), yaw = theta + pi, doel op (-gx, -gy):
        relAngle = atan2(-gy + by, -gx + bx) - (theta + pi)
                 = atan2(-(gy-by), -(gx-bx)) - theta - pi
                 = (atan2(gy-by, gx-bx) + pi) - theta - pi
                 = atan2(gy-by, gx-bx) - theta
                 = identiek
```

Dit geldt voor alle egocentrische features:

| Feature | Reden invariant |
|---|---|
| `*Flag_relSin/relCos`, `homeBase_relSin/relCos` | Relatieve hoek ongewijzigd |
| `enemyPlayer_relSin/relCos` | Idem |
| `*_forwardDist_norm`, `*_rightDist_norm` | Projectie op forward/right assen, invariant |
| `*_distance_norm` | Euclidische afstand: d((-a),(-b)) = d(a,b) |
| `forwardVelocity_norm`, `rightVelocity_norm` | dot((-v),(-f)) = dot(v,f) |

De feature resolvers maken een correcte home/enemy mapping op basis van het team -- rode bot krijgt rode vlag als homeFlag, blauwe als enemyFlag. De egocentrische berekening is gebaseerd op relatieve posities die invariant zijn.

### Overige Ongewijzigde Features

| Categorie | Features | Reden |
|---|---|---|
| Status | `hasFlag`, `enemyPresent`, `enemyVisible`, `enemyHasFlag` | Perspectief-onafhankelijk |
| Hoogte | `locationZ_norm`, `velocityZ_norm` | Symmetrie is horizontaal |
| Pitch | `viewRotationY_norm` | Horizontaal-onafhankelijk |
| Angular velocity | `yawAngularVelocity_norm`, `pitchAngularVelocity_norm` | Draaisnelheid richtingsonafhankelijk |
| Dodge state | `playerDodgeActive`, `playerDodgeCooldown` | Toestand, niet positie |

---

## 6. Edge-Squash Compatibiliteit

De locatie-normalisatie gebruikt edge-squash (zachte tanh-begrenzing bij kaartranden). Edge-squash is een oneven functie: `f(-x) = -f(x)`. Daarom kan de normalisatie veilig de genormaliseerde waarde negeren zonder terug te rekenen naar ruwe coordinaten:

```
    normalizeLocation(-x) = edgeSquash(-x / maxDist)
                           = -edgeSquash(x / maxDist)
                           = -normalizeLocation(x)
```
