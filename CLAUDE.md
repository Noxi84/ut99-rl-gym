# CLAUDE.md

Guidance voor Claude Code in deze repo. Compact: index + kritieke commando's + working preferences. **Alle gedetailleerde architectuur / scripts / configuratie staat in `docs/`.**

UT99-bot RL trainingsplatform — Java bot-proces + Python training, gedistribueerd over 5 Linux Mint machines. Joint LSTM `rl_pawn` (één model = movement + yaw/pitch + fire/altFire + target aux head). BC pre-training + SAC fine-tuning.

---

## Detailed docs (lees on-demand)

**Architectuur / overzicht**
- [docs/repository-layout.md](docs/repository-layout.md) — directory tree, packages, sessions layout
- [docs/platform-architecture.md](docs/platform-architecture.md) — runtime compositie, ports/adapters, role registry, control plane
- [docs/multi-machine-specs.md](docs/multi-machine-specs.md) — hardware specs per machine
- [docs/self-play-architecture.md](docs/self-play-architecture.md) — multi-bot config, ThreadLocal propagation, `SKIP_SERVER_START`
- [docs/scripts-reference.md](docs/scripts-reference.md) — alle shell scripts + Java main classes
- [docs/deploy-config.md](docs/deploy-config.md) — `deploy.json` flags, scenarios, pipeline-volgorde

**Configuratie**
- [docs/config/json/servers.md](docs/config/json/servers.md) — `servers.json` machine inventory
- [docs/config/json/gameplay.md](docs/config/json/gameplay.md) — `gameplay.json` reference (top-level + ai_bots/ut99_bots/appearance, skin/face/voice tabellen, flow-diagram)

