# Platform Architectuur

Bron van waarheid voor de platformarchitectuur: runtime-compositie, modelbinding, ports en adapters, testarchitectuur, modelpromotie en control plane.

---

## Architectuuroverzicht

```
+----------------------------------------------------------------------+
|  CONTROL PLANE -- servers.json -> MachineInventory                    |
|  Workflows / Jobs / Deployments / Trainer-slot toewijzing            |
+----------------------------------+-----------------------------------+
                                   |
                                   v
+----------------------------------------------------------------------+
|  MODEL PROMOTIE -- artifact / threshold / baseline gates             |
|  active_bindings.json / rollback / ONNX sync naar alle servers       |
+----------------------------------+-----------------------------------+
                                   |
                                   v
+----------------------------------------------------------------------+
|  MODEL ROLE REGISTRY -- roles.json -> ModelRole -> model key         |
|  pawn_policy -> rl_pawn                                              |
+----------------------------------+-----------------------------------+
                                   |
                                   v
+----------------------------------------------------------------------+
|  BOT RUNTIME (per instance)                                          |
|  InstanceContext / InstanceServices / Lifecycle                       |
|  Producer thread / Consumer threads / Intent buses                   |
+----------------------------------+-----------------------------------+
                                   |
                                   v
+----------------------------------------------------------------------+
|  PORTS (abstracties)                                                 |
|  GameStateSource / CommandSink / InferencePort / RuntimeClock        |
+----------------------------------+-----------------------------------+
                                   |
                                   v
+----------------------------------------------------------------------+
|  LIVE ADAPTERS                                                       |
|  LiveGameStateSource / UdpCommandSender / GenericPredictor /         |
|  SystemClock / BatchingInferencePort                                 |
+----------------------------------------------------------------------+
```

---

## 1. Runtime Compositie

Elke bot-instance wordt geassembleerd als een expliciet runtime-object via een factory, voor de eerste tick van de behavior tree.

### Opstartvolgorde

```
MultiInstanceLauncher
  |
  +-- SharedRuntimeServices (1x per JVM)
  |     +-- maxPredictionFps
  |     +-- ModelRoleRegistry
  |
  +-- Per instance:
        +-- BotRuntimeFactory.create(InstanceContext, SharedRuntimeServices)
        |     Maakt: buses, ports, predictor, sender, RL, executors
        +-- BehaviorTree bouwen (pure structuur)
        +-- runtime.populateBlackboard(blackboard)
        +-- runtime.start(treeContext)
              +-- validate() -- checkt verplichte services
              +-- startProducerThread() -- producer gaat pollen
              +-- logStartupSnapshot() -- logt port bindings + config
```

### Eigenaarschap

| Eigenaar | Bezit |
|---|---|
| BotRuntime | InstanceContext, InstanceServices, lifecycle, producer thread |
| InstanceServices | Alle buses, ports, RL components, PlayExecutionService |
| Behavior tree decorators | Consumer threads (lezen van blackboard, ticken child nodes) |
| Blackboard | Bridge: runtime zet services, BT nodes lezen ze |

### Lifecycle

```
CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED
```

| Transitie | Wat gebeurt |
|---|---|
| CREATED -> STARTING | validate(), startProducerThread(), logStartupSnapshot() |
| STARTING -> RUNNING | Lifecycle bereikt steady state |
| RUNNING -> STOPPING | running flag = false, join producer + consumer threads |
| STOPPING -> STOPPED | Alle threads gestopt, cleanup gedaan |

### Gedeeld vs Per-Instance

| Gedeeld (JVM-breed) | Per instance |
|---|---|
| ModelRoleRegistry | 7 intent buses |
| ModelConfigRepository | GameStateBus |
| FeatureContractRepository | UdpCommandSender (per-bot, deelt 1 static DatagramChannel) |
| GenericPredictor SESSION_CACHE | UdpStateReceiver (1 per instance, gedeeld tussen bots) |
| GPU_NATIVE_EXECUTOR | GenericPredictor (per-instance useGpu config) |
| | Producer + consumer threads |
| | Lifecycle state |

---

## 2. Model Role Registry

Runtime-consumers kennen modellen alleen via hun architecturale rol, niet via hardcoded model keys.

### Rolbinding

| Rol | Verplicht | Functie |
|---|---|---|
| `pawn_policy` | Ja | Joint policy: movement (moveDir_sin/cos + dodge + jump + duck) + viewrotation (yawDelta_norm + pitchDelta_norm) + fire/altFire + target_index aux head |

De enige binding in `resources/config/roles.json`:

```
pawn_policy -> rl_pawn
```

### Resolutiepad

