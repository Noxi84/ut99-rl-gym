# DeltaGate -- in-game performance promotion gate

De DeltaGate is een in-game performance gate die promotion-beslissingen baseert op werkelijke per-bot KPI's in matches (RL vs UT99 baseline-bots), niet op SAC-return. SAC-return is een proxy die door de bot kan worden geexploiteerd. DeltaGate gebruikt het echte spelresultaat als criterium en laat de gradient onaangetast -- reward-shaping levert de gradient, DeltaGate beslist welk model gepromoveerd wordt.

De productie-gate is `DualKPIDeltaGate` voor het joint `rl_pawn` model. Drie KPI's moeten alle drie passen voor PROMOTE (AND-promotie); een onder rollback-margin triggert ROLLBACK (OR-rollback).

| KPI | Formule |
|---|---|
| `combat_score` | `frags + (damage_dealt - 0.3 * damage_taken) / 80` (kill-equivalents/min) |
| `shots_on_target_rate` | `shotsOnTarget / shots` (ratio) |
| `flag_score` | CTF objective gain per minuut |

Gate-config in `resources/models/rl_pawn/export_gate.json` (`delta_gate` + `probe` subsecties). Weights + AdamW optimizer state worden samen gebundeld bij PROMOTE en samen gerestored bij ROLLBACK.

---

## Concept

```
ratio = KPI(RL bots) / KPI(baseline)
```

per KPI, over een match-aligned window. Per RL-bot is identieke policy actief (huidige ONNX); UT99 native-bots vormen een vaste skill-baseline. De ratio t.o.v. de verified baseline = signaal of de policy daadwerkelijk beter speelt.

```
SAC loop (per gen):
  1. Train candidate (SAC fine-tuning over replay buffer)
  2. Probe: fire-rate floor check (collapse detection)
  3. Probe pass -> DEPLOY candidate (active ONNX naar alle bots)
  4. Probe fail -> REVERT naar delta-baseline

Match-aligned (elke `matches_per_eval_cycle` afgeronde matches):
  5. Sample PLAYER_SCORES logs van alle servers
  6. Compute per-KPI ratio (current / baseline) over het window
  7. Decide: PROMOTE / ROLLBACK / NEUTRAL / INSUFFICIENT (AND over alle 3 KPI's)
```

---

## RL-bot detectie

Stock UT99-bots en RL-bots delen dezelfde `bIsABot=true` flag. DeltaGate identificeert RL-bots via:

| Pad | Markering |
|---|---|
| Binary UDP (state sender) | bit 128 in `actionFlags`, gezet wanneer bot RL-controlled is |
| JSON debug endpoint | `bIsRLControlled` field |

---

## PlayerScoresLogger

Gestart vanuit de bot-runtime. Een emit per 60s per bot, snapshot van alle 6 spelers in de match:

```
PLAYER_SCORES t=<unix_ms> self=<name>:<team>:<score>:<deaths>:<frags>:<flagsT>:<flagsC>:<flagsR>:<shots>:<shotsOn>:<dmgDealt>:<dmgTaken>:<rl>
              tm0=<...> tm1=<...> en0=<...> en1=<...> en2=<...>
```

13 colon-velden per speler:

| Field | Beschrijving |
|---|---|
| `t` | Unix milliseconds |
| `name:team:score:deaths` | Standaard player-state |
| `frags:flagsT:flagsC:flagsR:shots:shotsOn` | KPI-counters |
| `dmgDealt:dmgTaken` | Damage cumulatieven |
| `rl` | 0 of 1 (RL-controlled flag) |

Counter-velden zijn monotonisch oplopend per match. De parser berekent rolling-window deltas.

Meerdere RL-bots in dezelfde match loggen redundant (zelfde 6-player snapshot vanuit verschillend self-perspectief). De parser dedupliceerd per `(host:instance_dir, 30s bucket)` en houdt de snapshot met de meeste spelers.

---

## Parser

`compute_delta(window_minutes, kpi)` per eval:

1. **SSH gather**: parallel naar alle hosts uit `servers.json`. Grep `PLAYER_SCORES` uit logs modified within `window + 2 min` slack.
2. **Tag per match**: `match_id = "<host>:<instance-N>"` zodat instances op verschillende machines niet worden gemerged.
3. **Dedupe per match per 30s bucket**: behoud snapshot met meeste spelers (>=4 vereist).
4. **Per-player tracking**: voor elke speler, in elke match, track first en last appearance binnen het window. UT99-bot pool roteert tijdens matches, dus per-player observation span lost dat op.
5. **Score-gain per minuut**: `gain = max(0, last_score - first_score)`. `gain / minutes` per speler. Voor ratio-KPI's (`shots_on_target_rate`) wordt het verschil als rolling-window ratio berekend.
6. **Aggregate**: `rl_avg = mean(rl_gains_per_min)`, `ut99_avg = mean(ut99_gains_per_min)`, `delta = rl_avg - ut99_avg`.

Resultaat: `(delta, rl_avg_gain, ut99_avg_gain, rl_n, ut99_n, minutes_observed, matches)`.

CLI: `python -m train.rl.shared.player_scores_eval [window_minutes]` print huidige delta direct.

---

## DualKPIDeltaGate decision logic (ratio-based)

`DualKPIDeltaGate` (`train/rl/shared/delta_gate.py`) is de enige gate-implementatie voor het joint `rl_pawn` model. Het is één stateful object dat per eval-cyclus alle 3 KPI's tegelijk evalueert via `evaluate()`; het houdt per KPI een eigen promote-/rollback-streak bij (geen wrapping van losse single-KPI gates). Elke KPI wordt uitgedrukt als `ratio = current / baseline` t.o.v. de verified baseline.

### Config parameters

Uit `resources/models/rl_pawn/export_gate.json` (strict load — alle keys vereist, geen fallbacks):

| Parameter | Waarde | Beschrijving |
|---|---:|---|
| `promote_combat_score_min_ratio` | 0.85 | combat_score-ratio drempel voor PROMOTE |
| `promote_aim_min_ratio` | 0.85 | shots_on_target_rate-ratio drempel voor PROMOTE |
| `promote_movement_min_ratio` | 0.80 | flag_score-ratio drempel voor PROMOTE |
| `promote_window_cycles` | 1 | Opeenvolgende cycli met alle ratios boven drempel |
| `rollback_combat_score_max_ratio` | 0.70 | combat_score-ratio onder dit telt als violation |
| `rollback_aim_max_ratio` | 0.50 | shots_on_target_rate-ratio onder dit telt als violation |
| `rollback_movement_max_ratio` | 0.40 | flag_score-ratio onder dit telt als violation |
| `rollback_window_cycles` | 2 | Opeenvolgende violation-cycli (per KPI) voor ROLLBACK |
| `matches_per_eval_cycle` | 5 | MATCH_ENDED-count over alle servers per eval-cyclus |
| `min_steps_before_eval` | 0 | Train-steps voor de gate begint te evalueren |
| `consecutive_rollback_adam_wipe_threshold` | 3 | Opeenvolgende rollbacks voordat AdamW state gewist wordt |

De cadence is **match-aligned**: de trigger firet zodra de MATCH_ENDED-count over alle servers `matches_per_eval_cycle` haalt sinds de laatste eval — geen wall-clock timer. ServerTravel-overhead (~90s/match) zit daarmee impliciet meegerekend.

### Beslissingslogica

Per cyclus berekent `evaluate()` de drie ratios en bepaalt:

```
  combat_ratio   = current_combat_score   / baseline_combat
  aim_ratio      = current_aim_rate        / baseline_aim
  movement_ratio = current_movement_score  / baseline_movement
         |
         v
  baseline None of 0 voor enige KPI?
  |                                  |
  ja                                nee
  |                                  |
  v                                  v
  INSUFFICIENT      promote_ready = (combat_ratio >= promote_combat_score_min_ratio
  (geen actie)                       AND aim_ratio >= promote_aim_min_ratio
                                     AND movement_ratio >= promote_movement_min_ratio)
                                          |
                    +---------------------+---------------------+
                    | promote_ready                             | niet promote_ready
                    v                                           v
        promote_streak += 1                       promote_streak = 0
        rollback streaks -> 0                     per-KPI rollback_streak += 1
                    |                              als die KPI < zijn rollback_max_ratio
                    v                                           |
        promote_streak >= promote_window_cycles?               v
        |                          |              een rollback_streak >= rollback_window_cycles?
        ja                        nee             |                              |
        |                          |              ja                            nee
        v                          v              v                              v
     PROMOTE                   NEUTRAL         ROLLBACK                       NEUTRAL
     (streak reset)                            (streaks reset,
                                                consecutive_rollback_count += 1)
```