**Training pipeline**
- [docs/training/training-model-architecture.md](docs/training/training-model-architecture.md) — joint model, BC + SAC fine-tuning overzicht
- [docs/training/trainer-slots-architecture.md](docs/training/trainer-slots-architecture.md) — BC/SAC/CSV trainer-slot scheduling
- [docs/training/training-csv-writer.md](docs/training/training-csv-writer.md) — distributed CSV-generatie, shard manifests
- [docs/training/delta-gate.md](docs/training/delta-gate.md) — DualKPIDeltaGate promotion (3 KPI's: combat_score AND shots_on_target_rate AND flag_score)

**Joint model**
- [docs/models/model-config-architecture.md](docs/models/model-config-architecture.md) — split config systeem voor `resources/models/rl_pawn/`

**Features**
- [docs/features/index.md](docs/features/index.md) — feature index voor het joint model
- [docs/features/feature-groups-architecture.md](docs/features/feature-groups-architecture.md) — temporele groepen, gap masking, window config
- [docs/features/map-movers.md](docs/features/map-movers.md) — map movers + elevator triggers: statische T3D-extractie + runtime UDP pipeline (tag 0x06) + egocentrische features (4 slots × 14 features)

**Perspectief-normalisatie**
- [docs/augmentation/perspective-normalization.md](docs/augmentation/perspective-normalization.md) — canonical perspective (180° voor rood team)

**Policy / tactics**
- [docs/policy/mission-architecture.md](docs/policy/mission-architecture.md) — mission-laag + engagement/tactical

**TODO (design / niet geïmplementeerd)**
- [docs/TODO/team-coordination.md](docs/TODO/team-coordination.md) — CTDE architectuur (Fase 0-2.5 LIVE: closest-2 joint-state critic + 6e team_assist head; Fase 3+ open horizon)
- [docs/TODO/team-coordination-rollout.md](docs/TODO/team-coordination-rollout.md) — Fase 2 + 2.5 rollout recap (COMPLETED) — Bear/Hawk-rolgedrag fix
- [docs/TODO/pbt-reward-tuning.md](docs/TODO/pbt-reward-tuning.md) — PBT meta-optimization voor reward-weight-schalen
- [docs/TODO/route-cost-phi-design.md](docs/TODO/route-cost-phi-design.md) — PBRS route-cost Φ ontwerp (draft)
- [docs/TODO/test-architecture.md](docs/TODO/test-architecture.md) — geplande scenario-harness + fake adapters (ports/adapters-testbaarheid; nog niet geïmplementeerd)

**Rewards**
- [docs/rewards/reward-architecture.md](docs/rewards/reward-architecture.md) — joint reward pipeline + per-skill decomp
- [docs/rewards/training-parameters.md](docs/rewards/training-parameters.md) — alle hyperparameters voor het joint model
- [docs/rewards/sparse-events.md](docs/rewards/sparse-events.md) — sparse-event rewards
- [docs/rewards/geodesic-distance-field.md](docs/rewards/geodesic-distance-field.md) — geodesische route-afstand voor progress-shaping (bezoekgraaf uit gameplay; per-map `geodesic_field` flag + `<map>.geodesic.json`)

**Transport (UT99 ↔ Java)**
- [docs/webservicemod/webservice-mod-architecture.md](docs/webservicemod/webservice-mod-architecture.md) — binary UDP + UWeb debug

**Safety / recovery**
- [docs/ammo-deadlock-guard.md](docs/ammo-deadlock-guard.md) — detecteer all-RLBots-zonder-ammo deadlock; forceer suicide+respawn van één bot via UDP magic 0xAB

---

## Kritieke commando's

```bash
# Build (geen tests)
./mvnw package -DskipTests

# Deploy — leest resources/config/deploy.json
./scripts/deploy.sh

# Server status (CPU, GPU, processes, tmux, VRAM)
bash scripts/deploy/status.sh
```

Zie [docs/scripts-reference.md](docs/scripts-reference.md) voor de volledige scripts-tabel en Python entrypoints.

---

## Multi-Machine quick reference

5 Linux Mint 22.3 machines (alle CUDA behalve dev). **Nooit Python parsing of ML training op de dev-machine** — geen CUDA.

| Machine | Hostname | Rol |
|---|---|---|
| Dev | n.v.t. (lokaal) | Code + deploy |
| 4090 | `desktop-4090.fritz.box` | Primary trainer (BC + SAC) |
| 4070 | `desktop-4070.fritz.box` | BC training + experience collection |
| 3070 | `desktop-3070.fritz.box` | BC training + experience collection |
| 2070 | `desktop-2070.fritz.box` | Experience only |
| P15v | `LAPTOP-P15v.fritz.box` | Experience only |

Instance counts (GPU/CPU split), `bc_trainer_slots`, `sac_trainer_slots`, `csv_writer_slots` zitten in `resources/config/servers.json`. Hardware specs: zie [docs/multi-machine-specs.md](docs/multi-machine-specs.md).

**SSH naar elke machine:** `ssh kris@<hostname>`. Het wachtwoord staat in de niet-getrackte `resources/config/secrets.local.json` (key `ssh_password`) — **niet** in git. Non-interactief: `sshpass -p "$(jq -r .ssh_password /home/kris/projects/ut99neuralnet/resources/config/secrets.local.json)" ssh -o StrictHostKeyChecking=no kris@<hostname>`. SSH start in `/home/kris/`; gebruik absolute paden of `cd /home/kris/projects/ut99neuralnet &&` prefix (ook **binnen** tmux command strings).

**Sessions directory** (NIET in git): `/home/kris/projects/ut99neuralnet-sessions/` — zie [docs/repository-layout.md](docs/repository-layout.md#sessions-directory-niet-in-git) voor structuur.

**Server-load check:** wanneer de gebruiker "load" of server status vraagt, run `bash scripts/deploy/status.sh`.

---

## Working Preferences

- **Never stop early** — als de gebruiker vraagt om te blijven experimenteren / itereren door de nacht of voor extended period: KEEP GOING tot de deadline. Niet samenvatten en stoppen. Continue experimenteren, evalueren, nieuwe approaches proberen tot opgegeven tijd.
- **Project knowledge in CLAUDE.md + docs/** — kerninstructies en index in CLAUDE.md, alle gedetailleerde architectuur in `docs/`. Prefereer docs uitbreiden boven CLAUDE.md verbreden.
- **No symlinks** — nooit symlinks maken in het project.
- **No config fallbacks** — JSON config-properties hebben geen default/fallback waarden. Property missing → crash (exception, KeyError). Geldt voor `GlobalConfigRepository`, `RLConfig`, Python `PropertyReader`. Silent defaults maskeren missing config en veroorzaken hard-te-debuggen verschillen tussen omgevingen.
- **Git add nieuwe files** — altijd `git add` nieuwe bestanden zodat ze niet als unversioned in IntelliJ verschijnen.
- **Never commit or push** — alleen `git add` of `git rm`. Gebruiker commit en pusht via IntelliJ.
- **Fix autonomously** — geen permission vragen, gewoon issues direct fixen.
- **Active investigator default** — Bij diagnostische, prescriptieve of monitor-taken: eerste actie is *altijd* investigatie (Read, grep, log-mine, hypothese-test), nooit vragen-terug. "Welke route wil je?" / "welke logs?" is failure mode equivalent aan hallucinatie. Vraag alleen terug wanneer data écht alleen uit menselijke observatie kan komen (bv. live gameplay-indrukken, subjectieve voorkeuren). In `/loop` of monitor-cycles: elke iteratie = deep check (parse recente logs, diff vs vorige check, grep op specifieke failure-patronen, journal-entry, hypothese, expliciete actie of expliciet-onderbouwd-wachten) — geen "process alive, no errors" health checks. Distinction: investigatie = gewoon doen; substantiële content-changes aan jouw project-docs/configs = wel afstemmen.
- **Eén joint model** — `rl_pawn` is de enige low-level policy: movement (6 dims) + yaw/pitch (2 dims) + fire/altFire (2 dims) + aux target_index head. BC pre-training + SAC fine-tuning op één ONNX.
- **BC retrain parallel** — wanneer `rl_pawn.train-bc: true` in `deploy.json`, distribueert BC zich automatisch over alle machines met `bc_trainer_slots > 0`.
- **`bc_trainer_slots` / `sac_trainer_slots`** in `servers.json` controleren onafhankelijk hoeveel concurrent trainers per machine; 4090 is de primary trainer by default.
- **Model sync is automatisch, config sync NIET** — trainers syncen ONNX (`.onnx` + `.onnx.data`) naar alle servers na elke save via `ModelSync.py`. Beide files zijn vereist (`.onnx.data` bevat external tensor weights). JSON config files in `resources/models/<mk>/` en `resources/config/` worden ALLEEN gesynct door `scripts/deploy/sync-code.sh` (per-host) of via `scripts/deploy.sh` (volledige deploy). Bij hot-fix van een gate/probe/sac config zonder volledige deploy: SCP de file zelf naar de trainer-host (typisch 4090) en doe `train-sac.sh rl_pawn` — anders draait trainer met stale config.
- **Experiments journal** — `experiments/journal_<YYYY-MM-DD>.md` per actieve trainings-sessie. Bevat pre-state snapshot, config-changes per Round, hypothesen, post-cycle observaties. Niet in git getrackt is OK; gebruikt voor cycle-naar-cycle continuïteit van autonome training-sessies.
- **No hardcoded feature IDs** — feature IDs komen altijd uit model feature config (`ModelConfigRepository` of `PropertyReaderUtils`), nooit hardcoded in Java code. Uitzondering: `TrainingFeatureValueResolver` implementaties die specifieke IDs by name resolven (switch/case op feature ID strings).
- **No fallback movement** — bot beweegt alleen via RL policy model. Geen fallback (bv. walk-forward) wanneer model niet geladen. Bot wacht tot `ModelWatcher` model laadt.
- **No quick hacks in CommandController** — nooit hardcoded behavioral fixes (dwell bypasses, turn-class-conditional logic, event-specific overrides) in `CommandController` of `LocomotionGate`. Controller blijft een dumb actuator. Synchronisatie tussen movement en viewrotation moet architectureel opgelost worden (features, model training, scheduling), niet door controller-patches.
- **Joint SAC orchestratie** — `train/rl/rl_pawn/trainSAC/` bezit de volledige SAC-orchestratie (`training_loop`, `bootstrap`, `export_validation`). `train/rl/shared/sac_core/` is de stabiele kernel — alleen voor genuine algorithm-bugs. Nooit `if model_key == "..."` branches in shared code.
- **Ignore spectators** — UT99 servers kunnen spectators hebben (bv. dev PC kijkt). De UC webservice filtert ze uit (`Spectator(P) == None`). Player identification via `Name` matching tegen `MrPython`. `PlayerPawn` marker class is verwijderd — gebruik `bIsABot`/`bIsSpectator` velden uit `PlayerReplicationInfo` indien nodig.
- **Deploy cleant logs** — bij `clean-logs: true` (default) verwijdert `deploy.sh` log-bestanden op elke server tijdens restart. Voorkomt onbeperkte groei.
