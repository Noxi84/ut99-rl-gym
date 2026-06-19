# Temporele Feature-groepen

Beschrijft hoe feature-groepen werken in het `rl_pawn` model.

---

## Kernidee

Het LSTM-model ontvangt een sequentie van frames als input. Niet elke feature heeft dezelfde temporele dekking nodig:

| Feature-type | Benodigde dekking | Reden |
|---|---|---|
| Enemy-tracking (bearing, velocity) | Lang geheugen (20+ frames) | Model moet trajectorie extrapoleren voor aim-lead |
| Collision (obstakels) | Recente frames (4) | Muren veranderen niet; oude obstakels zijn ruis |
| Context (rol, fase, health) | Alleen huidig frame | Langzaam veranderend, altijd actueel |

**Feature-groepen** lossen dit op: elke groep heeft zijn eigen temporele activatievenster binnen de totale sequentie. Frames buiten het venster worden gemaskeerd (op nul gezet). Het LSTM verwerkt de volledige sequentie, maar elke feature ziet alleen de frames die voor hem bedoeld zijn.

---

## Configuratie Schema

Gedefinieerd in `resources/models/rl_pawn/features.json`.

```json
{
  "feature_groups": [
    {
      "_doc": "beschrijving",
      "first_frames": 20,
      "last_frames": 4,
      "features": ["feat_a", "feat_b"]
    },
    {
      "_doc": "beschrijving",
      "first_frames": 0,
      "last_frames": 1,
      "features": ["feat_c"]
    }
  ],
  "target_features": [
    {"name": "moveDir_sin", "type": "continuous"},
    {"name": "bFire", "type": "binary"}
  ],
  "aux_target_features": [
    {"name": "target_index", "type": "categorical_5"}
  ]
}
```

| Veld | Type | Betekenis |
|---|---|---|
| `first_frames` | int >= 0 | Aantal frames aan het **begin** van het venster die actief zijn |
| `last_frames` | int >= 0 | Aantal frames aan het **einde** van het venster die actief zijn |
| `features` | string[] | Feature-namen in volgorde (komen overeen met CSV-kolommen) |
| `features_from` | string | Optioneel. `"rewardgroups"` vult features dynamisch vanuit `rewards.json` |
| `target_features` | object[] | Uitvoer-labels met naam en type (op top-niveau, niet per groep) |
| `aux_target_features` | object[] | Auxiliaire uitvoer-labels (target_index head) |

### Rewardgroup-context

Voor dynamische rolcontext staat er geen harde lijst in `features.json`:

```json
{
  "_doc": "Rewardgroups",
  "first_frames": 0,
  "last_frames": 1,
  "features_from": "rewardgroups"
}
```

De featurevolgorde komt uit de keys van de niet-default groepen in `resources/models/rl_pawn/rewards.json`, in declaratievolgorde. Als een bot meerdere rewardgroups kiest, worden de overeenkomstige features multi-hot gezet.

### Totale vensterlengte

```
total_window = max(first_frames + last_frames)  over alle groepen
```

In de huidige configuratie: `max(20+4, ...) = 24 frames`.

---

## Masking Mechanisme

### Formule

Per groep worden de frames in de **gap** gemaskeerd -- de ruimte tussen het eerste blok en het laatste blok:

```
gap = [ first_frames,  total_window - last_frames )
```

Alles buiten de gap (het eerste blok en het laatste blok) is actief.

### Visualisatie

```
total_window = 24 frames
                 <- 24 frames ->
Index:           0             23
                 |              |

first=20, last=4:
  Eerste blok:   [--------------------]
  Laatste blok:                  [----]
  Gap:           geen (20+4 = total_window)
  Actief:        [========================]  alle 24 frames

first=0, last=4:
  Eerste blok:   (leeg)
  Laatste blok:                 [----]
  Gap:           [...................]
  Actief:        [...................====]  laatste 4

first=0, last=1:
  Actief:        [......................=]  huidig frame

first=0, last=0:
  Actief:        (geen enkel frame)  CSV-only, volledig gemaskeerd
```

### Bijzonder geval: geen gap

