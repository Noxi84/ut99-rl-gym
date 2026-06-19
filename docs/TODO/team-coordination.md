# Team Coordination via Centralized Training, Decentralized Execution (CTDE)

Architectuur-notitie. Beschrijft het CTDE-pad dat op `rl_pawn` is uitgerold
plus de open ontwerphorizon (Fase 3+). Fase 0 t/m 2.5 zijn LIVE — zie
[team-coordination-rollout.md](team-coordination-rollout.md) voor de
implementatie-recap.

---

## Probleemstelling (historisch, opgelost vanaf Fase 2.5)

De bot heeft sinds 5v5 een rijke teammate-stack (zie
[`TeammateSlotFeatureComponent`](../../java-aiplay/src/main/java/aiplay/scanners/feature/resolver/teammate/TeammateSlotFeatureComponent.java),
[`RoleContextFeatureComponent`](../../java-aiplay/src/main/java/aiplay/scanners/feature/resolver/role/RoleContextFeatureComponent.java)),
inclusief `teammateCarrier_proximity_norm` en alle bestaande
`team_captured` / `team_returned` reward-asymmetrieën in
[`rl_pawn/rewards.json`](../../resources/models/rl_pawn/rewards.json).

Status nu (na Fase 2.5):

| Component | Status |
|---|---|
| Teammate observations in input tensor | LIVE |
| Reward-asymmetrie per rol (Attack/Cover/Defend) | LIVE — actief gerouteerd via rewardgroups 0-3 |
| Productie `gameplay.json` rolverdeling | LIVE — 2 Att + 1 Cov + 1 Def per team |
| SAC critic joint state `Q(s_self, s_teammate_closest2, a_self)` | LIVE — `ctde_mode: "closest_two"`, `teammate_state_dim: 40` |
| `team_assist` critic head + reward decomp (7 heads totaal) | LIVE — `reward_decomp_keys` incl. `team_assist` (+ `residual` als 7e head sinds 2026-05-30), 5 sub-componenten |
| Counterfactual credit assignment (COMA difference rewards) | NIET LIVE — design horizon, niet gepland |

De diagnose uit het ontwerp staat overeind: zonder team-context in de critic
leert de actor egoïstische policies. Vanaf Fase 2.5 ziet de critic per-tick
de closest-2 teammate slice (carrier-first ordering) + een aparte
gradient-channel voor team-utility via de 6e head — teamwork is daarmee
expliciet gemodelleerd in plaats van indirect via shaped rewards.

## CTDE in één paragraaf

Centralized Training, Decentralized Execution (Lowe et al. 2017, MADDPG;
Pu et al. 2020, MASAC): tijdens training krijgt de critic *meer* informatie
dan de actor — typisch een joint snapshot van alle agents' observaties en
acties. Bij inference is alleen de actor live; die ziet enkel zijn eigen
state. Het effect is dat de Bellman-target voor `Q(s_self, a_self)` rekening
houdt met team-utility, en de policy-gradient zo richting beleid trekt
dat het *team* helpt, niet alleen de eigen rol.

In onze setting is de scheiding niet 100% strikt: onze actor ziet via
`teammate{N}_*` features al een deel van teammate-observaties. CTDE blijft
echter een echte upgrade omdat de critic dan ook teammate-acties en
team-progressie ziet die de actor *niet* heeft.

## Toepassing op onze stack

### Scope: uitsluitend `rl_pawn`

CTDE wordt toegepast op `rl_pawn` (het joint movement + VR + shooting +
target-aux model dat sinds 2026-05-12 productie is).

Waarom deze scope pragmatisch is voor onze stack:

| Argument | Toelichting |
|---|---|
| `MultiHeadSACCritic` is hier al productie | 5-head Q-decomp live sinds 2026-05-14 (Movement_flag + 4 andere). Een 6e `team_assist` head is een natuurlijke uitbreiding, geen architecturele switch. |
| Joint actor ziet al alle teammate-features | `rl_pawn/features.json` bevat teammate slots 0–3 (isAlive, relSin/relCos, distances, hasFlag, pitchBearing, health). De infrastructure voor team-context staat al in de input tensor. |
| Joint mode heeft al safety nets | Probe-hard-fail, dual-KPI gate, multi-head critic pre-wired — extra critic-experimenten beschermd door bestaande gates. |

