# Trainingspijplijn & Modeloverzicht

Bron van waarheid voor hoe het joint `rl_pawn` model wordt getraind en uitgerold.

---

## Joint model

Een LSTM-gebaseerd policy-netwerk dat alle low-level acties output:

| Output-groep | Dimensies | Beschrijving |
|---|---|---|
| Movement | 6 | `moveDir_sin`, `moveDir_cos`, `dodge`, `bJump`, `bDuck`, `bIdle` |
| Viewrotation | 2 | `yawDelta_norm`, `pitchDelta_norm` |
| Fire | 2 | `bFire`, `bAltFire` |
| Aux head | 5 | `target_index` (5-slot categorical) |

Eerst BC pre-training op menselijke demonstraties, daarna SAC fine-tuning met live experience.

| Eigenschap | Waarde |
|---|---|
| Model key | `rl_pawn` |
| Rol | `pawn_policy` |
| Architectuur | LSTM 640h x 2 lagen + PlayerEncoder + TargetHead |
| Inference Hz | 30 |
| Fine-tuning | SAC |

---

## Trainingsstroom

```
  Menselijke gameplay opnemen (RecordLauncher)
          |
          v
  ZIP opnames naar json-recording-sessions/rl_pawn/
          |
          v
  prepare-csv.sh -> gedistribueerde CSV-generatie
          |
          v
  BC pre-training
          |
          v
  Candidate registratie -> gates -> promotie
          |
          v
  ONNX sync naar alle servers (ModelSync)
          |
          v
  Runtime gebruikt nieuw model (ModelWatcher)
          |
          v
  Continue verbetering via SAC fine-tuning
  met live ervaring uit rl-replay-buffer/rl_pawn/
```

---

## Netwerk-architectuur

Het joint model gebruikt een LSTM-netwerk met permutation-invariant PlayerEncoder voor enemy- en teammate-slots.

```
Flat input [B, T, totalDim]
    |
    +-- self/global features (direct)
    +-- teammate slots -> shared PlayerEncoder -> mean+max pool over slots
    +-- enemy slots    -> shared PlayerEncoder -> mean+max pool over slots
    |
   concat per timestep
    |
   LSTM(input_size, hidden_size=640, num_layers=2, dropout=0.15)
    |
   out[:, -1, :]          <-- laatste frame hidden state
    |
   LayerNorm -> GELU
    |
   Linear(hidden -> hidden/2) -> GELU
    |
   Linear(hidden/2 -> output_size)
```

| Parameter | Waarde |
|---|---:|
| Hidden size | 640 |
| LSTM-lagen | 2 |
| Dropout | 0.15 |
| PlayerEncoder hidden / embed | 64 / 64 |
| Output | 10 actions + 5-dim TargetHead |

Door per-slot PlayerEncoder + mean+max-pooling is het netwerk **permutation-invariant** over enemy- en teammate-slots -- slot-volgorde aan de input verandert de output niet. Feature-counts staan in `resources/models/rl_pawn/features.json`; zie [docs/features/](../features/index.md).

---

## Feature-groepen en temporele vensters

Features komen in groepen met een eigen temporeel venster (first/last frames). Frames buiten het venster worden gemaskeerd naar 0. Zie [feature-groups-architecture.md](../features/feature-groups-architecture.md) voor detail.

---

## BC-trainingsdetails

Multi-head loss:

| Component | Loss |
|---|---|
| Movement (sin/cos/dodge/jump/duck/idle) | Smooth-L1 op `tanh(logits)` voor sin/cos (velocity-masked) + BCE met `pos_weight` voor binary outputs |
| Yaw/Pitch (`yawDelta_norm`, `pitchDelta_norm`) | Gaussian-NLL |
| Fire (`bFire`, `bAltFire`) | BCE per output |
| Aux `target_index` | Confidence-weighted cross-entropy |

Gewichten per head (`w_movement`, `w_vr`, `w_fire`, `w_target`) in `resources/models/rl_pawn/bc.json:multi_head_loss`. Label smoothing `0.02`. Augmentatie: yaw-rotaties (movement) + mirror (yaw + slot-symmetry waar veilig).

Target `target_index` via post-hoc kill-attribution lookahead + closest-visible fallback. Het BC-label is een (slot, confidence) tuple; confidence weegt het CE-loss.

Zie [training-parameters.md](../rewards/training-parameters.md) voor alle hyperparameter-waarden.

---

## Ervaringsverzameling (SAC)

De experience collector schrijft `(state, action, reward, next_state, done)` plus per-skill reward decomp (movement/view/pitch/fire/altFire/team_assist + residual) en aux target supervision (label + confidence) naar `.npz`-bestanden in `<sessions>/rl-replay-buffer/rl_pawn/`.

`sync_replay.sh` synchroniseert ervaring van alle servers naar de toegewezen SAC-trainer elke 30 s met `--remove-source-files`.

SAC laadt alle aanwezige `.npz`-bestanden in een circular buffer (`replay_buffer_max_transitions`, standaard 500000). Nieuwe experience wordt continu toegevoegd; oudste transitions worden overschreven bij overflow.

---

## Rewards

Rewards worden berekend in de runtime en opgeslagen in de `.npz`-bestanden, samen met per-skill decompositie (`reward_movement`, `reward_view`, `reward_pitch`, `reward_fire`, `reward_altFire`, `reward_team_assist` + residual). Wijzigingen in `rewards.json` of `sac.json` vereisen deploy + herstart van bots op alle servers. Zie:

- [reward-architecture.md](../rewards/reward-architecture.md)
- [sparse-events.md](../rewards/sparse-events.md)

---

## Model-promotie

### BC candidates -- promotion gates

Training produceert candidates. Alleen gepromoveerde candidates worden gesyncet naar runtime servers via ModelSync. Configuratie in `resources/models/rl_pawn/promotion.json`.

| Gate | Controle |
|---|---|
| Artifact gate | ONNX + `.data`-bestand bestaan en zijn niet leeg |
| Threshold gate | `val_loss <= max_val_loss` |
| Baseline gate | Niet slechter dan huidig gepromoveerd model (`max_regression_vs_baseline`) |

Bij `promotion.enabled=false`: auto-promote.

### SAC fine-tuning -- DualKPIDeltaGate

De SAC training loop runt een DualKPIDeltaGate (zie [delta-gate.md](delta-gate.md)) die drie KPI's toetst tegen baselines uit `resources/models/rl_pawn/baseline.json`:

| KPI | Beschrijving |
|---|---|
| `combat_score` | frags + damage-balance |
| `shots_on_target_rate` | `shotsOnTarget / shots` ratio |
| `flag_score` | CTF objective gain |

PROMOTE vereist dat alle 3 KPI's hun threshold over `promote_window_cycles` opeenvolgende cycli halen. ROLLBACK triggert wanneer een van de KPI's onder de rollback-margin zakt.

Parallel runt een per-gen probe-gate (saturation / spread / fire-rate floor) als snelle sanity check voor de DeltaGate-evaluatie.

---

## Gerelateerde documenten

| Onderwerp | Document |
|---|---|
| Feature-pipeline | [features/index.md](../features/index.md) |
| Augmentatie | [augmentation/training-augmentation-architecture.md](../augmentation/training-augmentation-architecture.md) |
| CSV-generatie | [training/training-csv-writer.md](training-csv-writer.md) |
| Trainer-slot-toewijzing | [training/trainer-slots-architecture.md](trainer-slots-architecture.md) |
| DualKPI DeltaGate | [training/delta-gate.md](delta-gate.md) |
| Modelconfig | [models/model-config-architecture.md](../models/model-config-architecture.md) |