```
Consumer vraagt: registry.resolve(ModelRole.PAWN_POLICY)
  -> leest roles.json binding: "pawn_policy" -> "rl_pawn"
  -> ModelConfigRepository.shared().get("rl_pawn")
  -> retourneert ModelConfig (features, architecture, runtime settings)
```

### Waar rollen gebruikt worden

| Consumer | Leest rol |
|---|---|
| BotRuntimeFactory | PAWN_POLICY voor ModelWatcher setup |
| RLConfig | PAWN_POLICY voor enabled-check |
| RLPawnModelSpec.loadStrict() | PAWN_POLICY |
| RLPawnExecutorComponent.getPredictionFps() | PAWN_POLICY |
| Bash scripts (common.sh) | resolve_role_model_keys() leest roles.json |
| Python trainers | ModelRoles.resolve_model_key() leest roles.json |

### Startup validatie

Bij startup draait `registry.validate()`:
1. De vereiste pawn_policy rol moet een binding hebben
2. Het gebonden model moet bestaan in ModelConfigRepository
3. Het gebonden model moet `runtime.enabled = true` zijn

---

## 3. Ports en Adapters

De runtime kernel kent alleen abstracte poorten. Infrastructuurdetails zitten in adapters.

### Portcatalogus

| Port | Interface methoden | Live adapter |
|---|---|---|
| GameStateSource | `poll(context, fps) -> List<GridFrame>` | LiveGameStateSource -> AiPlayFacade -> AiPlayService.readGameState -> UdpStateReceiver.getLatestFrame() -> StateFrameToGameStateConverter |
| CommandSink | `sendCommand(fwd, back, left, right, jump, duck, fire, altFire, dodge, yaw, pitch, moveYaw)` | UdpCommandSender -> 12-byte UDP packet |
| InferencePort | `register()`, `isModelAvailable()`, `predictRaw()`, `refreshModel()` | GenericPredictor -> ONNX Runtime |
| RuntimeClock | `nanoTime()`, `waitUntilNano(targetNs)` | SystemClock -> System.nanoTime + LockSupport |

### Afhankelijkheidsrichting

```
Runtime kernel (BotRuntime, executors, CommandController)
  -> kent alleen: GameStateSource, CommandSink, InferencePort, RuntimeClock
  -> kent NIET: AiPlayFacade, UdpCommandSender, UdpStateReceiver, GenericPredictor

Adapters (LiveGameStateSource, UdpCommandSender, GenericPredictor, SystemClock)
  -> implementeren port interfaces
  -> kennen infrastructuur (UDP DatagramChannel, ONNX, wall-clock)

BotRuntimeFactory
  -> verbindt adapters aan ports
```

### Startup snapshot

Bij startup logt BotRuntime welke adapter achter welke port zit:

```
+-- Port Bindings -------------------------------------------+
|  gamestate   : LiveGameStateSource (UDP frame -> GameState) |
|  command sink: UdpCommandSender @ 127.0.0.1:<udp_port>     |
|  inference   : GenericPredictor                             |
|  clock       : SystemClock                                  |
+-------------------------------------------------------------+
```

---

## 4. Test Architectuur (gepland)

De ports uit §3 zijn met opzet abstract zodat de runtime-kernel met fake adapters getest kan worden zonder UDP, ONNX of wall-clock. Die test-laag — scenario-harness + fake adapters + contract-tests — is **nog niet geïmplementeerd**; er zijn momenteel geen Java-tests in de repo. Het beoogde ontwerp staat in [TODO/test-architecture.md](TODO/test-architecture.md).

---

## 5. Model Promotie

Training produceert candidates. Alleen gepromoveerde candidates worden gesyncet naar runtime servers.

### Candidate lifecycle

```
BUILT -> EVALUATING -> REJECTED
                    -> PROMOTION_READY -> PROMOTED -> SUPERSEDED
                                                    -> ROLLED_BACK
```

### Gate pipeline

```
export_actor_onnx() -> ONNX op disk
  |
  +-- register_candidate() -> status BUILT
  |
  +-- evaluate_and_promote():
  |     +-- Artifact gate: ONNX + .data bestaan en niet leeg
  |     +-- Threshold gate: val_loss <= max_val_loss (uit promotion.json)
  |     +-- Baseline gate: niet slechter dan huidig promoted model
  |
  +-- Als ALLE gates passen:
  |     +-- status -> PROMOTED
  |     +-- active_bindings.json bijgewerkt (vorige -> SUPERSEDED)
  |     +-- sync_onnx_to_servers() -> rsync naar alle machines
  |
  +-- Als een gate faalt:
        +-- status -> REJECTED
        +-- ONNX wordt NIET gesyncet
```

### Configuratie

Per model: `resources/models/{model_key}/promotion.json`

