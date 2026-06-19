# Test Architectuur (gepland — nog niet geïmplementeerd)

> **Status:** ontwerp. Er zijn momenteel **geen** Java-tests in de repo — geen scenario-harness, geen fake adapters. Dit document beschrijft de *beoogde* test-laag die de ports/adapters uit [platform-architecture.md §3](../platform-architecture.md) mogelijk maken. Wordt dit gebouwd, verplaats het dan (geactualiseerd, met echte tellingen) terug naar `platform-architecture.md`.

De runtime-kernel kent alleen abstracte ports (`GameStateSource`, `CommandSink`, `InferencePort`, `RuntimeClock`). Dat is precies wat een scenario-harness in staat zou stellen dezelfde kernel als productie te draaien, maar met fake adapters in plaats van UDP, ONNX en wall-clock. Het is de architecturale reden dat die ports abstract zijn.

## Beoogde fake adapters

| Test adapter | Vervangt | Doel |
|---|---|---|
| DeterministicClock | SystemClock | Bestuurbare tijd, `advanceTo()`, `releaseAll()` |
| FakeGameStateSource | LiveGameStateSource | Pre-enqueued frames, poll counter |
| StubInferencePort | GenericPredictor | Configureerbare model availability + predictions |
| CaptureCommandSink | UdpCommandSender | Vangt commands als records voor assertie |

## Beoogde harness-opbouw

```
ScenarioHarness.create("test-session")
  +-- Fake adapters aangemaakt
  +-- InstanceServices met fake ports (geen PlayExecutionService)
  +-- BotRuntime met InstanceContext
  +-- Minimale BehaviorTree (alleen voor blackboard)
  +-- runtime.populateBlackboard()
  +-- Klaar voor startRuntime() / stopRuntime()
```

## Beoogde contract-tests

| Categorie | Wat ze zouden bewijzen |
|---|---|
| Assembly | Complete runtime bouwt + start; missing port faalt fail-fast; session ID behouden |
| Lifecycle | CREATED→RUNNING→STOPPED, running flag, double start/stop idempotent, producer pollt |
| Isolation | Gescheiden buses, sinks, lifecycles, session IDs; stop-isolatie — geen state-leakage tussen instances |
