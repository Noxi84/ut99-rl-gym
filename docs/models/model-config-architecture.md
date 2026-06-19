# Model Config Architecture

## Overzicht

Alle configuratie leeft als JSON-bestanden in `resources/`. Globale applicatieconfig staat in `resources/config/`, per-model config in `resources/models/<modelKey>/`. De loaders (Java en Python) detecteren deze structuur automatisch, mergen alles naar een in-memory configboom, en bieden typed access via repositories.

Geen monolithische config file en geen fallbacks. De split-structuur is de enige bron van waarheid.

```
resources/
+-- config/                         Globale applicatieconfig
|   +-- gameplay.json                 Bot roster, mapName, debug
|   +-- files.json                    Sessie-directory
|   +-- runtime.json                  Server, player, RL, controller, etc.
|   +-- ammo-deadlock-guard.json      Deadlock-detectie config
|   +-- roles.json                    Rolconfiguratie
|   +-- servers.json                  Machine-inventory
|   +-- maps/                         Per-map normalisatie + spawns + jump pads
|       +-- CTF-andACTION.json
|       +-- CTF-ThornsV2.json
|       +-- ...
|
+-- models/                         Per-model config
    +-- index.json                    Model registry
    +-- rl_pawn/
        +-- runtime.json               Inferentie-frequentie, target-lock
        +-- training_csv.json          CSV-generatie parameters
        +-- model.json                 Netwerk-architectuur
        +-- bc.json                    BC hyperparameters
        +-- sac.json                   SAC hyperparameters
        +-- features.json              Input/output feature-lijsten
        +-- rewards.json               Reward-configuratie per rewardgroup
        +-- export_gate.json           DeltaGate promotie-criteria
        +-- promotion.json             BC promotie-criteria
        +-- probe.json                 Probe-configuratie
        +-- baseline.json              Baseline-configuratie
```

---

## Laadproces

```
+--------------------------------------------------------------+
|                    Bestanden op disk                          |
|                                                              |
|  resources/config/                resources/models/          |
|  +-- gameplay.json                +-- index.json             |
|  +-- files.json                   +-- rl_pawn/*.json         |
|  +-- runtime.json                                            |
|  +-- maps/CTF-*.json                                         |
+---------------+------------------------+---------------------+
                |                        |
        +-------v---------+      +-------v--------+
        | Root config      |      | Property reader |
        | loader (Java)    |      |   (Python)      |
        +-------+----------+      +-------+---------+
                |                         |
                v                         v
        Een gemerged JSON-boom     Een gemerged dict
                |                         |
     +----------+----------+              |
     v          v          v              v
  Global    Model      Runtime      Accessor-functies
  config    config     services     per model en sectie
  repo      repo
```

**Java:** De gemerged JSON-boom wordt gecacht in een atomaire referentie. Globale en model config repositories lezen daaruit via JSON Pointer paden en parsen naar immutable records.

**Python:** De gemerged dict wordt gecacht in een module-globale cache. Accessor-functies navigeren naar de juiste subsectie.

Beide loaders produceren exact dezelfde boomstructuur. Project root wordt bepaald via env var `UT99_PROJECT_ROOT`, of automatisch gedetecteerd.

---

## Model Registry

`resources/models/index.json` bepaalt welke modellen geladen worden. Subdirectories die niet in de index staan worden genegeerd.

Het enige geregistreerde model is `rl_pawn` -- het joint LSTM dat alle low-level acties produceert:

| Output | Dimensies |
|---|---|
| Movement | moveDir_sin, moveDir_cos, dodge, bJump, bDuck (5 dims) |
| Viewrotation | yawDelta_norm, pitchDelta_norm (2 dims) |
| Shooting | bFire, bAltFire (2 dims) |
| Target-selectie | target_index (1 aux head) |

---

## Globale config (`resources/config/`)

### Bestanden

| Bestand | Inhoud |
|---|---|
| `gameplay.json` | Bot roster, mapName, weapon_profile, debug-logging |
| `maps/<mapKey>.json` | Per-map normalisatie (max distances, edge, k), spawn points, jump pads |
| `files.json` | Sessions directory |
| `runtime.json` | Server, player, recording, command controller, mission, RL, logging |
| `servers.json` | Machine-inventory (zie [servers.md](../config/json/servers.md)) |
| `ammo-deadlock-guard.json` | Ammo-deadlock detectie |
| `roles.json` | Rolconfiguratie |

### Globale config slices

De globale config repository biedt typed access voor alle runtime-configuratie:

| Slice | Belangrijkste velden |
|---|---|
| Command controller | Yaw heading (max step, dead zone), locomotion gate, pitch (max step, decay) |
| Mission | Annotator FPS, dwell times, anti-stuck drempels |
| Recording | Player name, key bindings, hold duration max |
| RL | Exploration epsilon, deterministic inference, replay buffer, reward types |
| Logging | Enabled, level, max bytes/files |
| Files | Sessions directory |
| Player | Naam ("MrPython"), team |
| Server | URL, UWeb port, install root |
| View | Max view rotation, window name |
| Gameplay | Near distance norm, actieve map |
| Policy profiles | Benoemde profielen met toegestane missions |

---

## Per-model config (`resources/models/rl_pawn/`)

### Bestanden