### Concreet ontwerp

```
Decentralized execution (runtime, 60 Hz):
    actor π(a | s_self) — ongewijzigd
    s_self bevat teammate{N}_* features uit eigen perceptie

Centralized training (Python SAC step):
    critic Q(s_self, s_teammates_joint, a_self) → Q-waarde
    Bellman target: r + γ · min(Q1', Q2')(s'_self, s'_teammates_joint, π(s'_self))
```

`s_teammates_joint` is een **slice** van de gevectoriseerde teammate state
die op dezelfde tick geldt. Drie levels van rijkdom om uit te kiezen:

| Level | Wat de critic extra ziet | Cost |
|---|---|---|
| L1: alleen self+team-aggregaten | `team_captured_progress`, `team_dropped_count`, mean health, mean depth — agnostisch voor agent-identity | Klein. Geen state-sync nodig |
| L2: self + closest-1 teammate's *eigen* obs slice | Gelabelde joint state met teammate's eigen perspectief | Medium. Vereist tick-aligned plumbing |
| L3: self + alle teammates' obs én acties | Volledige MASAC | Groot. Cross-machine sync nodig |

Aanbeveling: **start met L1 of L2**. L1 vereist alleen nieuwe aggregatie-features
in Java (geen sync), L2 vereist tick-id in NPZ files. L3 blijft horizon.

### Het synchronisatie-probleem

Onze huidige replay-buffer is bewust anoniem (zie
[`replay_buffer.py:23`](../../train/rl/shared/sac_core/replay_buffer.py)) —
transities zijn `(state, action, reward, next_state, done)` zonder
episode_id, team_id, tick of bot_id. NPZ-files worden per-bot geflusht met
namen als `batch_<machineId>_<flushId>_<ts>.npz`. Geen mechanisme om twee
ticks van twee bots op dezelfde matchtijd te correleren.

Voor L1 (team-aggregaten) is dit geen probleem: de aggregaten worden in
Java per-tick samengesteld vanuit `GameStateDto.teammates[]` en als gewone
features in `s_self` opgenomen. Geen Python-side sync.

