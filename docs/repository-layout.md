# Repository Layout

Directory-tree van het ut99neuralnet monorepo. Het platform draait een joint policy `rl_pawn` (BC pre-training + SAC fine-tuning op een ONNX model) met 10 acties: movement (moveDir_sin/cos + dodge + jump + duck) + viewrotation (yawDelta_norm + pitchDelta_norm) + fire/altFire + target_index aux head.

```
ut99neuralnet/
+-- pom.xml                    # Parent POM -- dependency versies centraal
+-- CLAUDE.md                  # Compact handleiding voor Claude Code
+-- java-aiplay/               # Java Bot Process (Maven module)
+-- java-rewards/              # Reward components + catalog (Maven module)
+-- java-features/             # Feature resolvers + enrichers + service (Maven module)
+-- java-liverecorder/         # Live recorder fat-jar (Maven module)
+-- java-behaviortree/         # BT core library (Maven module)
+-- java-config/               # Config-loaders + typed config records (Maven module)
+-- java-model/                # Game state DTOs + UT99 web model + shared utilities (Maven module)
+-- train/                     # Python training pijplijn
+-- resources/                 # Configuratie + per-model JSON
+-- scripts/                   # Deploy + runtime + champion + eval shell-scripts
+-- docs/                      # Gedetailleerde documentatie (deze map)
+-- logs/                      # Lokale dev logs
```

---

## Java -- java-aiplay

Hoofdmodule: bot runtime, executors, RL infrastructure, UDP communicatie, training data generatie.

```
aiplay/
+-- MultiInstanceLauncher              # Production entry point: N bots in 1 JVM
+-- GenerateTrainingCsvMain            # JSON recordings -> CSV voor BC
+-- GenerateExperienceFromRecordingsMain  # .rec.gz -> .npz experience replay
+-- ConvertJsonRecordingsToRecGzMain   # JSON -> .rec.gz binary format
+-- DiscoverMapsInRecordingsMain       # Scan recordings voor unieke mapnamen
+-- ExtractMapBoundsMain              # T3D map dumps -> per-map JSON config
|
+-- behaviortreebuilder/       # BT-node factory + manuele DI
+-- runtime/                   # Bot runtime: lifecycle, ports/adapters, role registry
|     +-- adapter/live/        # LiveGameStateSource, SystemClock
|     +-- config/              # NeuralNetEndpointResolver, PolicyRole, Ut99InstallResolver
|     +-- controlplane/        # Machine inventory (leest servers.json)
|     +-- port/                # GameStateSource, CommandSink, InferencePort, RuntimeClock
|     +-- promotion/           # Candidate status, promotion records, active bindings
|
+-- scanners/                  # Hot path: feature extraction + model execution
|     +-- executors/
|     |     +-- command/       # CommandController -- sole command emitter (50 Hz)
|     |     +-- common/policy/ # SequenceWindowBuffer (gedeeld door joint executor)
|     |     +-- rlpawn/        # Joint executor (30 Hz)
|     |     |     |            # RLPawnActionDecoder, RLPawnModelSpec, RLPawnCommitmentTracker,
|     |     |     |            # RLPawnSpectatorFilter, RLPawnFireDecisionProcessor
|     |     |     +-- movement/  # MovementActionDecoder, MovementIntentMapper, MovementOutput
|     |     +-- mission/       # MissionPlayExecutor (5 Hz)
|     +-- feature/             # Training feature resolvers + enrichers
|     |     +-- contract/      # Feature contract types + RealTimeFeatureEnricher SPI
|     |     +-- jsontodtoconverters/  # JSON -> feature DTO converters
|     |     +-- resolver/      # Per-feature resolver implementaties
|     +-- model/               # Training model pipeline (augmentatie, dedup, CSV writer)
|           +-- augmentation/  # Mirror transform + projectoren
|           +-- dedup/         # State-bucket dedup
|           +-- feature/       # Per-model feature wiring
|           +-- resolver/
|           |     +-- rlshared/ # Training logger
|           |     +-- rlpawn/   # Target projectoren, augmentatie policy,
|           |                   # sample generator, training model component
|           +-- sample/, target/, validation/, writer/
|
+-- mission/                   # Policy profiles, rule-based mission policy, stuck detector
+-- engagement/                # Engagement policy
+-- tactical/                  # Tactical policy + movement constraint applier
|
+-- play/                      # AiPlayFacade, AiPlayService, UdpCommandSender,
|     |                        # StateFrameToGameStateConverter
|     +-- udpstate/            # UdpStateReceiver (+ FrameReassembler, StateFrameCodec, model/)
+-- prediction/                # GenericPredictor, ModelSpec, PredictionDtoPopulator
|     +-- batch/               # BatchDispatcher, BatchingInferencePort (multi-output batching)
+-- rl/                        # Reinforcement learning infrastructure
|     +-- ExperienceCollector, RewardComputer, ReplayBufferWriter,
|     |   PerModelExperienceRecorder, RealtimeSequenceInputBuilder, ModelWatcher,
|     |   RLConfig, RLExperienceHook  # RLConfig = SAC-algoritme-knobs (sac.json)
|     +-- JointRewardDecompositionStrategy (RewardSignal->kanaal routing-tabel),
|     |   RewardChannel, JointRewardWeights
|     +-- champion/            # ChampionFingerprint, SnapshotMeta, SnapshotResolver
|     +-- recording/           # RawGameplayRecorder, RawGameplayReader (.rec.gz)
|     +-- targeting/           # JointTargetAttribution (single source of truth)
|     |                        # reward components: aparte module java-rewards
+-- instance/                  # InstanceConfig
```

