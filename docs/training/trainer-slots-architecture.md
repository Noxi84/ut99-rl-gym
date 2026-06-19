# Trainer Slots Architectuur

Bron van waarheid voor hoe de trainer-slots in `resources/config/servers.json` worden gebruikt om BC- en SAC-training, CSV-generatie en model-sync over het cluster te verdelen.

---

## 1. Overzicht

BC en SAC training hebben elk hun eigen slotcapaciteit per machine.

| Veld | Stuurt aan |
|---|---|
| `bc_trainer_slots` | BC pre-training (`rl_pawn`) |
| `sac_trainer_slots` | SAC fine-tuning (`rl_pawn`) |
| `csv_writer_slots` | Gedistribueerde CSV-generatie |

Alle drie zijn onafhankelijk.

| Waarde | Betekenis |
|---|---|
| `0` | machine draait geen processen van dit type |
| `1` | machine mag 1 proces tegelijk draaien |
| `N` | machine mag N processen tegelijk draaien |

Afgeleide rollen:

| Rol | Bepaling |
|---|---|
| Primary trainer | hoogste `bc_trainer_slots + sac_trainer_slots` (tiebreak: GPU instances, dan alfabetisch) |
| BC trainer worker | elke host met `bc_trainer_slots > 0` |
| SAC trainer worker | elke host met `sac_trainer_slots > 0` |

---

## 2. Huidige clusterconfiguratie

| Machine | CUDA | Instances (GPU/CPU) | BC | SAC | CSV | Rol |
|---|---|---|---:|---:|---:|---|
| 4090 | ja | 0 / 0 | 1 | 2 | 4 | Primary trainer, BC/SAC worker, CSV writer |
| 4070 | ja | 5 / 0 | 1 | 0 | 4 | BC worker, CSV writer, bot-runner |
| 3070 | ja | 2 / 0 | 1 | 0 | 2 | BC worker, CSV writer, bot-runner |
| 2070 | ja | 1 / 0 | 0 | 0 | 0 | bot-runner |
| P15v | ja | 1 / 0 | 0 | 0 | 1 | CSV writer, bot-runner |

Trainer-pools:

**BC trainer-pool:**

| Fysieke host | Virtual trainer slots |
|---|---|
| 4090 | `4090_t0` |
| 4070 | `4070_t0` |
| 3070 | `3070_t0` |

**SAC trainer-pool (`rl_pawn`):**

| Fysieke host | Virtual trainer slots |
|---|---|
| 4090 | `4090_s0`, `4090_s1` |

---

## 3. Primary trainer selectie

De primary trainer wordt deterministisch afgeleid uit de trainer workers.

| Prioriteit | Regel |
|---|---|
| 1 | hoogste `bc_trainer_slots + sac_trainer_slots` |
| 2 | hoogste GPU instance count |
| 3 | laagste `machine_id` alfabetisch |

Met de huidige config is de 4090 de primary trainer.

---

## 4. Source of truth per onderdeel

| Onderdeel | Bron |
|---|---|
| Machine inventory | `resources/config/servers.json` |
| Primary trainer keuze | `scripts/deploy/common.sh` |
| SAC model assignment | `UT99_SESSIONS_DIR/sac-trainer-assignments.conf` |
| BC input CSV | `UT99_SESSIONS_DIR/csv-training-data/` op de primary trainer |
| Replay data | `UT99_SESSIONS_DIR/rl-replay-buffer/rl_pawn/` op de assigned SAC trainer |

---

## 5. Clusterarchitectuur

```
                           TRAINER SLOTS CLUSTER

  DEV MACHINE
  -----------
  deploy.sh
  prepare-csv.sh
        |
        v
  +------------------------------+
  | Primary trainer (4090)       |
  |------------------------------|
  | - csv-training-data/         |
  | - csv-generation-history/    |
  | - sac-trainer-assignments    |
  +---------------+--------------+
                  |
      +-----------+------------+
      |                        |
      v                        v
  +-----------+            +-----------+
  | 4090_t0   |            | 4090_s0/1 |
  | BC        |            | SAC       |
  +-----------+            +-----------+
      |
      +------------------------------+
                                     |
                                     v
                               +-----------+
                               | 3070_t0   |
                               | BC        |
                               +-----------+

  Bot runners and collectors
  --------------------------
  4090 / 4070 / 3070 / 2070 / P15v
      |
      +--> per-model replay sync --> assigned SAC trainer
      +--> model updates receive --> all other hosts
```

---

## 6. BC Architectuur

BC training draait als model-level jobs op BC trainer-slots.

### 6.1 Scheduling

Het deploy-script bouwt een virtual trainer-slot pool uit `bc_trainer_slots`:

| Slot | Host |
|---|---|
| `4090_t0` | 4090 |
| `4070_t0` | 4070 |
| `3070_t0` | 3070 |

Het `rl_pawn` model wordt toegewezen op basis van `bc_trainer_priority` uit `servers.json` (4090 = 1, eerste keuze).

### 6.2 Dataflow

