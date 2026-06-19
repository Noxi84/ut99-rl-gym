# ViewRotationY — Pitch (Verticale Kijkrichting)

## Overzicht

`viewRotationY` is de verticale kijkrichting (pitch) van de bot: omhoog, recht vooruit, of omlaag. UT99 slaat dit op als 16-bit unsigned rotatie met een ongebruikelijk wrap-around systeem, wat speciale normalisatie vereist.

Pitch is een invoerfeature van het `rl_pawn` model en een target-output als continu stuurcommando (`pitchDelta_norm`). Runtime wordt pitch direct gestuurd door het model: `pitchDelta_norm * continuous_max_step` = pitch stap per tick.

---

## Datapipeline

```
UT99 Server
    -> Leest ViewRotation.Pitch (16-bit unsigned, 0..65535)
    -> JSON: Players[].ViewRotation.Pitch
    -> Bot: viewRotationY (int)
    -> Feature resolver: signed conversie + schaling (zie hieronder)
    -> Model input: "viewRotationY_norm" (tijdlijnfeature)
```

---

## UT99 Pitch Systeem

UT99 gebruikt 16-bit unsigned rotatie (0..65535) voor pitch, maar niet lineair. Het systeem heeft een wrap-around rond het midden:

```
    UT99 viewRotationY — 16-bit unsigned met wrap-around

    +-------------------------------------------------------------------+
    |                                                                   |
    |   OMLAAG KIJKEN              MIDDEN              OMHOOG KIJKEN   |
    |                                                                   |
    |   49152 ----------- 65535 / 0 ----------- 18000                  |
    |     |                  |                    |                      |
    |   max omlaag      recht vooruit        max omhoog                |
    |                                                                   |
    |   <-- 16384 eenheden --><---- 18000 eenheden ---->               |
    |       (omlaag bereik)        (omhoog bereik)                      |
    |                                                                   |
    |   Het bereik 18001..49151 is ongeldig / niet gebruikt             |
    |                                                                   |
    +-------------------------------------------------------------------+

    Visueel op een halve cirkel:

                    18000 (+1.0)
                      |  MAX OMHOOG
                      |
                      |
                      |
    ------- 65535/0 --+-- recht vooruit (0.0)
                      |
                      |
                      |
                    49152 (-0.91)
                      |  MAX OMLAAG
```

---

## Normalisatie

```
    Invoer: viewRotationY (0..65535, unsigned 16-bit)

    Stap 1 — Naar signed waarde:
    +--------------------------------------------------------+
    |                                                        |
    |  rotY in 0..18000?       -> signed = rotY    (omhoog) |
    |  rotY in 49152..65535?   -> signed = rotY - 65536     |
    |                            (omlaag, wordt negatief)    |
    |  rotY in 18001..49151?   -> signed = 0 (ongeldig,     |
    |                            behandeld als centrum)      |
    |                                                        |
    +--------------------------------------------------------+

    Stap 2 — Normaliseren:
        viewRotationY_norm = signed / 18000

    Voorbeelden:
    +------------------+------------+--------------------+
    | Ruwe waarde      | Signed     | viewRotationY_norm |
    +------------------+------------+--------------------+
    | 0                | 0          |  0.000 (vooruit)   |
    | 9000             | 9000       | +0.500 (half omhoog)|
    | 18000            | 18000      | +1.000 (max omhoog)|
    | 65535            | -1         | -0.0001 (net onder)|
    | 60000            | -5536      | -0.308 (schuin omlaag)|
    | 49152            | -16384     | -0.910 (max omlaag)|
    | 30000 (ongeldig) | 0          |  0.000 (centrum)   |
    +------------------+------------+--------------------+
```

---

## Asymmetrisch Bereik

Het genormaliseerde bereik is **asymmetrisch**: -0.91 tot +1.0. Dit komt doordat UT99 een groter bereik reserveert voor omhoog kijken (18000 eenheden) dan voor omlaag kijken (16384 eenheden). Het gat (18001..49151) is een ongebruikt segment in de 16-bit ruimte.

```
    -0.91 <------ omlaag ------- 0.0 ------ omhoog ------> +1.0
    ################################################################
    ^                               |                            ^
    max omlaag                 recht vooruit                max omhoog
    (49152 UT)                 (65535/0 UT)                (18000 UT)
```

---

## Model Output

Het `rl_pawn` model voorspelt pitch als continu stuurcommando:

| Aspect | Waarde |
|---|---|
| Feature naam (invoer) | `viewRotationY_norm` |
| Bereik als feature | [-0.91, +1.0] |
| Target feature | `pitchDelta_norm` (continu) |
| Bereik als target | [-1, +1] (tanh) |
| Loss functie | Smooth-L1 (Huber) |
| Runtime | pitchDelta_norm * continuous_max_step (960) = pitch stap per tick |

---

## Target Generatie (BC)

De BC-target `pitchDelta_norm` wordt berekend als de actuele spelersrotatie tussen frame N en frame N+targetLookaheadFrames:

- **pitchDelta_norm**: (futurePitch - currentPitch) / bc_pitch_target_scale

---

## Augmentatie

Pitch wordt **niet geaugmenteerd** — mirror augmentatie spiegelt alleen de horizontale as. `pitchDelta_norm` blijft ongewijzigd in zowel identity als mirror samples.

Zie [yaw-rotation-augmentation.md](../augmentation/yaw-rotation-augmentation.md) voor details.

---

## Configuratie

| Pad | Beschrijving |
|---|---|
| `resources/models/rl_pawn/features.json` | `viewRotationY_norm` in feature_groups (invoer), `pitchDelta_norm` in `target_features` (uitvoer) |
| `resources/models/rl_pawn/training_csv.json` | `bc_pitch_target_scale` (800) — normalisatiedeler voor pitch delta labels |
| `resources/config/runtime.json` | `command_controller.pitch.continuous_max_step` (960), `center_decay_rate` (0.08), `dead_zone_rad` (0.0) |

**Let op — schalen verschillen:** `bc_pitch_target_scale` (800) en `continuous_max_step` (960) zijn **niet** gelijk. De BC-labels worden genormaliseerd door 800, terwijl de runtime de policy-output met 960 schaalt; de runtime laat dus iets grotere pitch-stappen toe dan de ruwe BC-deltaschaal. SAC fine-tuning kalibreert de policy-magnitude op deze runtime-schaal.