### Java -- java-model

Shared foundation-laag: game state DTOs, UT99 web model, runtime utilities, shared buses.

```
aiplay/
+-- dto/                       # Game-state DTOs (GameStateDto, FlagDto, PickupDto,
|                              # ProjectileDto, JumpPadDto, ViewRotationDto, ...)
+-- ut99webmodel/              # UT99 web API model classes (Player, GameState, Flag, ...)
+-- prediction/
|     +-- PredictionDto        # Per-model voorspellings-output (label, value, logits)
+-- rl/
|     +-- MovementPrimitive    # 6-dim movement actie-enum
+-- logging/                   # SessionRollingLogger, SessionLogPaths
+-- runtime/
|     +-- config/              # ActiveMapConfigResolver, CoordinatesConverter, SessionPaths
|     +-- context/             # ActiveMapContext, MapKey, PlayerIdentityContext
|     +-- identity/            # IdentityLookups
|     +-- role/                # ModelRole, ModelRoleRegistry
+-- util/                      # NormalizationUtils, WeaponNameCanonicalizer
+-- shared/                    # Foundation-laag voor alle cross-module gedeelde types:
      |                        #   intents + thread-safe buses + enums (één thuisbasis)
      +-- engagement/          # AttentionTargetType, EngagementType, EngagementIntent
      +-- matchcontext/        # MatchTimingUtils
      +-- mission/             # MissionType, MissionIntent
      +-- movement/            # MovementIntent, MovementIntentBus, PolicyIntentBus
      +-- objective/           # CounterGrabResolver
      +-- shooting/            # ShootIntent, ShootIntentBus, ShootingTargetIndexBus, ShootingIntentStateBus
      +-- state/               # GameStateBus, GameStateSnapshot
      +-- tactical/            # TacticalIntent, TacticalIntentBus, TacticalType, TacticalReason,
      |                        #   TacticalConstraintMode, TacticalTerritoryBoundary
      +-- view/                # ViewTargeting, ViewTurnIntent, ViewTurnIntentBus, FireModeAimTargeting,
                               #   EnemySpawnTargeting, EnemySpawnYawFallback
```

### Java -- java-features

Feature-resolutie laag: feature interface, discovery service, per-feature resolvers.

```
aiplay/scanners/feature/
+-- ITrainingFeature            # Feature interface
+-- TrainingFeatureService      # ClassGraph-based feature discovery + singleton
+-- CanonicalPerspectiveNormalizer  # Rood-team 180 graden flip
+-- contract/                   # FeatureContract, FeatureContractRepository, validator
+-- jsontodtoconverters/        # CollisionsConverter, KeyboardMoveDtoConverter, ViewRotationConverter
+-- resolver/                   # Per-feature resolvers (ammo, enemy, flag, jumppad, mapidentity,
                                # matchcontext, movement, navigation, playerpawn, projectile,
                                # rewardgroup, role, shoot, shootingintent, shootingtarget,
                                # team, teammate, translocator, viewtargetpitch, weaponidentity,
                                # weaponstate)
```

### Java -- java-rewards

Reward-laag: alle reward-componenten + catalog voor het joint `rl_pawn` model. Eigen Maven-module (hangt af van java-model, java-config, java-features). Geconsumeerd door `aiplay.rl.*` in java-aiplay (RewardComputer, JointRewardDecompositionStrategy).

