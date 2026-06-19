# Team Coordination Rollout — CTDE Fase 2 + 2.5 (COMPLETED)

Operationeel vervolg op [team-coordination.md](team-coordination.md). Gericht op één concreet probleem: **Bear (Defend) en Hawk (Cover) gedragen zich als Attack-bots — ze campen in de enemy base in plaats van te defenden / te coveren.**

Dit rapport bepaalde waarom dat zo was, wat daar architectureel aan moest gebeuren, en in welke volgorde. **Fase 2 + Fase 2.5 zijn uitgerold** (rewards + 6e team_assist head + closest-2 teammate joint-state critic met carrier-first ordering). Fase 3 en 4 blijven open horizon — zie §9.

Status op 2026-05-20 (besluitvormingsmoment); rollout-status hieronder geverifieerd vandaag.

---

## 1. Gedragsdoelen

| Rol | Bot | Huidig gedrag | Gewenst gedrag |
| --- | --- | --- | --- |
| Defend | Bear | Camp in enemy base, jaagt frags | Bewaak eigen vlag/base. Engage zodra enemy `enemy_depthInOwnHalf_norm > ~0.25`. Recover own flag wanneer die buitgemaakt is. |
| Cover | Hawk | Camp in enemy base, jaagt frags | Escorteer Attack-teammate richting enemy flag. Houd afstand binnen ~1500 UU van Attack-bot. Bind de tegenstander die hij gaat tegenkomen. |
| Attack | Falcon | Camp in enemy base, jaagt frags | Push enemy flag, capture. (Gedrag is nu acceptabel voor deze rol.) |

Dit zijn de criteria waaraan elke fase getoetst wordt: heeft de wijziging Bear weg van enemy base getrokken en in eigen helft gehouden, en Hawk dichter bij Falcon?

---

## 2. Status na verificatie

### 2.1 Live (Fase 0 + Fase 1 + Fase 2 + Fase 2.5 voltooid)