```
  prepare-csv.sh
       |
       v
  Primary trainer
  csv-training-data/rl_pawn/
       |
       +--> indien assigned host != primary trainer:
       |      sync CSV naar remote trainer worker
       |
       +--> start BC proces op assigned slot host
               |
               +--> BC training
               |
               +--> export ONNX op die worker
               |
               +--> ModelSync propageert ONNX naar alle andere hosts
```

### 6.3 Artefactpaden

| Artefact | Locatie |
|---|---|
| Canonieke CSV input | `UT99_SESSIONS_DIR/csv-training-data/rl_pawn/` op primary trainer |
| Tijdelijk gekopieerde CSV input | Zelfde pad op remote BC worker indien nodig |
| ONNX output | `UT99_SESSIONS_DIR/models/trainingmodel/` op de worker die trainde |

---

## 7. SAC Architectuur

SAC draait als persistente per-model assignment op SAC trainer workers.

### 7.1 Model assignment

| Pad | Functie |
|---|---|
| `UT99_SESSIONS_DIR/sac-trainer-assignments.conf` | model -> machine_id mapping |

Lifecycle van de assignment file:

| Stap | Actie |
|---|---|
| `train-sac.sh` | bouwt of herbouwt assignments |
| `train-sac.sh` | publiceert assignments naar alle servers |
| `sync_replay.sh` | leest assignments en routeert replay per model |
| `status.sh` | toont de assignments |

### 7.2 Scheduling

| Factor | Gebruik |
|---|---|
| Bestaande assignments | behouden bij partial restart |
| `sac_trainer_slots` | meer slots = meer load capacity |
| GPU instance count | tiebreak in voordeel van sterkere host |

### 7.3 Replay routing

`sync_replay.sh` routeert per model naar de assigned SAC trainer:

```
  Collector host
      |
      +--> batch_<machine>_*.npz voor rl_pawn
              -> assigned host voor rl_pawn (4090)
```

| Eigenschap | Gedrag |
|---|---|
| Routing frequentie | elke 30s |
| Assignment refresh | bij elke sync loop |
| Local short-circuit | als model aan dezelfde machine is toegewezen, geen rsync |
| Fallback | bij ongeldige assignment, route naar primary trainer |

### 7.4 SAC procesmodel

Het `rl_pawn` model draait als tmux sessie `sac_rl_pawn` op de assigned trainer worker.

---

## 8. Model Sync

ONNX sync is source-host gebaseerd: de machine waar training exporteert synct naar alle andere bekende hosts.

| Scenario | Resultaat |
|---|---|
| BC op 3070 exporteert model | 4090, 4070, 2070 en P15v ontvangen update |
| SAC op 4090 exporteert model | 4070, 3070, 2070 en P15v ontvangen update |

---

## 9. CSV en trainer-slots samenhang

| Subsysteem | Scheduling input | Owner |
|---|---|---|
| CSV-generatie | `csv_writer_slots` | primary trainer publiceert resultaat |
| BC training | `bc_trainer_slots` | assigned BC trainer worker |
| SAC training | `sac_trainer_slots` | assigned SAC trainer worker |

Pipeline volgorde:

```
  recordings
      |
      v
  CSV writers (csv_writer_slots)
      |
      v
  Primary trainer publiceert finale CSV
      |
      v
  BC trainers (bc_trainer_slots)
      |
      v
  ONNX models
      |
      v
  Runtime + SAC trainers (sac_trainer_slots)
```

---

## 10. Status en operations

### 10.1 `status.sh`

`scripts/deploy/status.sh` toont:

| Veld | Betekenis |
|---|---|
| `Instances` | GPU/CPU bot instance split |
| `BC` | `bc_trainer_slots` per machine |
| `SAC` | `sac_trainer_slots` per machine |
| `SAC` (process kolom) | aantal draaiende SAC processen |
| `BC` (process kolom) | aantal draaiende BC processen |
| `Sync` | aantal `sync_replay.sh` processen |
| `tmux` | zichtbare tmux sessies |
| SAC assignments block | model -> machine mapping |

### 10.2 `deploy.sh --restart-sac`

| Stap | Gedrag |
|---|---|
| deploy restart | bot sessions worden herstart |
| kill SAC | alle hosts met `sac_trainer_slots > 0` krijgen SAC cleanup |
| republish assignment | assignment file wordt geschreven en verspreid |
| restart SAC | per model op assigned SAC trainer worker |

### 10.3 SAC cleanup (`kill-processes.sh --kill-sac`)

| Type | Voorbeeld |
|---|---|
| Per-model sessies | `sac_rl_pawn` |
| Python processen | SAC training processen |

---

## 11. Bestandsoverzicht

| Bestand | Verantwoordelijkheid |
|---|---|
| `resources/config/servers.json` | Machine inventory met `bc_trainer_slots`, `sac_trainer_slots`, `csv_writer_slots` |
| `scripts/deploy/common.sh` | Parsing, primary trainer, BC/SAC trainer workers |
| `scripts/deploy/train-bc.sh` | BC scheduling over BC trainer-slots |
| `scripts/deploy/train-sac.sh` | SAC assignments over SAC trainer-slots + start per model |
| `scripts/runtime/sync_replay.sh` | Per-model replay routing naar assigned SAC trainer |
| `scripts/deploy/status.sh` | Cluster status + SAC assignment snapshot |