```
aiplay/rl/rewards/
+-- core/                       # RewardComponent (SPI: compute(RewardContext) -> double),
|                               #   RewardContext, RewardBreakdown (double[] op RewardSignal.ordinal()),
|                               #   RewardSignal + RewardCategory (uitkomst-signaal catalog),
|                               #   RewardTuningConfig, RewardUtils, LeadAimUtils
+-- movement/ aim/ team/ combat/ objective/   # 24 per-reward slices, gegroepeerd per skill
|     +-- <reward>/             # bv. objective/flagevent/, combat/combatevent/
|           +-- <Name>Params    #   typed RewardBlock-record (data)
|           +-- <Name>Reward    #   RewardComponent (gedrag)
|           +-- <Name>Module    #   RewardModule<Params>: id + parse + create
+-- team/endgame/               # EndgameUrgency (gedeelde helper voor de 3 team-rewards)
+-- catalog/                    # RewardCatalog, RewardBlock, RewardMetadata, RewardId,
      |                         #   RewardKind, RewardOwner, EndgameUrgencyParams,
      |                         #   RewardModule (SPI), RewardModules (registry + guard),
      |                         #   RewardParseSupport (strikte JSON-helpers), RewardComponentContext
      +-- json/                 # JsonRewardCatalog (orchestrator: rewardgroup-merge + EnumMap-façade)
```

> Config-laag-scheiding: `RewardTuningConfig` (reward-params uit `rewards.json`) leeft hier in
> java-rewards; `RLConfig` (SAC-algoritme-knobs uit `sac.json`) in java-aiplay; `PickupConfigRepository`
> in java-config. De reward-componenten zien via `RewardContext` alléén `RewardTuningConfig`, nooit de
> volledige RLConfig — zo blijft de module-DAG (java-aiplay → java-rewards → java-config) acyclisch.

### Java -- java-liverecorder

Aparte Maven-module voor het opnemen van menselijk gameplay (keyboard + game state) naar JSON.

---

## Resources

```
resources/
+-- config/
|     +-- files.json             # Sessions + recordings directory paden
|     +-- runtime.json           # Runtime config (player, server, recording, logging,
|     |                          #  shooting per-weapon timings, controllers)
|     +-- gameplay.json          # Active map, bot roster (ai_bots/ut99_bots/appearance), debug
|     +-- servers.json           # Machine inventory (hostname, ssh, capacity, roles, ports)
|     +-- deploy.json            # Deploy controls (hosts filter, restart-bots, model flags)
|     +-- roles.json             # Model role binding (pawn_policy -> rl_pawn)
|     +-- pickup-types.json      # Pickup type registry -- categorieen + per-type defaults
|     +-- maps/                  # Per-map config: <mapKey>.json
|                                # (map_norm, symmetric, spawn_points, jump_pads, pickups)
+-- models/
      +-- index.json             # Model registry -- alleen modellen hier worden geladen
      +-- rl_pawn/               # Per-model JSON config bestanden
```

### Per-model bestanden (`resources/models/rl_pawn/`)

| Bestand | Inhoud |
|---|---|
| `features.json` | Input feature-groepen + temporele windows + `target_features` + `aux_target_features` (target_index head) |
| `model.json` | Network architecture (hidden_size, num_layers, dropout, player_hidden_dim, player_embed_dim) |
| `bc.json` | BC training hyperparameters + multi-head loss weights + data_loader settings |
| `sac.json` | SAC training hyperparameters (joint actor + multi-head critic) |
| `rewards.json` | Reward weights + rewardgroups configuratie |
| `runtime.json` | Inference runtime (prediction_fps, target_commitment_lock_ticks, idle thresholds) |
| `training_csv.json` | CSV-generatie config (csv_fps, target_lookahead_frames, state_bucket_key, scales) |
| `baseline.json` | KPI-baselines voor DualKPI DeltaGate (combat_score, shots_on_target_rate, flag_score) |
| `export_gate.json` | DualKPI DeltaGate parameters (promote/rollback ratios, eval cycle) |
| `probe.json` | SAC export-time probes (stratified sampling, steering/binary/cross-head limits) |
| `promotion.json` | BC promotion-gate config |

---

## Python (`train/`)

