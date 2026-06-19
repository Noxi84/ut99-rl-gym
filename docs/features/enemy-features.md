# Vijandfeatures

## Overzicht

Het model observeert tot 5 vijandelijke spelers via een slot-systeem. Elke vijand bezet een slot (enemy0..enemy4) met een vaste set van 14 features. Slots worden gevuld op basis van afstand; lege slots bevatten nullen. De webservice filtert spectators uit en identificeert bots op naam.

Naast de per-slot speler-features krijgt het model ook per-enemy projectieldata: tot 7 projectiel-slots per vijand met elk 21 features.

---

## Datapipeline

```
UT99 webservice
    -> Itereert over alle spelers (filtert spectators)
    -> JSON: Players array met per speler alle velden
    -> Slot-selectie: 5 dichtstbijzijnde vijanden
    -> Feature resolvers: egocentrische berekening t.o.v. kijkrichting bot
    -> Normalisatie
    -> Model input
```

---

## Enemy slot features (5 slots x 14 = 70)

Per slot dezelfde 14 features, prefix `enemy{N}_`:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `hasFlag` | 0 of 1 | Vijand draagt een vlag |
| `relSin` | [-1, 1] | Sinus van de hoek naar vijand t.o.v. kijkrichting bot |
| `relCos` | [-1, 1] | Cosinus van de hoek naar vijand t.o.v. kijkrichting bot |
| `distance_norm` | [0, 1] | 3D-afstand tot vijand (genormaliseerd) |
| `pitchBearing_norm` | [-1, 1] | Verticale hoek naar vijand |
| `aimAlignmentDot_norm` | [-1, 1] | Dot-product van kijkrichting bot en richting naar vijand |
| `visible` | 0 of 1 | Vijand is zichtbaar voor de bot |
| `forwardVelocity_norm` | [-1, 1] | Vijand-snelheid geprojecteerd op diens forward-as |
| `rightVelocity_norm` | [-1, 1] | Vijand-snelheid geprojecteerd op diens rechter-as |
| `speed_norm` | [0, 1] | Absolute snelheidsgrootte vijand |
| `relVelForward_norm` | [-1, 1] | Relatieve snelheid vijand op forward-as van de bot |
| `relVelRight_norm` | [-1, 1] | Relatieve snelheid vijand op rechter-as van de bot |
| `relVelUp_norm` | [-1, 1] | Relatieve verticale snelheid vijand |
| `relZ_norm` | [-1, 1] | Verticale offset vijand t.o.v. bot. tanh(dz/512). Map-onafhankelijk |

### Body-frame projecties (aanvullend)

Naast de 14 kern-features krijgt elke enemy-slot ook body-frame projecties:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `forwardDist_norm` | [0, 1] | Vijandafstand geprojecteerd op de forward-as van de bot |
| `rightDist_norm` | [0, 1] | Vijandafstand geprojecteerd op de rechter-as van de bot |

---

## Egocentrische representatie

```
    Bot kijkt naar het NOORDEN, vijand staat NOORDOOST:

                   ^ kijkrichting
                   |
                   |  forwardDist (projectie op forward-as)
         +---------|---------+
         |         |    X    |  <- vijand
         |         |   /     |
         |         |  /      |
         |         | / relatieveHoek
         |    Bot O|/        |
         |         |         |
         +---------|---------+
                   |-> rightDist (projectie op rechter-as)

    relCos > 0: vijand is VOOR de bot
    relCos < 0: vijand is ACHTER de bot
    relSin > 0: vijand is RECHTS van de bot
    relSin < 0: vijand is LINKS van de bot
```

De sin/cos-representatie vermijdt de discontinuiteit bij -180/+180 graden en biedt het model twee continue signalen die samen de volledige richting vastleggen.

---

## isAlive en physics per slot

Naast de 14 kern-features staan in aparte feature-groepen:

| Feature | Bereik | Groep | Beschrijving |
|---|---|---|---|
| `enemy{N}_isAlive` | 0 of 1 | Player isAlive | Guard-conditie: alle andere features zijn nul als isAlive=0 |
| `enemy{N}_physics_isFalling` | 0 of 1 | Physics state | Vijand is in de lucht |
| `enemy{N}_health_norm` | [0, 1] | Health | Genormaliseerde gezondheid vijand |

---

## Per-enemy projectielen (5 enemies x 7 slots x 21 features)

