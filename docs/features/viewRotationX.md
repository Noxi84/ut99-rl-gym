# ViewRotationX — Yaw (Horizontale Kijkrichting)

## Overzicht

`viewRotationX` is de horizontale kijkrichting (yaw) van de bot. UT99 slaat dit op als 16-bit unsigned rotatie (0..65535). Voor het model wordt dit getransformeerd naar een sin/cos-paar om discontinuiteit op de 0/65535-grens te elimineren.

Het `rl_pawn` model voorspelt `yawDelta_norm` als continu stuurcommando (tanh, [-1, +1]). Runtime wordt dit direct toegepast als draaicommando: `yawDelta_norm * continuous_max_step` = yaw stap per tick.

---

## UT99 Yaw Systeem

UT99 gebruikt 16-bit unsigned rotatie (0..65535) voor yaw. 65536 eenheden = 360 graden (volle cirkel).

```
    UT99 viewRotationX — 16-bit unsigned, volle cirkel

                        0 / 65536
                          |
                     +----+----+
                   /      |      \
                 /   64000| 2000   \
               /          |          \
    49152 ----+           |           +---- 16384
    (270)     |           |           |    (90)
               \          |          /
                 \        |        /
                   \      |      /
                     +----+----+
                          |
                        32768
                       (180)
```

### Het probleem: discontinuiteit bij de 0/65535-grens

De ruwe yaw-waarde heeft een harde sprong bij de grens. De bot kijkt bijna dezelfde richting uit bij 65535 en bij 0, maar de numerieke waarden liggen 65535 eenheden uit elkaar. Dit is desastreus voor een neuraal netwerk:

```
    Bot draait langzaam rond...

    Yaw: 65530 -> 65533 -> 65535 -> 0 -> 3 -> 6
                                    ^
                        SPRONG VAN 65535 -> 0
                        terwijl de bot nauwelijks draait!
```

### De oplossing: sin/cos representatie

Transformatie naar een sin/cos-paar op de eenheidscirkel elimineert de discontinuiteit volledig:

```
    yawRadians = viewRotationX * (2pi / 65536)
    viewRotationX_sin = sin(yawRadians)
    viewRotationX_cos = cos(yawRadians)
```

```
    Bot draait door de 0/65535-grens:

    Ruwe waarden:  65530    65535    0       5
                   ^ SPRONG ^

    Sin/cos:       sin: -0.005  -0.001   0.000   0.001
                   cos:  1.000   1.000   1.000   1.000
                         ^ VLOEIEND ^
```

```
    Voorbeeldtabel:

    +--------------+--------------+--------------+-------------+
    | Richting     | Ruwe waarde  | sin          | cos         |
    +--------------+--------------+--------------+-------------+
    | 0 (start)    | 0            |  0.000       |  1.000      |
    | 45 (NO)      | 8192         |  0.707       |  0.707      |
    | 90 (O)       | 16384        |  1.000       |  0.000      |
    | 135 (ZO)     | 24576        |  0.707       | -0.707      |
    | 180 (Z)      | 32768        |  0.000       | -1.000      |
    | 225 (ZW)     | 40960        | -0.707       | -0.707      |
    | 270 (W)      | 49152        | -1.000       |  0.000      |
    | 315 (NW)     | 57344        | -0.707       |  0.707      |
    | ~360 (~0)    | 65535        | -0.0001      |  1.000      |
    +--------------+--------------+--------------+-------------+

    65535 en 0 hebben bijna dezelfde sin/cos waarden.
    De discontinuiteit is volledig opgelost.
```

---

## Datapipeline

```
UT99 Server
    -> Leest ViewRotation.Yaw (16-bit unsigned, 0..65535)
    -> JSON: Players[].ViewRotation.Yaw
    -> Bot: viewRotationX (int)
    -> Feature resolver: yaw * (2pi / 65536) -> sin(), cos()
    -> Model input: "viewRotationX_sin" + "viewRotationX_cos" (tijdlijnfeatures)
```

---

## Model Output

Het joint `rl_pawn` model voorspelt yaw als continu stuurcommando:

| Positie | Feature | Type | Loss |
|---|---|---|---|
| 0 | `yawDelta_norm` | Continu [-1, +1] (tanh) | Smooth-L1 |

Runtime: `yawDelta_norm * continuous_max_step (3000)` = yaw stap per tick.

---

## Target Generatie (BC)

De BC-target `yawDelta_norm` wordt berekend als de actuele spelersrotatie tussen frame N en frame N+targetLookaheadFrames:

- **yawDelta_norm**: shortestArcDelta(currentYaw, futureYaw) / bc_yaw_target_scale

---

## Augmentatie

Het model gebruikt **mirror augmentatie** (links-rechts spiegeling). Gespiegelde features: `enemy0_relSin`, `self_rightVelocity_norm`, `self_yawAngularVelocity_norm` en target `yawDelta_norm`. Resultaat: 1 base + 1 mirror = 2 CSV-rijen per sample.

Bij yaw-rotatie augmentatie (movement-pad) worden `viewRotationX_sin` en `viewRotationX_cos` herberekend voor de synthetische yaw.

Zie [yaw-rotation-augmentation.md](../augmentation/yaw-rotation-augmentation.md) voor details.

---

## Feature Tabel

### Invoerfeatures (tijdlijn)

| Feature | Bereik | Beschrijving |
|---|---|---|
| `viewRotationX_sin` | [-1, 1] | Yaw sinus-component op de eenheidscirkel |
| `viewRotationX_cos` | [-1, 1] | Yaw cosinus-component op de eenheidscirkel |

Samen geven deze twee waarden de volledige horizontale kijkrichting weer zonder discontinuiteit.

### Doelfeature (model-uitvoer)

| Feature | Bereik | Beschrijving |
|---|---|---|
| `yawDelta_norm` | [-1, +1] | Horizontaal draaicommando (genormaliseerd door bc_yaw_target_scale) |

---

## Configuratie

| Pad | Beschrijving |
|---|---|
| `resources/models/rl_pawn/features.json` | Feature groups (viewRotationX_sin/cos) en target_features (yawDelta_norm) |
| `resources/models/rl_pawn/training_csv.json` | bc_yaw_target_scale (1000), target_lookahead_frames (4) |
| `resources/config/runtime.json` | command_controller.yaw_heading.continuous_max_step (3000) |

**Let op — schalen verschillen:** `bc_yaw_target_scale` (1000) en `continuous_max_step` (3000) zijn **niet** gelijk. De BC-labels worden genormaliseerd door 1000, terwijl de runtime de policy-output met 3000 schaalt; het netto-effect is dat de runtime grotere yaw-stappen toelaat dan de ruwe BC-deltaschaal. SAC fine-tuning kalibreert de policy-magnitude op deze runtime-schaal.
