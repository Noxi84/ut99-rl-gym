# Collision Stralen

## Overzicht

De UT99 webservice vuurt per tick stralen (rays) af vanuit elke speler via de engine's native `Trace()`-functie. Elke straal meet de afstand tot de dichtstbijzijnde muur of obstakel. Ruwe waarden zijn in Unreal Units (UU), bereik 0 (tegen een muur) tot 1200 (open ruimte). Genormaliseerde varianten (`_norm`) delen door 1200 en klemmen vast op [0, 1].

Er zijn vijf typen collision-informatie:

| Type | Aantal per speler | Beschrijving |
|---|---|---|
| Yaw-relatieve stralen | 16 | Draaien mee met kijkrichting (borsthoogte) |
| World-axis stralen | 16 | Vaste kompasrichtingen, onafhankelijk van kijkrichting |
| Floor-elevation fan | 8 | Signed omlaag-probe per sector: drop (−) / step-up (+) |
| Low rays | 8 | Horizontale stralen op voethoogte (lage-obstakel detectie) |
| Verticale probes | 2 | Strikt omlaag + strikt omhoog (floor/ceiling afstand) |
| Enemy collision | 16+16 per slot | Idem yaw-relatief + world-axis, vanuit elke vijand |

---

## Datapipeline

```
UT99 webservice
  Trace() per straal (native engine-aanroep)
    |
    v
JSON: collision-velden per speler (ruwe UU, 0-1200)
    |
    v
Collision converter -> normalisatie: waarde / 1200, clamp [0, 1]
    |
    v
Feature resolver -> model input
```

---

## Self yaw-relatieve stralen (16)

Draaien mee met de kijkrichting van de bot. Direct bruikbaar voor bewegingsbeslissingen: als `self_fwdCollision_norm` laag is (muur dichtbij) moet de bot niet vooruit lopen.

```
         fwdLeft30  FWD  fwdRight30
      fwdLeft45          fwdRight45
   fwdLeft60                fwdRight60
  LEFT                          RIGHT
   backLeft60               backRight60
      backLeft45         backRight45
         backLeft30 BACK backRight30
```

| Feature | Hoek |
|---|---|
| `self_fwdCollision_norm` | 0 (vooruit) |
| `self_fwdRight30Collision_norm` | 30 |
| `self_fwdRight45Collision_norm` | 45 |
| `self_fwdRight60Collision_norm` | 60 |
| `self_rightCollision_norm` | 90 |
| `self_backRight60Collision_norm` | 120 |
| `self_backRight45Collision_norm` | 135 |
| `self_backRight30Collision_norm` | 150 |
| `self_backCollision_norm` | 180 |
| `self_backLeft30Collision_norm` | 210 |
| `self_backLeft45Collision_norm` | 225 |
| `self_backLeft60Collision_norm` | 240 |
| `self_leftCollision_norm` | 270 |
| `self_fwdLeft60Collision_norm` | 300 |
| `self_fwdLeft45Collision_norm` | 315 |
| `self_fwdLeft30Collision_norm` | 330 |

---

## Self world-axis stralen (16)

Vaste kompasrichtingen op de kaart. Roteren NIET wanneer de bot draait. Naamgeving volgt Unreal Engine assenconventie: +X = oost, +Y = noord.

| Feature | Hoek |
|---|---|
| `self_posXCollision_norm` | 0 (+X, oost) |
| `self_posXPosY30Collision_norm` | 30 |
| `self_posXPosY45Collision_norm` | 45 (NO) |
| `self_posXPosY60Collision_norm` | 60 |
| `self_posYCollision_norm` | 90 (+Y, noord) |
| `self_negXPosY60Collision_norm` | 120 |
| `self_negXPosY45Collision_norm` | 135 (NW) |
| `self_negXPosY30Collision_norm` | 150 |
| `self_negXCollision_norm` | 180 (-X, west) |
| `self_negXNegY30Collision_norm` | 210 |
| `self_negXNegY45Collision_norm` | 225 (ZW) |
| `self_negXNegY60Collision_norm` | 240 |
| `self_negYCollision_norm` | 270 (-Y, zuid) |
| `self_posXNegY60Collision_norm` | 300 |
| `self_posXNegY45Collision_norm` | 315 (ZO) |
| `self_posXNegY30Collision_norm` | 330 |

---

## Verschil tussen de twee typen

```
    YAW-RELATIEF                          WORLD-AXIS
    (draait mee)                          (vast op kaart)

    Bot kijkt OOST:                       Bot kijkt OOST:
         FWD                                   +Y (N)
          |                                     |
    L <-- O --> R                    -X (W) <-- O --> +X (O)
          |                                     |
         BACK                                  -Y (Z)

    Bot draait naar NOORD:                Bot draait naar NOORD:
         FWD                                   +Y (N)
          |                                     |
    L <-- O --> R   (alles meegedraaid)  -X (W) <-- O --> +X (O)  (ongewijzigd)
          |                                     |
         BACK                                  -Y (Z)
```

- **Yaw-relatief**: direct bruikbaar voor beweging. Bewegingsacties (vooruit, links, rechts) zijn relatief aan de kijkrichting.
- **World-axis**: absolute kaartinformatie. De bot kan herkennen "ik ben in een gang die oost-west loopt" ongeacht kijkrichting. Helpt bij navigatie en positiebewustzijn.

---

## Floor-elevation fan (8)