```
train/
+-- requirements-train.txt
+-- common/
|     +-- ModelRoles.py          # Role constants/resolution (pawn_policy)
|     +-- ModelSync.py           # rsync .onnx + .onnx.data naar alle servers + .pt naar trainers
|     +-- Promotion.py           # Model promotion gates + sync
|     +-- PropertyReader.py      # JSON config reader (geen fallbacks)
|     +-- ServerInventory.py     # servers.json reader
|     +-- SessionPaths.py        # Session directory structure
|     +-- TrainerLogger.py       # Logging setup
|     +-- champion_pool.py       # Champion pool snapshot management
|     +-- champion_store.py      # Snapshot store (pin/list/show/delete)
|     +-- ChampionSync.py        # Cross-machine champion-pool sync
+-- model/
|     +-- bc_sequence_network.py # BCSequenceNetwork + PlayerEncoder + TargetHead
|     |                          #  (joint actor: movement + yaw/pitch + fire/altFire + target_index)
|     +-- player_feature_grouping.py
+-- rl/
      +-- shared/                # Gedeelde training infrastructure
      |     +-- bc_cache.py, bc_checkpoint.py, bc_config.py,
      |     |   bc_data_loading.py, bc_training_loop.py
      |     +-- delta_gate.py    # DualKPIDeltaGate (combat_score + shots_on_target_rate AND-promote)
      |     +-- player_scores_eval.py  # In-game KPI aggregator
      |     +-- tests/           # player_scores_eval reset-tests
      |     +-- sac_core/        # Stabiele SAC kernel
      |           +-- networks.py, replay_buffer.py, sac_step.py,
      |           |   checkpoint_io.py, config.py
      |           +-- tests/     # critic-mode dispatch, multi-head critic, dual-KPI gate,
      |                          #  replay-buffer decomp
      +-- rl_pawn/
            +-- trainBC/         # __main__, trainer, data_loading, loss, validation, config_loader
            +-- trainSAC/        # __main__, training_loop, bootstrap, export_validation,
                  |              # joint_batch_provider, probes, strata, config_loader
                  +-- tests/     # aux-target anchor, dual-KPI live wiring, stratified probes
```

### SAC trainer layout

`rl_pawn/trainSAC/` bezit de volledige SAC-orchestratie (training_loop, bootstrap, export_validation, joint_batch_provider, probes, strata, config_loader). Model-specifieke SAC fixes horen daar, niet in `shared/sac_core/`. Het shared kernel is met opzet klein en stabiel.

---

## Scripts (`scripts/`)

```
scripts/
+-- deploy.sh                  # Main deploy orchestrator
+-- install-ut99.sh            # OldUnreal UT99 installer
|
+-- deploy/                    # Deploy subscripts (zie scripts-reference.md)
|     +-- common.sh, status.sh, kill-processes.sh, sync-code.sh, clean-logs.sh,
|     |   clean-experience.sh, compile-ucc.sh, extract-map-bounds.sh,
|     |   start-bots.sh, sync-recordings.sh
|     +-- prepare-csv.sh, train-bc.sh, train-sac.sh
|     +-- replay-export.sh, reset-sac-baseline.sh, reset-sac-to-bc-baseline.sh,
|     |   reset_sac_checkpoint.py
|     +-- load_deploy_config.py, load-deploy-config.sh
+-- runtime/                   # Draaien op servers
|     +-- multi_instance.sh    # N bots per machine in 1 JVM
|     +-- sync_replay.sh       # Cross-machine experience sync elke 30s
+-- mutator/                   # UnrealScript source + fallback .u + ini files
      +-- NeuralNetWebserver/Classes/
            +-- NeuralNetWebserver.uc       # UWeb endpoint (curl-only debug pad)
            +-- RLUdpCommandReceiver.uc     # Binary UDP: actions Java -> UT99 (hot path)
            +-- RLUdpStateSender.uc         # Binary UDP: state UT99 -> Java @ 60 Hz
            +-- RLBot.uc                    # Headless RL bot
            +-- RLCTFGame.uc               # Custom CTF gametype (auto-start, RLBot blue)
            +-- *OnlyArena.uc              # Per-weapon arenas (FlakOnly, ShockOnly, etc.)
```

Zie [scripts-reference.md](scripts-reference.md) voor de complete script-tabel.

---

## Sessions directory (niet in git)

`/home/kris/projects/ut99neuralnet-sessions/` op alle machines:

```
ut99neuralnet-sessions/
+-- models/trainingmodel/             # ONNX + PT checkpoints (.onnx + .onnx.data)
+-- csv-training-data/rl_pawn/        # BC training CSVs (data_part*.csv)
+-- csv-training-data-staging/        # Worker-local CSV output tijdens distributed gen (ephemeral)
+-- csv-training-data-inbox/          # Trainer-side collection voor publish (ephemeral)
+-- bc-cache/rl_pawn/                 # Preprocessed BC data cache (mmap'd numpy arrays + manifest)
+-- rl-replay-buffer/rl_pawn/         # SAC experience (batch_*.npz)
+-- experience-recordings/            # .rec.gz gameplay recordings (BC input)
|     +-- rl_pawn/
+-- json-recording-sessions/          # RecordLauncher output (pro gameplay)
+-- logs/                             # Training + session logs
```

Sessions-dir wordt geconfigureerd via `resources/config/files.json` (`sessions_dir`, `recordings_dir`).