Als `first_frames + last_frames >= total_window` is er geen gap en zijn alle frames actief. Dit geldt voor groepen zoals enemy-slots (20, 4) die het totale venster vullen.

### Bijzonder geval: volledig gemaskeerd (CSV-only)

Als `first_frames = 0` en `last_frames = 0` is de gap het volledige venster -- alle frames worden op nul gezet. De features staan wel in de CSV (beschikbaar voor de Python data pipeline), maar het model ontvangt altijd nullen. Nuttig voor features die nodig zijn voor **target-afleiding** maar label leakage veroorzaken als model input.

Concreet voorbeeld: `self_dodgeCooldown_norm` staat in een `(0, 0)` groep -- de Python pipeline leest deze kolom om dodge-targets af te leiden, maar als model-input zou het de voorspelling weggeven.

---

## Data Flow

```
+-------------------------------------------------------------------+
|  EXPERIENCE COLLECTIE                                              |
|  Per-tick game state -> feature resolvers -> CSV/NPZ schrijver     |
+-------------------------------+-----------------------------------+
                                |
                                v
+-------------------------------------------------------------------+
|  TRAINING DATA                                                     |
|                                                                    |
|  Elke rij = een trainingsvenster van total_window frames           |
|                                                                    |
|  Kolommen:                                                         |
|    feat_F1 ; feat_F2 ; ... ; feat_F{N-1} ; label_a ; ...          |
|    (t=0)     (t=1)           (t=N-2)      (t=N-1)                 |
|                                                                    |
|  Alle features uit alle groepen worden per frame geschreven.       |
+-------------------------------+-----------------------------------+
                                |
                                v
+-------------------------------------------------------------------+
|  BC TRAINING (Python / PyTorch)                                    |
|                                                                    |
|  extract_features()                                                |
|    -> X[N_samples, total_window, F]                                |
|                                                                    |
|  apply_temporal_mask()                                             |
|    Voor elke groep: X[:, gap_start:gap_end, cols] = 0              |
|                                                                    |
|  BCSequenceNetwork (LSTM)                                          |
|    input shape: [batch, total_window, F]                           |
|    Gemaskeerde frames leveren geen gradient                        |
|                                                                    |
|  Export -> rl_pawn.onnx + rl_pawn.onnx.data                       |
+-------------------------------+-----------------------------------+
                                |
                                v
+-------------------------------------------------------------------+
|  RUNTIME INFERENCE (30 Hz)                                         |
|                                                                    |
|  SequenceWindowBuffer                                              |
|    Ring buffer: capacity = total_window                            |
|    buildAlignedWindow(total_window, csvFps)                        |
|    -> lijst van total_window GameState-frames                      |
|                                                                    |
|  RealtimeSequenceInputBuilder.build()                              |
|    1. Resolve alle features per frame                              |
|    2. Bouw float[1][total_window][F] tensor                        |
|    3. Apply gap masking per groep                                  |
|                                                                    |
|  ONNX sessie -> float[] raw output                                 |
|                                                                    |
|  IntentBus -> CommandController (30 Hz)                            |
+-------------------------------------------------------------------+
```

---

## SequenceWindowBuffer

De ringbuffer per bot-instantie:

| Methode | Gedrag |
|---|---|
| `append(frames)` | Voegt nieuwe frames toe, trimt op `maxCapacity` |
| `hasEnoughFor(n)` | True als buffer >= n frames bevat |
| `buildAlignedWindow(n, csvFps)` | Selecteert n frames op basis van tijdstempels |
| `latestFrame()` | Huidig frame (meest recent) |
| `prefill(frame, n)` | Vult buffer met n kopieen van frame -- gebruikt na reset |
| `clear()` | Leegt de buffer |

---

## Een nieuwe groep toevoegen

1. Voeg de groep toe aan `features.json` met `first_frames`, `last_frames`, en `features`
2. Implementeer de bijbehorende feature-resolver voor nieuwe feature-namen
3. Voer `deploy.sh --retrain` uit om CSV te genereren en te hertrainen

De totale vensterlengte wordt automatisch herberekend. Geen andere codewijzigingen nodig.