Per vijand worden de 7 dichtstbijzijnde projectielen (raketten, flak, etc.) als aparte slots bijgehouden.

### Aanwezigheid (5 x 7 = 35 features)

| Feature | Bereik | Beschrijving |
|---|---|---|
| `enemy{N}_projectile{M}_present` | 0 of 1 | Projectiel-slot is gevuld |

### Details per projectiel-slot (21 features)

| Feature | Bereik | Beschrijving |
|---|---|---|
| `relSin` | [-1, 1] | Egocentrische hoek-sinus naar projectiel |
| `relCos` | [-1, 1] | Egocentrische hoek-cosinus naar projectiel |
| `distance_norm` | [0, 1] | Afstand tot projectiel |
| `pitchBearing_norm` | [-1, 1] | Verticale hoek naar projectiel |
| `forwardVelocity_norm` | [-1, 1] | Projectiel-snelheid op forward-as |
| `rightVelocity_norm` | [-1, 1] | Projectiel-snelheid op rechter-as |
| `speed_norm` | [0, 1] | Absolute snelheid projectiel |
| `timeToImpact_norm` | [0, 1] | Geschatte tijd tot impact |
| `isGrenade` | 0 of 1 | Projectieltype one-hot |
| `isChunk` | 0 of 1 | (flak-fragment) |
| `isShockBall` | 0 of 1 | |
| `isRocket` | 0 of 1 | |
| `isRocketGrenade` | 0 of 1 | |
| `isBioBlob` | 0 of 1 | |
| `isBioGlob` | 0 of 1 | |
| `isPulsePlasma` | 0 of 1 | |
| `isRazor` | 0 of 1 | |
| `isRedeemerMissile` | 0 of 1 | |
| `isTranslocatorDisc` | 0 of 1 | |
| `damage_norm` | [0, 1] | Genormaliseerde schade |
| `chargeScale_norm` | [0, 1] | Charge-niveau (voor opgeladen wapens) |

---

## Enemy collision

Per enemy-slot worden 32 collision-stralen beschikbaar gesteld (16 yaw-relatief + 16 world-axis, vanuit de vijand). Zie [collision-rays.md](collision-rays.md) voor details.

---

## Enemy-spawn target

Wanneer alle vijanden dood zijn activeert een sticky nearest enemy-team spawn-point met een hold-timer (~3 seconden). Dit voorkomt rondjes-draaien tijdens respawn-vensters.

| Feature | Bereik | Beschrijving |
|---|---|---|
| `enemySpawnTarget_active` | 0 of 1 | Target is actief |
| `enemySpawnTarget_relSin` | [-1, 1] | Egocentrische hoek-sinus |
| `enemySpawnTarget_relCos` | [-1, 1] | Egocentrische hoek-cosinus |
| `enemySpawnTarget_distance_norm` | [0, 1] | Afstand |
| `enemySpawnTarget_forwardDist_norm` | [0, 1] | Forward-projectie |
| `enemySpawnTarget_rightDist_norm` | [0, 1] | Right-projectie |
| `enemySpawnTarget_pitchBearing_norm` | [-1, 1] | Verticale hoek |
| `enemySpawnTarget_pitchError_norm` | [-1, 1] | Pitch-fout |

---

## Temporele vensters

| Groep | Venster (first, last) | Beschrijving |
|---|---|---|
| Enemy slots 5x14 | (20, 4) | Lange historie voor trajectorie-extrapolatie |
| Enemy forwardDist/rightDist | (10, 4) | Body-frame projecties |
| Enemy isAlive | (0, 1) | Alleen huidig frame |
| Enemy physics | (0, 1) | Alleen huidig frame |
| Enemy health | (0, 1) | Alleen huidig frame |
| Enemy collision yaw | (0, 1) | Alleen huidig frame |
| Enemy collision world | (0, 1) | Alleen huidig frame |
| Enemy projectiel presence | (0, 1) | Alleen huidig frame |
| Enemy projectiel details | (10, 1) | Historie voor trajectorie-tracking |
| Enemy-spawn target | (0, 4) | Recente frames |

---

## Augmentatie

Bij mirror-augmentatie worden de egocentrische enemy-features (`relSin/relCos`, `forwardDist_norm`, `rightDist_norm`) herberekend vanuit de gespiegelde kijkrichting. Alle overige enemy-features (status, eigen kijkrichting vijand, collision, afstand) blijven ongewijzigd.
