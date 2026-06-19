# Movement Targets

Bron van waarheid voor de movement-outputs van het `rl_pawn` joint model.

---

## Target-outputs

Het model voorspelt **continue** movement-waarden (geen categorische softmax). De exacte targetlijst staat in `resources/models/rl_pawn/features.json` onder `target_features`.

| Index | Target | Activatie | Bereik | Beschrijving |
|---:|---|---|---|---|
| 0 | `moveDir_sin` | tanh | [-1, +1] | Sinus van gewenste wereldrichting |
| 1 | `moveDir_cos` | tanh | [-1, +1] | Cosinus van gewenste wereldrichting |
| 2 | `dodge` | sigmoid | {0, 1} | Binaire dodge-initiatie |
| 3 | `bJump` | sigmoid | {0, 1} | Springen |
| 4 | `bDuck` | sigmoid | {0, 1} | Bukken |
| 5 | `bIdle` | sigmoid | {0, 1} | Stilstaan (gedecodeerd naar `MovementPrimitive.IDLE`) |

De overige outputs van het joint model (`yawDelta_norm`, `pitchDelta_norm`, `bFire`, `bAltFire`, `target_index`) staan beschreven in [viewRotationX.md](viewRotationX.md) en [viewRotationY.md](viewRotationY.md).

---

## Kernprincipe: wereldrichting, niet toetsen

`moveDir_sin` en `moveDir_cos` coderen een **wereldruimte-richting** via `atan2(sin, cos)`. Het model leert "ik wil richting theta in de wereld bewegen" — onafhankelijk van kijkrichting. De controller vertaalt de wereldrichting naar de juiste toetscombinatie (forward/strafe/back) op basis van de huidige view yaw.

| Eigenschap | Waarde |
|---|---|
| View-onafhankelijk | Ja — richting is wereldruimte |
| Augmentatie-invariant | Ja — yaw-augmentatie roteert view-features maar niet targets |
| Magnitude-encoding | `sqrt(sin² + cos²) ≈ 0` bij idle, ≈ 1 bij bewegen |

---

## Sector-mapping (runtime)

De continue wereldrichting wordt geconverteerd naar een 8-sector movement-primitief relatief aan de huidige view yaw:

```
                 FORWARD (0°)
                     |
      FORWARD_LEFT   |   FORWARD_RIGHT
           (+45°)    |       (-45°)
                     |
    STRAFE_LEFT -----+----- STRAFE_RIGHT
        (+90°)       |        (-90°)
                     |
      BACK_LEFT      |    BACK_RIGHT
          (+135°)    |       (-135°)
                     |
                  BACK (+/-180°)
```

Hysteresis van ~8 graden voorkomt oscillatie op sectorgrenzen.

---

## Dodge

Het `dodge`-output is binair (sigmoid > 0.5 = dodge). De **richting** van de dodge wordt afgeleid uit de huidige movement-sector:

| Sector | Dodge richting | Dodge byte |
|---|---|---:|
| FORWARD, FORWARD_LEFT, FORWARD_RIGHT | forward | 1 |
| BACK, BACK_LEFT, BACK_RIGHT | back | 2 |
| STRAFE_LEFT | left | 3 |
| STRAFE_RIGHT | right | 4 |

Dodge wordt onderdrukt wanneer de engine al in een dodge-sequentie zit (`DodgeState != NONE`). Het model mag veilig `dodge=1` voorspellen tijdens cooldown — de gate vangt het op.

Bij stochastische inferentie (Bernoulli sampling) worden dodge, jump en duck stochastisch gesampled in plaats van deterministisch afgekapt. Dit is cruciaal voor SAC fine-tuning: het genereert counterfactual data voor de critic.

---

## BC-target-generatie

Targets worden berekend tijdens CSV-generatie uit de werkelijke speler-velocity en -state:

| Target | Bron |
|---|---|
| `moveDir_sin` | `velocityY_norm / speed` (0 bij `speed < 0.01`) |
| `moveDir_cos` | `velocityX_norm / speed` (0 bij `speed < 0.01`) |
| `dodge` | 1 op frame waar `DodgeState` van NONE naar richting gaat, anders 0 |
| `bJump` | `actionFlags.jump` in het huidige frame |
| `bDuck` | `actionFlags.duck` |

---

## Augmentatie

Het model gebruikt **yaw-rotatie augmentatie** met 7 synthetische yaw-offsets (45-graden stappen). Per CSV-sample ontstaan 8 rijen (1 identity + 7 geroteerde view-perspectieven).

| Frame-type | Augmentatie |
|---|---|
| Normale movement | 8 yaw-varianten (1 + 7) |
| Dodge-frames | Identity only (niet geroteerd) |

De target-waarden `moveDir_sin/cos` en `dodge` blijven **identiek** onder yaw-augmentatie — alleen de view-relatieve input features roteren.

Zie [yaw-rotation-augmentation.md](../augmentation/yaw-rotation-augmentation.md) voor details.

---

## Collision wall check (runtime)

Omdat yaw-augmentatie de collision features niet roteert, heeft het model geen sterk trainingssignaal om collision te koppelen aan richtingskeuze. Een runtime collision wall check voorkomt dat de bot tegen muren loopt:

| Eigenschap | Waarde |
|---|---|
| Triggering | Na locomotion gate, voor UDP-emit |
| Inputs | `fwdCollision_norm`, `backCollision_norm`, `leftCollision_norm`, `rightCollision_norm` |
| Gedrag | Blokkeert movement in een richting als collision < threshold |
| Default threshold | 0.15 |
| Configuratie | `runtime.json -> command_controller.general.collision_wall_threshold_norm` (0 = uit) |

---

## Configuratie

| Pad | Beschrijving |
|---|---|
| `resources/models/rl_pawn/features.json` | `target_features` lijst |
| `resources/models/rl_pawn/bc.json` | BC hyperparameters (label smoothing, pos_weight) |
| `resources/config/runtime.json` | `command_controller.general.collision_wall_threshold_norm` |