| Component | Bewijs | Opmerking |
| --- | --- | --- |
| Rolverdeling 2 Attack + 1 Cover + 1 Defend per team | `resources/config/gameplay.json` — Falcon/Wolf=Attack, Hawk=Cover, Bear/Boar=Defend (Wolf/Boar disabled) | Fase 0 doelconfiguratie precies live. |
| 4-dim rewardgroups one-hot in policy input | `features.json:515-520`, `RewardGroupFeatureComponent.java` | Last-frame-only feature; policy ziet zijn rol via SAC-input. |
| Role-context features in input | `features.json:709-718` — `self_proximityToOwnFlag_norm`, `enemy_depthInOwnHalf_norm`, `teammateCarrier_proximity_norm` | Bouwstenen voor rol-conditioned behaviour. |
| Role-aware attention prior | `RuleBasedEngagementPolicy.java:194-215` — Defend kijkt naar carrier/home-flag, Cover naar enemy near Attack-teammate | Beïnvloedt alleen waar de bot kijkt, niet waar hij heen beweegt. |
| Role-asymmetrische rewards | `rewards.json` rewardgroups 0/1/2/3 | Verschillende `team_captured`, `team_returned`, `progress_scale`, `alive_bonus`, `own_flag_return_progress_scale`. |
| 5 team-aggregaat features in `s_self` (Fase 1 L1) | `features.json:732-740`, `TeamAggregateFeatureComponent.java` | `team_meanHealth_norm`, `team_meanDepth_norm`, `team_aliveCount_norm`, `team_captureProgress_norm`, `team_droppedCount_norm`. Critic ziet ze automatisch via shared trunk. |
| `defender_presence` reward-component (Fase 2) | `DefenderPresenceReward.java` + `rewards.json:375` (defaults) + Defend rewardgroup override met `home_bonus=0.15`, `enemy_half_penalty=0.08` | Bidirectional reward — positief in eigen helft met enemy_depth > threshold, klein negatief in enemy helft zonder threat thuis. |
| `cover_escort` reward-component (Fase 2) | `CoverEscortReward.java` + `rewards.json:385` + Cover rewardgroup override | Dichte escort shaping tov teammate-met-flag; sentinel 0 wanneer geen carrier (open-field-fase gedekt door `team_assist.escort_proximity_dense`). |
| `own_flag_return_progress_scale` actief buiten RETURN_HOME (Fase 2) | Defend rewardgroup `own_flag_return_progress_scale: 0.06`, Cover `0.025` — actief wanneer `enemy_team_has_our_flag` | Recovery-pull naar eigen vlag (drop-site of enemy-carrier-positie), onafhankelijk van mission. |
| `team_assist` reward-component met 5 sub-componenten (Fase 2.5) | `TeamAssistReward.java` + `rewards.json:396` (defaults) + per-role overrides | (1) `team_captured_assist` (sparse, bot binnen `assist_radius_uu` van eigen flag base bij teammate-capture); (2) `team_returned_assist` (sparse, instigator != self); (3) `carrier_kill_assist` (sparse, carried→dropped transitie + bot binnen `kill_assist_radius_uu`); (4) `escort_proximity_dense` (dense per-tick richting closest teammate); (5) `endgame_attack_bonus` (catchup modulator). |
| `team_assist` critic head (Fase 2.5) | `sac.json:58` — `reward_decomp_keys: ["movement","view","pitch","fire","altFire","team_assist","residual"]` (7 heads; team_assist toegevoegd in Fase 2.5, residual als 7e head op 2026-05-30) | Aparte gradient-channel voor team-utility. Conservatieve head_weight zodat Movement_flag KPI niet regressed. |
| `team_assist` reward-decomp in NPZ (Fase 2.5) | `JointRewardDecompositionStrategy` + `RewardDecomposition.java` | Per transitie geschreven naast bestaande reward_movement/view/pitch/fire/altFire. |
| `TeammateStateExtractor` closest-2 met carrier-first ordering (Fase 2.5) | `TeammateStateExtractor.java` — `FEATURES_PER_SLOT=20`, `NUM_SLOTS=2`, `SLICE_SIZE=40` | Carrier-first reorder: slot 0 = teammate die de flag draagt (indien aanwezig), slot 1 = dichtste van overige; fallback: dichtste-2. 20 features per slot incl. health, armor, hasFlag, relSin/Cos, distance, velocity-deltas, fire flags, dodge cooldown. |
| `teammate_state` NPZ aux key (Fase 2.5) | `ExperienceCollector.java:54-62, 163-164` + `PerModelExperienceRecorder.java:258-261` | Parallel aan `target_label` aux key precedent. Critic-only input — nooit naar runtime actor. |
| `ctde_mode` config flag (Fase 2.5) | `sac.json:73` — `ctde_mode: "closest_two"`, `teammate_state_dim: 40` | Schakelt critic-instantiatie in `bootstrap.py:_build_critics()`. |
| Joint-state input voor critic (Fase 2.5 L2A) | `networks.py:71-141` `MultiHeadSACCritic` — accepteert optionele `teammate_state_dim`; forward concatenates `[state, teammate_state, action]` | Critic-update in `sac_step.py compute_critic_loss()` consumeert teammate-state via Bellman-target. Actor blijft self-only. |
| Replay buffer hard-fail wanneer NPZ key ontbreekt + `ctde_mode != "off"` | `replay_buffer.py:111-129, 308-324` | Configuratie-consistency-net per CLAUDE.md no-fallback rule. |
| Export-validation probe (Fase 2.5) | `export_validation.py:41-100` | Verifieert `ctde_mode` ↔ `replay.ctde_enabled` consistency vóór ONNX-export. |
| Endgame catchup modulator | `rewards.json:87` + `team_assist.endgame_attack_bonus` per-role | Dense per-tick pull naar enemy half tijdens slotfase bij achterstand; defender_presence + cover_escort dempen via `(1-urgency)`. |
| MultiHeadSACCritic met 7 heads | `sac.json:49-50` — `critic_mode: "multi_head"`, `reward_decomp_keys` heeft 7 entries | Per-skill Bellman backup live sinds 2026-05-14; team_assist-head toegevoegd in Fase 2.5, residual-head als 7e op 2026-05-30. |