PROMOTE is een AND-conditie over alle 3 ratios; ROLLBACK is een OR-conditie waarbij elke KPI zijn eigen streak heeft, zodat cross-skill regressie onafhankelijk gedetecteerd wordt. Bij een promote-ready cyclus worden alle rollback-streaks gereset (geen regressie meer, ook al zat één KPI eerder onder ratio).

### Waarom AND-promote + OR-rollback

combat_score alleen kan stijgen door volume-fire op nabije targets (hoge dmg-output zonder aim-precision) -- `shots_on_target_rate` (aim) voorkomt promotie van high-volume-low-precision regressies. `flag_score` (movement) voorkomt promotie als CTF-objectieven verslechteren. OR-rollback is bewust: per-skill regressie zichtbaar in één KPI detecteert cross-dim gradient-noise voordat een gemiddelde KPI het maskeert.

### Per-weapon modus

Wanneer per-weapon baselines beschikbaar zijn, kiest de training_loop `evaluate_per_weapon()`: dezelfde streak-structuur, maar de ratios worden per wapen berekend en gereduceerd via `min()` (promote-check) / `any()` (rollback-check) over de actieve wapens. Het wapen met de slechtste ratio bepaalt elke beslissing — een regressie op één wapen blokkeert promotie ook als andere wapens boven baseline blijven. Wapens zonder voldoende samples in zowel baseline als current worden overgeslagen.

---

## Baseline-beheer

Per-KPI baselines worden onafhankelijk bijgehouden en bij PROMOTE bijgewerkt via `update_baselines()` (dat alle streaks reset). AdamW state wordt gedeeld bewaard bij PROMOTE en samen gerestored bij ROLLBACK ongeacht welke KPI de rollback triggerde. Een PROMOTE reset `consecutive_rollback_count` naar 0 (doorbreekt de drift-ratchet).

---

## Persistence

State op `{model_output_dir}/rl_pawn_sac_delta_baseline.{pt, onnx, json}`:

| Bestand | Inhoud |
|---|---|
| `*_delta_baseline.pt` | Actor weights + AdamW optimizer state -- voor restore bij rollback |
| `*_delta_baseline.onnx` (+ `.onnx.data`) | Geexporteerd ONNX -- gedeployed naar alle bots bij rollback |
| `*_delta_baseline.json` | Baseline state: delta, timestamps, rl/ut99 averages, promote/rollback counts, `consecutive_rollback_count`, `kpi` |

Bij trainer-restart wordt baseline herladen.

### In-flight eval-anker reset bij clean-logs deploy

Bij `clean-logs: true` deploy plaatst het deploy-script een sentinel `_reset_inflight_clock.flag` op elke SAC-trainer host. De bootstrap consumeert die: als er een in-flight candidate geladen wordt, reset het `delta_inflight_started_at` naar het huidige tijdstip en verwijdert de flag. Match-end count en KPI-window beginnen vanaf het restart-moment.

Reden: clean-logs wist logbestanden op alle servers -- de bron waaruit PLAYER_SCORES en MATCH_ENDED regels worden gelezen. Zonder reset zou de window ontbrekende data lezen en ruis-vrije INSUFFICIENT cycli opleveren tot de logs vol zijn.

### Optimizer-state pairing

Bij PROMOTE worden actor weights en AdamW optimizer state (momentum + variance buffers) samen gebundeld. Bij ROLLBACK worden beide samen hersteld, zodat AdamW state altijd matched bij de bijbehorende verified weights.

Zonder deze koppeling zou rollback alleen weights herstellen -- de Adam momentum bleef dan gericht op de afgekeurde drift, waardoor de volgende update onmiddellijk dezelfde richting opgaat (rollback ratchet).

### Adam-wipe escalation

Bij `consecutive_rollback_count >= consecutive_rollback_adam_wipe_threshold` (default 3) wordt de AdamW state volledig gewist na de baseline-restore. De counter wordt naar 0 gereset.

Drie opeenvolgende rollbacks zonder PROMOTE betekent dat de baseline-bundled momentum tegen de policy werkt. Een schone AdamW-start dwingt de trainer een andere gradient-richting te ontdekken.

| State | Counter gedrag |
|---|---|
| PROMOTE / INITIAL | reset naar 0 |
| ROLLBACK (voor wipe-threshold) | +1 |
| ROLLBACK (op wipe-threshold) | volledige Adam-wipe, reset naar 0 |
| NEUTRAL | onveranderd |
| INSUFFICIENT | onveranderd |