| Bestand | Inhoud |
|---|---|
| `runtime.json` | Inferentie-frequentie (30 Hz), target commitment lock, dodge cooldown, idle-detectie |
| `training_csv.json` | CSV-generatie: enabled, FPS, state bucket key, target lookahead frames |
| `model.json` | Netwerk: hidden_size=640, num_layers=2, dropout=0.15, player/map embedding dims |
| `bc.json` | BC hyperparameters: batch size, lr, warmup, early stop, data loader |
| `sac.json` | SAC hyperparameters: lr, gamma, tau, replay buffer, temperature, BC-anchor |
| `features.json` | Timeline features, single features, target features, feature groups |
| `rewards.json` | Reward-configuratie per rewardgroup (default + per-rol overrides) |
| `export_gate.json` | DeltaGate promotie: eval interval, promote/rollback margins per KPI |
| `promotion.json` | BC promotie: max val loss, max regressie vs baseline |
| `probe.json` | Probe-configuratie voor diagnostiek |
| `baseline.json` | Baseline-configuratie voor vergelijking |

### Typische waarden

Geen fallback-defaults. Ontbrekende property = crash bij laden.

| Veld | Waarde |
|---|---|
| `prediction_fps` | 30 |
| `hidden_size` | 640 |
| `num_layers` | 2 |
| `dropout` | 0.15 |
| `device` | `"auto"` |
| `player_hidden_dim` | 64 |
| `player_embed_dim` | 64 |
| `map_embedding_capacity` | 256 |
| `map_embedding_dim` | 16 |

### Validatieregels

| Regel | Foutmelding |
|---|---|
| `timeline_features` niet leeg | `rl_pawn: timeline_features is empty` |
| `target_features` niet leeg | `rl_pawn: target_features is empty` |
| `number_of_columns` > 0 | `rl_pawn: number_of_columns must be > 0` |
| `prediction_fps` > 0 | `rl_pawn: prediction_fps must be > 0 when enabled` |

---

## In-memory configboom

Beide loaders produceren dezelfde structuur:

```
root
+-- near_dist_norm, mapName, ai_bots, ...       <- gameplay.json
+-- maps                                         <- resources/config/maps/
|   +-- <mapKey>: { map_norm, symmetric, spawn_points, jump_pads }
+-- files                                         <- files.json
|   +-- sessions_dir
+-- runtime                                       <- runtime.json
|   +-- ut99_server
|   +-- ut99_player
|   +-- recording
|   +-- command_controller
|   +-- mission
|   +-- rl
|   +-- logging
+-- models
    +-- rl_pawn
        +-- model_key: "rl_pawn"
        +-- runtime                               <- models/rl_pawn/runtime.json
        +-- training_csv                          <- models/rl_pawn/training_csv.json
        +-- model                                 <- models/rl_pawn/model.json
        +-- bc                                    <- models/rl_pawn/bc.json
        +-- sac                                   <- models/rl_pawn/sac.json
        +-- features                              <- models/rl_pawn/features.json
        +-- rewards                               <- models/rl_pawn/rewards.json
```

---

## Runtime services

Runtime resolve-logica leeft in aparte services met environment/system property overrides.

| Service | Verantwoordelijkheid | Override-prioriteit |
|---|---|---|
| Session paths | Sessions dir, model training dir | sys prop -> env var -> config |
| Endpoint resolver | URL/port resolutie voor webservice | Instance config -> sys prop -> env var -> config |
| Install resolver | UT99 install root + game speed | sys prop -> env var -> config |
| Player identity | Effectieve player name/team (immutable na init) | Bot mode: player config / CSV mode: recording config |
| Map config resolver | Map normalisatie (max distances, edge, k) | Uit configboom, case-insensitive |

Player identity wordt exact een keer geinitialiseerd bij JVM-start en is daarna immutable. Alle feature converters lezen hieruit.

---

## Python API

De Python property reader spiegelt de structuur en biedt accessor-functies:

| Functie | Pad in configboom |
|---|---|
| `get_model_config(model_key)` | `/models/<key>/model` |
| `get_bc_config(model_key)` | `/models/<key>/bc` |
| `get_training_csv_config(model_key)` | `/models/<key>/training_csv` |
| `get_features(model_key)` | `/models/<key>/features` |
| `get_sessions_dir()` | Env var `UT99_SESSIONS_DIR` -> `/files/sessions_dir` |

---

## Cache en reload

| Aspect | Java | Python |
|---|---|---|
| Cache-mechanisme | Atomaire referentie | Module-globale dict |
| Invalidatie | Herlaad-functie invalideert ook model en globale repositories | `clear_cache()` |
| Thread safety | Double-checked locking + immutable records | Single-threaded training |
| Model config | Immutable records, atomisch vervangen bij reload | Dict-kopieen |

---

## Foutgedrag

| Situatie | Resultaat |
|---|---|
| Split-structuur bestaat niet | Exception bij opstarten |
| IO-fout bij laden | Exception |
| Model in index maar directory ontbreekt | Exception |
| `index.json` ontbreekt | Exception |
| Onbekende model key | Exception |

---

## Bestands-granulariteit

Een bestand per logische sectie. Niet fijner splitsen.

| Goed | Niet goed |
|---|---|
| `runtime.json`, `bc.json`, `features.json` | Aparte file per hyperparameter |