### 2.2 Niet live (Fase 3+ open horizon)

| Component | Status | Impact |
| --- | --- | --- |
| L2B tick-id sync (true teammate observation) | NPZ-files hebben geen `(match_id, tick)` keys; geen cross-bot ingest-join in Python | Huidige L2A gebruikt self-perceived teammate-state — bias acceptabel voor critic-context, maar onnauwkeurig voor true team-utility correlatie. Triggert pas wanneer L2A self-perceived bias bewezen bottleneck wordt. |
| L3 full MASAC (teammate-acties) | `ExperienceCollector` schrijft geen `teammate_actions` aux key; `MultiHeadSACCritic` neemt geen `teammate_actions` parameter | Volgende stap na L2B; vereist tick-aligned action-logging. |
| Signaling-school (`μ̂` teammate-action predictor) | Geen `signaling_policy.py`, geen `μ̂` netwerk-klasse | Alternatief pad — bleef intentioneel design-only (zie §4: MASAC L2A gekozen boven Signaling). |
| Fase 4 PBT-infrastructuur | Geen `train/rl/rl_pawn/pbt/` directory, geen `coordinator.py` of `search_space.py` | Zie [pbt-reward-tuning.md](pbt-reward-tuning.md). Triggert pas wanneer handmatige weight-tuning de bottleneck is. |

---

## 3. Diagnose — waarom Bear en Hawk campten (pre-rollout)

Historische diagnose die de keuzes in §4 en §5 onderbouwde. Behouden als context; gedrag is na rollout veranderd, niet de analyse.

Drie convergerende oorzaken, niet één.

**(a) De mission stuurt iedereen naar de enemy flag.** `CAPTURE_FLAG` is rol-blind. Dat geeft alle bots dezelfde objective, dus dezelfde dense `objective_progress.progress_scale` gradient richting enemy base. Defend krijgt die scale wel zwakker (0.004 vs 0.05 voor Attack) maar nog steeds positief.

**(b) De reward-architectuur premieert combat overall sterker dan rol.** `combat_event.frag` is +5.0 voor élke rol. `damage_delta.dealt_per_hp` is +0.0625 voor élke rol. `flag_event.team_captured` is +6.0 voor Defend, +12.0 voor Cover, +3.0 voor Attack — wanneer een teammate captures krijgt iedereen die bonus. Een Defend-bot die in enemy base camp tegen de spawn pickt 2-3 kills per minuut én harvest passief de team_captured-bonus van Attack. Dat is een orde van grootte sterker dan de `alive_bonus=0.015/sec` die hij krijgt voor patrouilleren in eigen helft.

**(c) De critic ziet geen team-context per teammate.** De vijf bestaande heads krijgen team-aggregaten (mean health, mean depth, alive count, capture progress, dropped count) via de shared trunk. Dat is genoeg voor de critic om te leren "het team scoort vaker als gemiddelde depth hoger is", maar niet om Bear's Q-waarde te koppelen aan "Falcon staat 200 UU van de enemy flag en heeft jouw cover nodig" of "Bear staat 800 UU van eigen vlag en de tegenstander is op 0.30 depth". Zonder die per-teammate context blijft de policy-leersignaal egoïstisch.

[team-coordination.md](team-coordination.md) §"Probleemstelling" voorspelt precies dit: *"De decentralized actor weet waar zijn teammates zijn, maar de critic die hem traint kent geen team-utility. Daardoor leert de actor egoïstische policies — teamwork kan alleen toevallig ontstaan via shaped rewards."* Wat we nu zien is de empirische bevestiging.