---

## SAC loop integratie

De SAC training loop instantieert DualKPIDeltaGate per training-cycle:

- Per gen: train candidate, fire-rate probe, DEPLOY of REVERT.
- Elke `matches_per_eval_cycle` afgeronde matches (match-aligned, via `player_scores_eval.count_match_ends_since`): `evaluate()` voor alle 3 KPI's met AND-promotie / OR-rollback.

Probe gate (apart van DeltaGate, runt per gen):
- Fire-rate floor -- minimum fire_rate per dimensie.
- Check: BOTH primary + alt fire dimensies onder floor triggert revert naar baseline (laat de trainer alt-only of primary-only exploreren zonder rollback).

---

## Telemetry

De gate logt per cyclus één `DUAL_KPI_DELTA_{decision}`-regel met de drie ratios en de streak-tellers (`evaluate()` in `delta_gate.py`):

```
DUAL_KPI_DELTA_PROMOTE: combat_ratio=+X.XXX aim_ratio=+X.XXX movement_ratio=+X.XXX
    promote_streak=N/1 rb_combat=N/2 rb_aim=N/2 rb_movement=N/2 (ALL ratios >= thresholds ...)
DUAL_KPI_DELTA_ROLLBACK: combat_ratio=+X.XXX aim_ratio=+X.XXX movement_ratio=+X.XXX
    promote_streak=0/1 rb_combat=N/2 ... (combat_score ratio X.XXX < 0.70 for N cycles)
DUAL_KPI_DELTA_NEUTRAL: combat_ratio=+X.XXX aim_ratio=+X.XXX movement_ratio=+X.XXX ... (within margins)
DUAL_KPI_DELTA_INSUFFICIENT: baseline_combat=... baseline_aim=... baseline_movement=... (None / zero)
```

Bij ROLLBACK volgen de baseline-restore log-regels:

```
DELTA_ROLLBACK_RESTORED: weights + Adam state from baseline (consecutive=N/3)
DELTA_ROLLBACK_ADAM_WIPE: 3x consecutive rollback -- Adam state cleared (counter reset)
```

In per-weapon modus volgt onder de aggregate-regel per wapen een `DUAL_KPI_PER_WEAPON[<weapon>]`-detailregel (zelfde `combat`/`aim`/`movement` ratio-format).

SAC-loop log:

```
DEPLOYED: gen=N ret=X.XXXX best_seen=X.XXXX (N bytes) -- DeltaGate validates next.
PROBE_FAIL_REVERT: fire_rate=[X.XXX, X.XXX] below floor 0.001 (BOTH dims)
```

---

## Champion ONNX delivery

Het model wordt naar bots geleverd via twee parallelle paden, beide met hot-reload (geen bot-restart nodig):

| Bot snapshot spec | Bron-file | Trigger |
|---|---|---|
| `"current"` | `sessions/trainingmodel/rl_pawn.onnx` | file mtime change (elke SAC export of rollback) |
| `"rl_pawn/newest"` | `sessions/models/champions/rl_pawn/NNNN/rl_pawn.onnx` | `bundles.json` pool[0] counter change (PROMOTE-aligned) |
| `"rl_pawn/<counter>"` | idem, vast counter | nooit (pinned, voor baseline/validation bots) |

Bij een `newest` change:

1. Resolve nieuwe snapshot -- hard-valideer feature- en architectuur-fingerprints; mismatch resulteert in log + skip (retry next poll).
2. Stability check op `.onnx`, `.onnx.data` en `bundles.json` zodat half-rsynced bestanden geen crash veroorzaken.
3. Voor elke geregistreerde predictor: atomic spec swap + verse ONNX-sessie op het nieuwe pad.
4. Na alle swaps: oude sessie sluiten via write-lock drain van in-flight predicts.

Bij partial failure (een predictor faalt) blijft de oude sessie alive; next poll retryt (idempotent).

Telemetry:

```
CHAMPION_NEWEST_WATCHER registered predictor for rl_pawn -> .../champions/rl_pawn/0042/rl_pawn.onnx
CHAMPION_NEWEST_WATCHER swapping rl_pawn counter 42 -> 43 across 5 predictor(s)
CHAMPION_NEWEST_WATCHER swap done for rl_pawn -> counter 43 (swapped=5/5)
```