Omlaag-gerichte stralen in een horizontale waaier op `FLOOR_PROBE_DIST` (160 UU) vooruit per sector, startend boven voethoogte (`STEP_PROBE_TOP` = 96 UU) zodat zowel dalingen als stijgingen gemeten worden. Rapporteert de **signed** vloerhoogte-delta t.o.v. de voeten (`HitLoc.Z − FootZ`):

- **Negatief** = drop (richel/afgrond omlaag); diepe void verzadigt naar −1.
- **Nul** = gelijk niveau.
- **Positief** = step-up (de vloer vóór de bot ligt hoger — een drempel/krat). Binnen het springbare bereik (~30–60 UU) is dit het signaal "hier moet/kun je springen". Een te hoge muur verzadigt richting +1.

Normalisatie: `tanh(delta / 64)`, zodat het beslissende ~0–128 UU venster fijne resolutie krijgt en void/muur netjes verzadigen.

| Feature | Richting |
|---|---|
| `self_fwdFloorDelta_norm` | Vooruit |
| `self_fwdRightFloorDelta_norm` | Vooruit-rechts |
| `self_rightFloorDelta_norm` | Rechts |
| `self_backRightFloorDelta_norm` | Achter-rechts |
| `self_backFloorDelta_norm` | Achter |
| `self_backLeftFloorDelta_norm` | Achter-links |
| `self_leftFloorDelta_norm` | Links |
| `self_fwdLeftFloorDelta_norm` | Vooruit-links |

Bruikbaar voor richel-detectie op maps met bruggen/afgronden (zoals CTF-Face) én voor lage overspringbare obstakels, zonder afhankelijkheid van pathfinding.

## Low rays (8)

Horizontale stralen op **voethoogte** (`LOW_RAY_HEIGHT` = 8 UU boven de voeten), in dezelfde 8 sectoren (yaw-relatief). De yaw-relatieve hoofdwaaier start op borsthoogte (`CollisionHeight × 0.5` ≈ 58 UU boven de voeten) en gaat dus *over* een laag obstakel heen; deze voethoogte-stralen vangen het. Het contrast tussen beide niveaus laat het model een overspringbare richel/krat onderscheiden van een volledige muur. Normalisatie identiek aan de hoofdwaaier: afstand / `maxDist`, clamp [0, 1] (0 = obstakel tegen de voeten, 1 = vrij).

| Feature | Richting |
|---|---|
| `self_fwdLowCollision_norm` | Vooruit |
| `self_fwdRightLowCollision_norm` | Vooruit-rechts |
| `self_rightLowCollision_norm` | Rechts |
| `self_backRightLowCollision_norm` | Achter-rechts |
| `self_backLowCollision_norm` | Achter |
| `self_backLeftLowCollision_norm` | Achter-links |
| `self_leftLowCollision_norm` | Links |
| `self_fwdLeftLowCollision_norm` | Vooruit-links |

---

## Verticale probes (2)

| Feature | Bereik | Schaal | Beschrijving |
|---|---|---|---|
| `self_floorBelow_norm` | [0, 1] | lineair, clamp 1024 UU | Strikt downward trace. 0 = grounded, 1 = mid-air boven diepe void |
| `self_ceilingAbove_norm` | [0, 1] | lineair, clamp 512 UU | Strikt upward trace. 0 = head against ceiling, 1 = open sky |

Lift/drop awareness, mid-air signaal, rocket-jump viability, jump-clearance.

---

## Enemy collision (16+16 per slot, 5 slots)

Dezelfde 16 yaw-relatieve + 16 world-axis stralen, maar afgevuurd vanuit elke vijand. Per enemy-slot (0..4) beschikbaar als `enemy{N}_*Collision_norm`.

| Feature-patroon | Aantal | Beschrijving |
|---|---|---|
| `enemy{N}_fwdCollision_norm` t/m `enemy{N}_fwdLeft30Collision_norm` | 16 per slot | Yaw-relatief vanuit vijand |
| `enemy{N}_posXCollision_norm` t/m `enemy{N}_posXNegY30Collision_norm` | 16 per slot | World-axis vanuit vijand |

Totaal: 5 slots x 32 = 160 features. Het model gebruikt deze om in te schatten of een vijand dicht bij een muur staat (lead-aim correctie) of ruimte heeft om te ontwijken (vuur-besluit).

---

## Temporele vensters in features.json

| Groep | Venster (first, last) | Beschrijving |
|---|---|---|
| Self yaw-relatief (16) | (0, 4) | Recente frames voor obstakel-awareness |
| Self world-axis (16) | (0, 4) | Recente frames |
| Floor-elevation fan (8) | (0, 4) | Recente frames |
| Low rays (8) | (0, 4) | Recente frames |
| Verticale probes (2) | (0, 4) | Recente frames |
| Enemy yaw-relatief (80) | (0, 1) | Alleen huidig frame -- stabiel genoeg |
| Enemy world-axis (80) | (0, 1) | Alleen huidig frame |

---

## Augmentatie

Bij mirror-augmentatie worden de 16 yaw-relatieve self-stralen getransformeerd via ring-interpolatie. World-axis stralen en enemy-collision stralen worden niet geaugmenteerd (onafhankelijk van kijkrichting resp. vanuit vijand-perspectief).

---

## Configuratie

Collision ray features worden geconfigureerd in `resources/models/rl_pawn/features.json`. Alleen features die daar staan worden als input aan het model meegegeven. De onderliggende stralen worden altijd door de webservice berekend en zijn beschikbaar voor alle resolvers.