Twee zwakkere bijdragen, niet hoofdoorzaak maar wel relevant:
- De `rewardgroups` one-hot is single-bit-per-frame. LSTM accumuleert het signaal over tijd, maar de gradient is dun.
- `DualKPIDeltaGate` is rol-blind: combat_score over alle bots. Een Defender die rolbreker wordt en frags pakt wordt door de gate niet gestraft. (Conform memory `feedback_delta_gate_untouched`: niet aanraken; dit blijft een passieve observatie, geen voorstel om de gate aan te passen.)

---

## 4. Route-keuze — waarom MASAC L2A, niet Signaling

[team-coordination.md](team-coordination.md) laat de keuze tussen twee schools open:
- **MASAC-school** (Lowe/Pu): critic ziet teammate-*state* via L1 → L2A → L2B → L3.
- **Signaling-school** (Bertsekas Sec 2.9.1): critic ziet voorspelde teammate-*actie* via aparte `μ̂` predictor.

Voor het Bear/Hawk-probleem kiest dit rapport **MASAC L2A** (closest-1 teammate self-perceived obs). Vier overwegingen:

1. **Het probleem is positie-gedreven, niet actie-gedreven.** Bear moet leren "Falcon staat dichtbij enemy base en heeft me niet nodig hier" — daar helpt Falcon's *positie* en *flag-status*, niet zijn voorspelde dodge-richting. Hawk moet leren "Falcon is 1200 UU verderop, ik ben uit escort-range" — afstand is een state-feature, geen action-voorspelling. `μ̂(s_teammate)` zou de verkeerde dimensie informatie geven.

2. **L2A is pragmatisch te bouwen op bestaande infrastructure.** Aux-array pattern is precedent (target_label, target_confidence). Geen tick-sync nodig: de bot logt zijn eigen perceptie van teammate (variant A in design). Geen offline ML-loop nodig voor `μ̂`. Cost-analyse: ~3-5 dagen vs. ~2-3 weken voor Signaling.

3. **Signaling vereist een discriminerende `μ̂`.** Bertsekas Example 2.9.3-4 waarschuwt: als `μ̂` te dicht bij `π` ligt, oscilleert het systeem of wordt het slechter dan baseline. Voor onze symmetrische 5v5 vereist dat ofwel rol-gelabelde transitions (we hebben dat nu) ofwel een extra input zoals `role_priority_rank_norm`. Niet onmogelijk, maar extra leerprobleem bovenop het basisprobleem.

4. **L2A is een stap richting L2B/L3 als die later nodig blijken; Signaling is een aparte tak.** Wanneer L2A onvoldoende blijkt kan tick-id sync (L2B) erop gestapeld worden zonder de critic opnieuw te ontwerpen. Signaling zou een tweede netwerk en een tweede trainings-cyclus betekenen.

Signaling blijft een geldige horizon-optie als we later COMA-baselines willen. Niet vandaag.

---

## 5. Implementatieplan in 3 fases

Elke fase staat op zichzelf — als de volgende fase niet helpt, is de vorige niet verloren. Fase 2 + Fase 2.5 zijn samen uitgerold in één deploy-cyclus (zie §7); Fase 3 + 4 blijven open horizon.

### Fase 2 — Reward gap dichten, 5-head houden (COMPLETED)

**Doel.** Dichten van het gat dat de huidige reward-shaping al inviteert maar niet inlost. Geen critic-architectuur wijziging. Run als baseline-update tegen huidige Fase 1 setup.

**Wijzigingen.**