| Veld | Betekenis |
|---|---|
| `enabled` | Gates actief? Bij false: auto-promote |
| `max_val_loss` | Maximum val_loss voor threshold gate |
| `max_regression_vs_baseline` | Maximum verslechtering vs huidig model |

### Rollback

`rollback(role)` herstelt de vorige promoted binding uit het `previous` veld in `active_bindings.json`.

### Observability

| Waar | Wat zichtbaar is |
|---|---|
| Training output | `-> PROMOTED & synced` of `-> REJECTED (not synced)` |
| `active_bindings.json` | Huidig promoted model per rol + vorige voor rollback |
| `candidates/{model_key}/step-{n}.json` | Gate results, status, timestamps |
| Java startup log | Active Model Bindings tabel met step, loss, rollback status |

---

## 6. Control Plane

De control plane formaliseert operationele orchestratie als domeinobjecten met expliciete state transitions.

### Machine Inventory

Leest `resources/config/servers.json` in naar immutable machine-records:

| Machine | Hostname | GPU inst | CPU inst | BC slots | SAC slots | CSV slots | CUDA |
|---|---|---:|---:|---:|---:|---:|---|
| 4090 | desktop-4090.fritz.box | 0 | 0 | 1 | 2 | 4 | Ja |
| 4070 | desktop-4070.fritz.box | 5 | 0 | 1 | 0 | 4 | Ja |
| 3070 | desktop-3070.fritz.box | 2 | 0 | 1 | 0 | 2 | Ja |
| 2070 | desktop-2070.fritz.box | 1 | 0 | 0 | 0 | 0 | Ja |
| P15v | LAPTOP-P15v.fritz.box | 1 | 0 | 0 | 0 | 1 | Ja |

Queryable via `primaryTrainer()`, `trainer()`, `botRunners()`, `byId()`. De primary trainer wordt deterministisch gekozen op hoogste trainer-slot capaciteit; 4090 is de primary.

### State modellen

| Entiteit | Statussen | Doel |
|---|---|---|
| Job | QUEUED -> SCHEDULED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED | Een uitvoerbare stap |
| Workflow | CREATED -> ACTIVE -> WAITING -> COMPLETED / FAILED / ROLLED_BACK | Keten van jobs (train -> eval -> deploy) |
| Deployment | DESIRED -> SYNCING -> ACTIVATING -> ACTIVE / UNHEALTHY / ROLLED_BACK | Desired vs actual per machine |

### Source of truth

ControlPlaneState combineert workflows + jobs + deployments in een JSON-persistente state store, leesbaar door Java startup en bash scripts.

### Relatie met bash scripts

De bash scripts (`deploy.sh`, `train-bc.sh`, `status.sh`) zijn de uitvoerlaag. De control plane modellen zijn de architectuurlaag erboven die formaliseren wat de scripts doen. Scripts lezen model keys uit `roles.json` via `resolve_role_model_keys()` in `common.sh`.

---

## Datastroom

### Live runtime

```
UT99 server (ucc-bin) -- RLUdpStateSender push @ 60Hz
  | UDP (binary, multi-packet, tag 0xBB)
  v
UdpStateReceiver (reader thread, reassembly -> AtomicReference<StateFrame>)
  |
  v
AiPlayService.readGameState() -> StateFrameToGameStateConverter
  |
  v
LiveGameStateSource.poll() (producer-thread cadance)
  |
  v
Producer thread -> TrainingFeatureService.enrich() -> GameStateBus.publish("live")
  |
  v
Consumer threads lezen bus -> tick executors:
  +-- MissionPlayExecutor (5Hz) -> mission annotatie + TacticalIntentBus
  +-- RLPawnExecutor (30Hz) -> InferencePort.predictRaw()
  |     -> movement + viewrotation + fire/altFire + target_index
  |     -> PolicyIntentBus + ViewTurnIntentBus + ShootIntentBus
  v
CommandController (50Hz) leest PolicyIntentBus + ViewTurnIntentBus
  -> movement dwell + yaw step + pitch tracking
  -> CommandSink.sendCommand()
  | UDP (binary 12-byte packet, tag 0xAA)
  v
RLUdpCommandReceiver.Tick() -> RLBots[botIdx]
```

### Training -> live promotie

```
BC trainer (Python op 4090)
  -> best val_loss checkpoint
  -> export ONNX naar disk
  -> register_candidate()
  -> evaluate_and_promote()
       +-- gates passen -> PROMOTED -> sync_onnx_to_servers()
       +-- gate faalt -> REJECTED -> niet syncen
  | rsync
  v
ModelWatcher (Java op alle servers)
  -> detecteert file change (5s polling, 2s stability wait)
  -> GenericPredictor.refreshModel()
  -> ONNX session hot-swap (write lock)
  -> Bot gebruikt nieuw model bij volgende predictRaw()
```