Voor L2 (closest-1 teammate's obs) zijn er twee opties:

- **A. Self-perceived teammate state** — de bot logt teammate's *waargenomen* state (positie/health/hasFlag) zoals hij die ziet. Geen sync nodig, maar de critic ziet teammate's wereld door bot's eigen perceptie. Imperfect maar pragmatisch.
- **B. True teammate state via tick-id** — beide bots taggen NPZ-rows met `(match_id, tick)`; Python `ingest_npz_files` joint matched rows in een aux-array. Vereist NPZ-schema-uitbreiding en een team-aware ingest-fase.

Begin met **A**. Het loopt door dezelfde data-pijp als nu, en je hebt het
binnen één SAC-iteratie te testen.

### Hergebruik van bestaande 5-head Q-decomp

`rl_pawn` draait nu met `MultiHeadSACCritic` op 5 heads: de heads
decomponeren de reward over de bestaande skill-componenten
(Movement_flag + 4 andere). CTDE voegt daar één extra head aan toe:

```
huidige head_keys = [<5 bestaande skill-heads, incl. Movement_flag>]
nieuwe set        = huidige + ["team_assist"]

reward_decomp uitbreiding:
    team_assist: r_team_captured + r_team_returned + r_flag_carrier_kill
                 + r_teammate_alive_bonus  (nieuwe shaping uit team-aggregaten)
```

De bestaande 5 heads consumeren ongewijzigd hun reward-slices uit
`compute_critic_loss(critic_mode='multi_head', ...)`. De nieuwe `team_assist`
head consumeert *uitsluitend* team-events; geen overlap met self-rewards.

Belangrijk: alle heads zien dezelfde joint state input (deze CTDE-stap maakt
er 6; later kwam `residual` er als 7e bij — zie [team-coordination-rollout.md](team-coordination-rollout.md) §2.1). Dat is de hele
CTDE-kern. De 5 bestaande heads krijgen daarmee **gratis** team-context in
hun Bellman-target — Movement_flag kan bv. impliciet leren dat
flag-progress conditioneel is op teammate-positie.

COMA-stijl difference rewards (`r - E_a[Q(s, a_-i, a')]`) zijn een latere
refinement bovenop deze structuur. Eerste stap is alleen: joint state in
alle heads, plus één team-assist head.

### Variabele teammates (1–4, dood/levend)

Onze 5v5 bots hebben 4 teammate-slots, waarvan 0–4 in leven kunnen zijn.
De `teammate{N}_isAlive` features bestaan al; de joint-state slice in de
critic moet die als mask gebruiken — net zoals `TacticalIntent` al
`carrierLineProgressNorm = -1.0` als sentinel gebruikt voor "geen
constraint". Geen extra dynamiek nodig.

## Implementatie-impact (high level)

Geen code in deze notitie — alleen de bestanden die geraakt worden.

### Java-kant — minimaal voor L1

| Bestand | Wijziging |
|---|---|
| `scanners/feature/resolver/team/TeamAggregateFeatureComponent.java` (nieuw) | `team_meanHealth_norm`, `team_meanDepth_norm`, `team_captured_progress_norm`, `team_dropped_count_norm`, `team_alive_count_norm` |
| `resources/models/rl_pawn/features.json` | Nieuwe `last_frames:1` groep met die 5 features |
| `resources/models/rl_pawn/rewards.json` | Nieuwe `team_assist` reward-component voor de extra head |
| Niets aan `PerModelExperienceRecorder` of NPZ-schema | Features zitten al in `s_self`; critic ziet ze automatisch |

Decoupled `rl_pawn` / `rl_pawn` / `rl_pawn` features blijven
**ongewijzigd** — geen risico op cross-contamination van features die andere
modellen interpreteren.

### Java-kant — voor L2 (variant A, self-perceived)

| Bestand | Wijziging |
|---|---|
| `rl/PerModelExperienceRecorder.java` | Per-model gated: alleen voor `rl_pawn` aparte slice `teammate_observation_closest` (subset van `teammate0_*` features) |
| `rl/ExperienceCollector.java` | NPZ-aux key `teammate_state` (alleen voor joint NPZ-files) |
| `train/rl/shared/sac_core/replay_buffer.py` | Optioneel aux `teammate_state_dim` parallel aan `aux_target_enabled` en `reward_decomp_keys` |

### Python-kant

| Bestand | Wijziging |
|---|---|
| `train/rl/shared/sac_core/networks.py` | Nieuwe `CTDEMultiHeadSACCritic` — Q over `concat(self_state, team_state) + action` met dezelfde head-dict-structuur als bestaande `MultiHeadSACCritic` |
| `train/rl/shared/sac_core/sac_step.py` | Critic-update krijgt joint state; actor-update blijft self-only. Geen `if model_key == "..."` branches (CLAUDE.md) — gedrag wordt door `cfg.ctde_mode` gestuurd |
| `train/rl/rl_pawn/trainSAC/bootstrap.py` | Critic-instantiatie schakelt om `cfg.ctde_mode == "team_aggregates"` of `"closest_one"`. De andere bootstraps (`rl_pawn`, `rl_pawn`, `rl_pawn`) blijven onaangeraakt. |
| `train/rl/rl_pawn/trainSAC/export_validation.py` | Probe moet de team-state-slice ook produceren (anders hard-fail bij export — een van de bestaande safety-nets) |

### Wat **niet** verandert

- Runtime inference path: `ModelWatcher` → ONNX → `IntentBus` → `CommandController`. CTDE is alleen training-side.
- Reward-pipeline: bestaande `RewardComputer` blijft per-bot, reward-decomposition gebruikt bestaande pre-wired MultiHeadSACCritic infrastructuur.
- Behavior tree / mission/skill/route stack: orthogonaal.

## Alternatieve route: signaling policies

De L1–L3 levels hierboven volgen de MADDPG/MASAC-lijn: critic ziet teammate-*state* en leert team-utility correlaties impliciet. Een orthogonaal alternatief uit de coordinate-descent literatuur (Bertsekas 2026, *A Course in Reinforcement Learning* 2e ed., Sec 2.9.1) is om de critic *teammate-acties* te laten zien — voorspeld door een aparte, off-line getrainde **signaling policy** `μ̂` die alle bots kennen.

### Idee

```
Bestaande L2/L3 critic:
    Q(s_self, s_teammate_obs, a_self)        ← teammate-state in critic-input

Signaling variant:
    Q(s_self, a_self, μ̂(s_teammate_obs))     ← voorspelde teammate-actie in critic-input
```

`μ̂` is een tweede netwerk dat *teammate's volgende actie* schat uit teammate's geobserveerde state-slice. Off-line BC-getraind op het eigen replay-corpus (elke bot heeft teammates die andere bots zijn — hun ground-truth acties zitten al in de NPZ-files van die teammates, mits tick-aligned of via self-perceived approximatie).

Bij inference is `μ̂` niet nodig — alleen actor `π` blijft live. CTDE-property behouden.

### Wat dit motiveert

| Argument | Toelichting |
|---|---|
| Geen state-dim explosion | Critic-input groeit met `dim(a_teammate)` (~10 voor `rl_pawn`) in plaats van `dim(s_teammate)` (~honderden features) |
| Natuurlijke COMA-baseline | Counterfactual baseline `Q(s, a) − E_{a~μ̂}[Q(s, …)]` valt rechtstreeks uit deze structuur (vs L1–L3 waar COMA een aparte uitbreiding is) |
| Geen tick-id sync | `μ̂` wordt off-line getraind; runtime is forward-only en self-only. Self-perceived teammate-obs is voldoende input voor `μ̂` (variant A blijft de pragmatische bron) |
| Sluit aan op multi-head decomp | `μ̂(s_teammate)` levert een joint actie-distributie over alle teammate-heads (movement/VR/shooting). De 6e `team_assist` head consumeert exact deze voorspelling als feature in zijn Bellman-target |

### Spider–fly precedent

Bertsekas Example 2.9.1 en 2.9.4: twee spiders, twee flies. Base policy = "ga naar dichtstbijzijnde fly" produceert collisions (beide spiders naar dezelfde fly). Multiagent rollout met een signaling policy lost dit op door spider 1 te laten anticiperen op spider 2's keuze. **Identieke topologie** als jullie carrier-shadow probleem: meerdere bots die zonder coördinatie hetzelfde target/lane kiezen. De `tactical-spatial-constraints` doen dit nu via hardcoded constraints; signaling-via-`μ̂` zou hetzelfde *leren*.

### Belangrijke waarschuwing

Bertsekas Example 2.9.3-4: als de signaling policy onvoldoende discriminerend is tussen agents (bv. `μ̂` = de base policy zelf en alle agents hebben identieke policy), kan het systeem oscilleren of zelfs **slechter** worden dan de base policy. Toepassing op jullie 5v5: gebruik **geen** `μ̂ = π` direct — train `μ̂` op rol-gelabelde transitions (Attack/Cover/Defend uit `gameplay.json`-rolverdeling) of voeg `role_priority_rank_norm` als input toe. Dit maakt Fase 0 (rolverdeling activeren) tot een **harde** dependency voor deze variant.

### Implementatie-impact (extra t.o.v. L2 variant A)

| Bestand | Wijziging |
|---|---|
| `train/rl/shared/sac_core/signaling_policy.py` (nieuw) | BC-trainer + ONNX-export voor `μ̂`. Geen separate runtime — alleen training-side weights, vergelijkbaar met hoe target-networks nu draaien |
| `train/rl/rl_pawn/trainSAC/bootstrap.py` | Bij `cfg.ctde_mode == "signaling"`: instantieer `μ̂`, laad checkpoint, freeze (geen gradient flow naar `μ̂` tijdens SAC critic-update) |
| `train/rl/shared/sac_core/sac_step.py` | Bellman-target: `target_Q = r + γ · Q_target(s', π(s'), μ̂(s'_teammate))` |
| `train/rl/shared/sac_core/networks.py` | `SignalingCritic` variant van `MultiHeadSACCritic` die action-dim met `dim(a_teammate)` uitbreidt i.p.v. state-dim |

`μ̂` retraining-cadence: orde van magnitude trager dan SAC-loop (elke N champion-promoties bv.), om bootstrap-instabiliteit te vermijden — analoog aan target-network update frequency, maar dan voor het signaling-network.

### Wanneer kiezen voor deze route i.p.v. L2/L3

| Kies signaling als… | Kies L2/L3 als… |
|---|---|
| Je COMA difference-rewards uiteindelijk wilt | Je COMA permanent uit scope houdt |
| Tick-id sync structureel te duur is | NPZ-schema-uitbreiding goedkoop is |
| Teammate-action voorspelbaarheid hoog is (uniforme policy) | Teammate-state rijker informatie bevat dan teammate-action |
| Je een natural extensie naar 3-vs-3 / 4-vs-4 wilt zonder critic-state-dim te laten meegroeien | Je 5v5 als vaste setup ziet |

Beide routes blijven in scope voor `rl_pawn`. Deze notitie kiest er geen — beide zijn legitiem en kunnen sequentieel geprobeerd worden.

## Phased rollout (historisch + open horizon)

Alleen `rl_pawn` raakt in iedere fase aangepast.

| Fase | Inhoud | Status |
|---|---|---|
| 0 | Activeer rolverdeling in `gameplay.json` (2 Attack + 1 Cover + 1 Defend per team). Train baseline op huidige joint stack. | **LIVE** |
| 1 | L1: team-aggregaat features toegevoegd aan `rl_pawn/features.json`. De bestaande 5-head critic ziet ze automatisch via `s_self`. Architecturaal nul-impact, alleen feature-uitbreiding. | **LIVE** ([`TeamAggregateFeatureComponent`](../../java-aiplay/src/main/java/aiplay/scanners/feature/resolver/team/TeamAggregateFeatureComponent.java)) |
| 2 | Voeg 6e head `team_assist` toe aan `MultiHeadSACCritic` + bijbehorende `reward_decomp` slice in `rewards.json`. Bestaande 5 heads onveranderd. | **LIVE** (sac.json `reward_decomp_keys` incl. `team_assist`; rewards.json `defender_presence` + `cover_escort` + `team_assist`) |
| 2.5 | L2 variant A: closest-2 teammate's self-perceived obs slice als aparte input naar critic. Joint state = `concat(s_self, s_teammate_closest2)`. Carrier-first ordering. | **LIVE** (`ctde_mode: "closest_two"`, `teammate_state_dim: 40`; [`TeammateStateExtractor`](../../java-aiplay/src/main/java/aiplay/rl/TeammateStateExtractor.java)) |
| 3 | L2 variant B met tick-id sync (true teammate obs), of L3 met teammate-acties (full MASAC). | **NIET LIVE — open horizon.** NPZ-schema mist `(match_id, tick)` keys; geen cross-bot ingest-join in Python. Geen `teammate_actions` aux key. Triggert pas wanneer L2A self-perceived bias bewezen bottleneck wordt. |
| 4 | PBT op reward-weights (zie [pbt-reward-tuning.md](pbt-reward-tuning.md)). | **NIET LIVE — open horizon.** Triggert pas wanneer handmatige weight-tuning de bewezen bottleneck is. |

Fase 0 was de harde prerequisite voor alles erna. Zonder rolverdeling
optimaliseren alle bots dezelfde reward — geen team-utility-gradiënt om te
leren, ongeacht hoeveel team-info de critic ziet.

## Risico's

| Risico | Mitigatie |
|---|---|
| Team-aggregaten zijn gecorreleerd met self-features → marginale informatie | Voeg in Fase 1 alleen aggregaten toe die *niet* afleidbaar zijn uit `teammate{N}_*` (bv. `team_capture_score_delta_5s`) |
| L2 slice vergroot state_dim → critic-overfit / langzamere training | Begin met closest-1; volg replay buffer fill-rate en target-update-frequency. State_dim bij `rl_pawn` is sowieso al groot vanwege joint VR+shooting input. |
| Bestaande 5-head Q-decomp (Movement_flag etc.) raakt verstoord door joint state | Fase 2 voegt alleen toe; bestaande heads blijven dezelfde reward-slices consumeren. Movement_flag KPI is een primair acceptatie-criterium (zie joint_multihead_critic memory). |
| 6e `team_assist` head domineert gradient-flow → overshadowing bestaande heads | Schaal `team_assist` reward conservatief; alle bestaande dense scales blijven leidend (zie [reward-architecture.md](../rewards/reward-architecture.md) §Dense vs Sparse). |
| Self-perceived teammate-state is bias (bot ziet niet wat teammate echt ziet) | Voor de critic is dit acceptabel — hij hoeft alleen *correlaat* met team-utility te leren. Voor MASAC pure variant nodig — Fase 4 |
| `team_assist` head naam-collision met bestaande joint head_keys | Gebruik prefix `coord_team_assist` als de bestaande set al een `team_*` key bevat; controleer bij Fase 2 start |
| Probe-hard-fail van bestaande safety-net triggert bij eerste joint-state run | Update `export_validation.py` synchroon met `bootstrap.py` zodat de probe team-state-slice produceert |

## Wat dit NIET probeert op te lossen

- **CTDE voor andere policies**. Joint `rl_pawn` is de enige low-level policy; geen andere modellen die CTDE-uitbreiding behoeven.
- **Window-lengte**. Joint `rl_pawn` heeft zijn eigen window-config; lang geheugen is een orthogonale as. "Memory-trace features" zoals `teammate{N}_secondsSinceSeen_decay` zijn een mogelijke follow-up maar geen CTDE.
- **Cross-team coordinated planning** (bv. one bot in tunnel A, one in tunnel B). CTDE leert *correlaties*, geen *expliciete* taakverdeling — die zou via skill-level role-arbitration moeten.
- **Communicatie tussen bots** (emergent of expliciet message-passing). Niet in scope; klassiek MADDPG / MASAC is silent-coordination.

## Gemaakte keuzes (Fase 0-2.5) + open horizon

| Keuze | Gekozen | Alternatief (niet gekozen) |
|---|---|---|
| CTDE-route | MASAC-school (Lowe/Pu) | Signaling-school (`μ̂` teammate-action-predictor, Bertsekas Sec 2.9.1) — blijft open horizon |
| Joint-state level | L2A (closest-2 self-perceived slice) | L2B (tick-sync), L3 (full MASAC) — open horizon |
| Reward decomposition | `team_assist` head bovenop bestaande 5-head Q-decomp (de 6e; later kwam `residual` als 7e bij — live nu 7 heads) | Geen team-head — verworpen |
| Counterfactual baseline | Geen COMA in Fase 2.5 | COMA difference rewards — open horizon, natuurlijker onder signaling-route |
| Gameplay.json rolverdeling | 2 Att + 1 Cov + 1 Def per team | Andere mix |

### Open horizon

| Item | Trigger |
|---|---|
| Fase 3 / L2B (tick-id sync voor true teammate obs) | Pas plannen wanneer L2A self-perceived bias bewezen bottleneck is in replay-analyse |
| Fase 3 / L3 (full MASAC met teammate-acties) | Pas plannen na L2B; vereist NPZ teammate_actions aux key + Python critic uitbreiding |
| Signaling-school (`μ̂`) | Alternatief pad — alleen relevant als COMA-baselines gewenst worden |
| Fase 4 PBT | Zie [pbt-reward-tuning.md](pbt-reward-tuning.md) — pas plannen wanneer handmatige tuning de bottleneck wordt |

## Referenties (architectureel, geen URL's)

- Lowe et al. 2017 — MADDPG: centralized critic met joint state+action
- Foerster et al. 2018 — COMA: counterfactual baseline voor multi-agent credit assignment
- Iqbal & Sha 2019 — MAAC: attention over teammates in critic (relevant voor L3)
- Pu et al. 2020 — MASAC: centralized critic op SAC off-policy
- Bertsekas 2026 — *A Course in Reinforcement Learning* 2e ed., Sec 1.6.7 en Sec 2.9 (multiagent rollout, cost improvement property), Sec 2.9.1 (signaling policies + asynchronous variant). Theoretische basis voor de signaling-variant
- Matignon, Laurent & Le Fort-Piat 2012 [MLL12] — RL-perspective survey op coordination problems (door Bertsekas geciteerd in Sec 2.9.1 voetnoot)

Onze stack draait SAC off-policy; MASAC is de directe algoritmische
uitbreiding wanneer we van L1 naar L3 willen. De signaling-route is een
parallel pad uit de coordinate-descent literatuur dat het critic-state-dim
trade-off anders maakt en COMA-baselines natuurlijker integreert.