| Bestand | Wijziging |
| --- | --- |
| `resources/models/rl_pawn/rewards.json` (Defend group) | Nieuwe `defender_presence` reward-component die `enemy_depthInOwnHalf_norm` consumeert: positieve weight wanneer enemy depth > 0.25 en bot in eigen helft, kleine negative wanneer bot in enemy helft zonder enemy threat thuis. Schaal in dezelfde grootte-orde als `alive_bonus` × 5 (~0.075/sec piek). |
| `resources/models/rl_pawn/rewards.json` (Cover group) | Nieuwe `cover_escort` reward-component die `teammateCarrier_proximity_norm` consumeert: positieve weight wanneer Cover binnen ~1500 UU van Attack-teammate, kleine negative wanneer > 3000 UU. Schaal vergelijkbaar. |
| `resources/models/rl_pawn/rewards.json` (Defend group) | `own_flag_return_progress_scale` actief ook in `CAPTURE_FLAG` wanneer `enemy_team_has_our_flag`. Vergt een kleine resolver-tweak in de reward-pipeline. Niet de mission-policy aanpassen (die blijft rol-blind voor nu). |
| Geen wijzigingen aan `DeltaGate`, `flag_event.team_captured` weights, of mission-policy. |

**Validatie (per memory `feedback_minimal_metrics` — geen nieuwe metrics-tools).**

Bestaande SCORE_INCREASE logs filteren op rol via bot-name. Grep-script vanaf dev. Acceptatie: na 24h SAC fine-tuning vanaf het huidige champion:
- Bear's gemiddelde `self_proximityToOwnFlag_norm` over 5 matches stijgt minstens 25%.
- Hawk's gemiddelde `teammateCarrier_proximity_norm` stijgt minstens 25%.
- Movement_flag KPI niet regressed in `delta_gate` (bestaande safety net).

**Aanname.** Fase 2 alleen lost waarschijnlijk 25-40% van het gedragsprobleem op (Defend krijgt een echte pull naar eigen helft, Cover naar Attack-teammate). Per Conclusie 3 in het reward-onderzoek is full fix architectuur-bound — daarvoor Fase 2.5.

### Fase 2.5 — Joint-state in critic via L2A, plus `team_assist` head (COMPLETED)

**Doel.** De critic per-teammate-context geven en een aparte gradient-channel voor team-utility openen.

**Wijzigingen Java-kant.**

| Bestand | Wijziging |
| --- | --- |
| `java-aiplay/src/main/java/aiplay/rl/PerModelExperienceRecorder.java` | Per-tick extractie van closest-teammate slice (~20 features: `teammate0_relSin/relCos/distanceNorm/health/hasFlag/pitchBearing` + enkele afgeleiden). Per-bot, self-perceived. |
| `java-aiplay/src/main/java/aiplay/rl/ExperienceCollector.java` | Aux key `teammate_state.npy` (parallel aan bestaande `target_label.npy`), `synchronized` op dezelfde flush. |
| `resources/models/rl_pawn/rewards.json` | Nieuwe rewardgroup-onafhankelijke `team_assist` reward-component: `r_team_captured_assist + r_team_returned_assist + r_carrier_kill_assist + r_carrier_escort_proximity`. Schaal conservatief — bestaande dense scales blijven leidend. |
| `resources/models/rl_pawn/sac.json` | `reward_decomp_keys` uitbreiden naar 6: `["movement","view","pitch","fire","altFire","team_assist"]`. |

**Wijzigingen Python-kant.**

| Bestand | Wijziging |
| --- | --- |
| `train/rl/shared/sac_core/replay_buffer.py` | Optionele `teammate_state_dim` parallel aan bestaande `aux_target_enabled` en `reward_decomp_keys`. Hard-fail wanneer NPZ key mist en config staat aan (zelfde pattern als target_label). |
| `train/rl/shared/sac_core/networks.py` | Critic-forward accepteert optionele tweede state-tensor; nieuwe `CTDEMultiHeadSACCritic` of uitgebreide bestaande klasse (afhankelijk van wat minder regressies geeft). |
| `train/rl/shared/sac_core/sac_step.py` | Bellman-target consumeert joint state in critic, actor blijft self-only. Niet via `if model_key == "..."` — via `cfg.ctde_mode` flag (CLAUDE.md preference). |
| `train/rl/rl_pawn/trainSAC/bootstrap.py` | `cfg.ctde_mode in {"off", "team_aggregates", "closest_one"}` schakelt critic-instantiatie. |
| `train/rl/rl_pawn/trainSAC/export_validation.py` | Probe genereert de teammate-state-slice mee, anders hard-fail (bestaande safety net activeren voor nieuwe inputshape). |

**Validatie.**
- Probe-hard-fail check: critic-forward met team-state moet niet crashen.
- Per-head Q-magnitudes: `team_assist` mag niet de bestaande 5 heads overschaduwen (schaal conservatief; bestaande dense scales leidend, zie [reward-architecture.md](../rewards/reward-architecture.md) §Dense vs Sparse).
- Movement_flag KPI: niet regressed (primaire acceptatiecriterium uit memory `project_rl_pawn_architecture`).
- Gedragsobservatie via bestaande replay-logs: Bear in eigen helft > 60% match-tijd, Hawk binnen 2000 UU van Attack-teammate > 50%.

**Risico's en mitigaties.**

| Risico | Mitigatie |
| --- | --- |
| Joint-state vergroot critic input-dim → overfit / langzamere training | Begin met closest-1 teammate (niet alle 4). Volg replay buffer fill-rate. Mocht convergence trager worden, verlaag `lr_critic` tijdelijk. |
| Self-perceived teammate-state is bias (bot ziet niet wat teammate echt ziet) | Voor critic acceptabel — hij hoeft alleen correlaat met team-utility te leren, niet exact teammate-perspectief. Variant B (L2B) is de pure oplossing maar groot werk. |
| Bestaande 5-head Q-decomp raakt verstoord door joint state | Fase 2.5 voegt alleen toe; bestaande heads blijven dezelfde reward-slices consumeren. Movement_flag KPI is primaire safety. |
| `team_assist` head domineert gradient-flow | Schaal conservatief; alle bestaande dense scales blijven leidend. |
| Probe hard-fail triggert bij eerste joint-state run | Update `export_validation.py` synchroon met `bootstrap.py`. Niet andersom (geen export voordat de probe weet wat te verwachten). |
| Champion-checkpoint incompatibel met nieuwe critic-shape | Volgen pattern van 2026-05-14 single→multi_head overgang: critic checkpoint weg, actor blijft compatibel. Documenteer in deploy-notitie. |

**Aanname.** Fase 2 + Fase 2.5 samen lost het Bear/Hawk-probleem voor circa 70-85%. Resterende drift (bv. Bear die soms toch ver naar voren beweegt wanneer team-pressure hoog is) is acceptabel rol-gedrag, geen rolbreker.

### Fase 3 — Optioneel, alleen als 2.5 onvoldoende blijkt (OPEN HORIZON)

L2B (true tick-sync) of het volledige team_coordination.md Fase 3 plan. Vereist NPZ-schema-uitbreiding met `(match_id, tick)` en cross-bot ingest-join in Python. 2-3 weken werk. Niet plannen tot Fase 2.5 baseline-resultaten een self-perceived-bias bottleneck aantonen.

### Fase 4 — PBT op reward-weights (parallel pad, OPEN HORIZON)

Wanneer Fase 2.5 draait maar de handmatige tuning van `defender_presence` / `cover_escort` / `team_assist`-schalen de bewezen bottleneck wordt: Population-Based Training rond de bestaande SAC-stack, met DualKPIDeltaGate als fitness en de multi-machine trainer-slots als population. Geïnspireerd op DeepMind FTW (Jaderberg et al. 2019). Tunes alleen schalen, niet *welke* components actief zijn. Volledige design in [pbt-reward-tuning.md](pbt-reward-tuning.md). Orthogonaal aan Fase 3 — kan eraan voorafgaan of erna komen.

---

## 6. Wat dit plan expliciet NIET wijzigt

Per gebruikersmemories en bestaande architectuurregels:

- `DualKPIDeltaGate` — werkt correct, niet aanraken (`feedback_delta_gate_untouched`).
- BC-anchor — geen retroactieve BC-target shaping om Defend "naar huis" te trekken (`feedback_rl_not_bc_anchor`). Reward-shaping moet binnen RL gebeuren.
- Mission-policy structuur — Fase 2/2.5 maakt mission rol-blind. Een role-aware MissionPolicy met nieuwe `DEFEND_HOME` / `COVER_ATTACKER` mission-types zou een feature-tensor wijziging zijn en valt buiten scope.
- Auto-orchestratie van rol-rotatie / curriculum-stage-switches blijft handmatig (`feedback_no_auto_orchestration`).
- Geen nieuwe metrics-infrastructuur (`feedback_minimal_metrics`); validatie via bestaande SCORE_INCREASE en replay-logs.
- Geen Bertsekas Signaling — gemotiveerd in §4.

---

## 7. Gemaakte keuzes (2026-05-20)

| Keuze | Beslissing |
| --- | --- |
| Fasering | **Fase 2 + Fase 2.5 gebundeld** in één deploy-cyclus. Eén nieuwe critic-architectuur, eén reward-update. Critic-checkpoint moet weg vóór restart (zelfde pattern als 2026-05-14 single→multi_head). |
| Teammate-slice grootte | **Closest-2 (~40 features)**, met **carrier-first ordering**: slot 1 = teammate die de flag draagt (als die er is), slot 2 = dichtste van de overige. Wanneer er geen carrier is, valt slot 1 terug op dichtste-2 reguliere ordering. |
| `team_assist` reward-componenten | **Alle vier**: `team_captured_assist` (conditioneel: bot binnen X UU van eigen vlag tijdens capture window), `team_returned_assist` (bot binnen range wanneer teammate eigen vlag returnt), `carrier_kill_assist` (kill op enemy carrier OF binnen Y UU van killer), `escort_proximity_dense` (Cover dichtbij Attack-teammate, dense per-tick). |
| Reward-schaal `team_assist` | **Medium (~25% van dense scale)** — vergelijkbaar met `objective_progress.progress_scale`. Sterker team-coordination signaal, met behoud van bestaande heads via Movement_flag KPI safety net. |
| `cover_escort` reward (Cover group, in `rewards.json` rewardgroup1) | **Tov teammate-met-flag** — dichte reward wanneer Hawk dicht bij de flag-drager (eigen of enemy carrier). Sentinel 0 wanneer er geen carrier is; `escort_proximity_dense` uit team_assist neemt de open-field-fase voor zijn rekening. |
| `defender_presence` reward (Defend group) | **Bidirectional**: positief in eigen helft met `enemy_depthInOwnHalf_norm > 0.25`, klein negatief in enemy helft zonder threat thuis. Sterkere gedragspull richting verdedigen. |
| Activatie `own_flag_return_progress_scale` ook in CAPTURE_FLAG | **Ja, wanneer `enemy_team_has_our_flag`**. Defender krijgt echte pull naar eigen vlag wanneer die buitgemaakt is, ongeacht mission-state. |
| Fase 3 trigger | Pas plannen na Fase 2/2.5 baseline-resultaten. Niet meteen in scope. |
| Fase 4 trigger | Pas plannen wanneer Fase 2.5 baseline draait én handmatige weight-tuning de bewezen bottleneck is. Zie [pbt-reward-tuning.md](pbt-reward-tuning.md) §1. |

---

## 8. Referenties

- [team-coordination.md](team-coordination.md) — onderliggende CTDE design-notitie (architectuur-niveau)
- [pbt-reward-tuning.md](pbt-reward-tuning.md) — Fase 4 design (PBT op reward-weights, FTW-geïnspireerd)
- [mission-architecture.md](../policy/mission-architecture.md) — huidige mission/engagement/tactical-stack
- [../rewards/reward-architecture.md](../rewards/reward-architecture.md) §Dense vs Sparse — schaling-richtlijnen voor nieuwe reward-componenten
- `RuleBasedEngagementPolicy.java:194-215` — `pickAttentionPrior` rol-logic die we niet aanraken
- `TeamAggregateFeatureComponent.java` — Fase 1 L1 implementatie (referentie voor patroon)
