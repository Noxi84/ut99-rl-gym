# PBT op Rewardgroup-Weights — Meta-Optimization Voor Reward-Schalen

Design-notitie. Geen implementatieplan. Opvolger van [team-coordination-rollout.md](team-coordination-rollout.md) Fase 2.5 — alleen aan te plannen wanneer Fase 2.5 baseline draait en handmatige weight-tuning de bewezen bottleneck is.

Geïnspireerd op DeepMind's **FTW** (Jaderberg et al., *Science* 2019, *Human-level performance in 3D multiplayer games with population-based reinforcement learning*) en de onderliggende **PBT** paper (Jaderberg et al. 2017). FTW gebruikte IMPALA/V-trace; onze stack draait SAC. PBT zelf is algoritme-agnostisch — alleen de replay-buffer-policy is SAC-specifiek (zie §7).

Status op 2026-05-20: niet ingepland. Document beschrijft pad indien gekozen.

---

## 1. Probleemstelling — waarom dit nadat Fase 2.5 draait

Fase 2.5 voegt meerdere reward-componenten en weight-velden toe waarvan de schalen handmatig gekozen worden:

| Component | Tunables |
| --- | --- |
| `defender_presence` (rewardgroup2) | `home_bonus`, `enemy_half_penalty`, `engagement_threshold` |
| `cover_escort` (rewardgroup1) | `proximity_bonus`, `far_penalty`, `escort_range_uu`, `far_range_uu` |
| `team_assist` (nieuwe head — alle rewardgroups) | `team_captured_assist`, `team_returned_assist`, `carrier_kill_assist`, `escort_proximity_dense` |
| `objective_progress.own_flag_return_progress_scale` activatie | aan/uit per rewardgroup, schaal per groep |

Plus bestaande weights die nooit empirisch geoptimaliseerd zijn — `flag_event.team_captured` per rol (0/3/6/12), `objective_progress.progress_scale` per rol (0.0/0.05/0.01/0.004), `combat_event.frag` (5.0 uniform), `damage_delta.dealt_per_hp` (0.0625), `objective_progress.alive_bonus` (0.0-0.025). Elke gekozen schaal is een geïnformeerde gok plus enkele iteraties.

Risico van handmatige tuning: een te hoge `team_assist.escort_proximity_dense` plakt Cover als zuignaal aan Attack en blokkeert engagement-vrijheid; een te lage `defender_presence.home_bonus` geeft Bear onvoldoende pull naar eigen helft. Iteratie kost een full SAC fine-tune cyclus per kandidaat (~12-24u op de 4090). Bij 4 nieuwe componenten × 2 weights × ~5 kandidaten = 40 cycles. Onpraktisch sequentieel.

PBT is de meta-optimization-loop die parallelle kandidaten evolueert tot de DualKPI DeltaGate-fitness convergeert. **De gebruiker tunet niet langer schalen — de gebruiker tunet de PBT-search-space.**

---

## 2. PBT in één paragraaf

Train een populatie van N agents simultaan, elk met een eigen weight-vector. Evalueer periodiek elke agent op een fitness-signaal. **Exploit:** kopieer de top-K agent-checkpoints + weight-vectoren naar de bottom-K. **Explore:** muteer de gekopieerde weights met een random perturbatie. Itereer. FTW deed dit met 30 agents over weken; wij doen het met 3-5 agents over dagen. Geen gradient terug door de meta-laag — de mutation is pure black-box search.

---

## 3. Wat wordt getuned, wat niet

**Getuned door PBT:**
- Schalen van bestaande `RewardComponent`s in `rewards.json` per rewardgroup
- (Optioneel) SAC-hyperparameters: `actor_lr`, `critic_lr`, `alpha_target`, `gamma`
- (Optioneel) Continue scalars binnen componenten: `engagement_threshold`, `escort_range_uu`, `combo_radius_uu`

**Niet getuned (handmatig, conform `feedback_no_auto_orchestration`):**
- *Welke* reward-components in `rewards.json` actief zijn
- *Welke* heads in `MultiHeadSACCritic` bestaan
- *Welke* features in `features.json` zitten
- DualKPIDeltaGate-logica (`feedback_delta_gate_untouched`)
- BC pretrain-weights en BC-targets (`feedback_rl_not_bc_anchor`)
- Mission/skill/route policy structuur
- *Wanneer* de PBT-run start en stopt (handmatige trigger, geen continu draaiende loop)

PBT vertelt niet *wat* gerewardeerd moet worden — alleen *hoe sterk*.

---

## 4. Fitness signaal — DualKPIDeltaGate

Gebruik de bestaande gate ongewijzigd. DualKPIDeltaGate produceert per agent een `combat_score_delta` en `shots_on_target_rate_delta` t.o.v. baseline. PBT vereist een scalar fitness.

**Aanbeveling:** `fitness = min(combat_score_delta_normalized, shots_on_target_rate_delta_normalized)`. AND-promotion van de gate gerespecteerd — een agent die alleen op één KPI uitblinkt scoort niet. Normalisatie via percentiel-rank binnen huidige population om scale-drift tussen cycles te neutraliseren.

**Aggregation window:** per match-cycle (zie memory `project_match_cycle_coupling` — gate is match-aligned, niet wall-clock). Minstens 3 match-cycles per fitness-update om noise te dempen. Bij population N=3 en 1 match-cycle ≈ 90s ServerTravel + 60s match = ~150s, dus ~7.5 min per fitness-update. Een PBT-run van 48u → ~380 fitness-updates → ruim genoeg signaal voor 3-5 exploit-events.

---

## 5. Population-design op multi-machine infra

Mapping op `servers.json` SAC-trainer slots:

| Machine | Rol in PBT | Member-count |
| --- | --- | --- |
| 4090 | Population-coordinator + member 1 (+ optioneel member 2 als slots toelaten) | 1-2 |
| 4070 | Member 2 (of 3) | 1 |
| 3070 | Member 3 (of 4) | 1 |
| 2070, P15v | Experience-only — spelen tegen alle members ongewijzigd | 0 |

**Population-grootte: start met 3, schaal naar 5 als 4090 dubbel-host.** Klein in FTW-context (30 members), maar:
- Off-policy SAC heeft hogere sample-efficiency dan IMPALA → minder members nodig per gradient-step
- De search-space is kleiner (~10 reward-weights, niet hele hyperparam-grids)
- Onze fitness-signal is rijker dan win-rate (twee KPI's via gate)

Coordinator-component is een nieuw Python-process op de 4090 dat:
1. Per machine bijhoudt welke `rewards.json`-variant actief is
2. Per N match-cycles fitness samples uit DualKPI-logs aggregeert
3. Exploit/explore beslissingen neemt en `rewards.json` op de target-machines vervangt
4. SAC-restart triggert op vervangen machines (replay buffer policy — zie §7)

---

## 6. Mutation strategie

Per geselecteerd-voor-explore weight: log-normaal multiplicatieve perturbatie.

| Parameter | Default | Toelichting |
| --- | --- | --- |
| Mutation factor distributie | `LogNormal(mu=0, sigma=0.2)` | ~83% binnen factor [0.8, 1.25], ~99% binnen [0.5, 2.0] |
| Per-weight mutation kans | 0.5 | Helft van de weights muteert per exploit-event |
| Bounded range | per-weight (bv. `home_bonus ∈ [0.0, 0.3]`) | Voorkomt absurde escapes; ranges in `pbt_search_space.json` |
| Sign-flip toegestaan | Nee | Een penalty mag geen bonus worden — semantisch onveilig |

Search-space file (`resources/models/rl_pawn/pbt_search_space.json`, nieuw) bepaalt **welke** weights muteren, **per** rewardgroup. Niet alle weights — bewust beperken tot de unkown/contentieuze schalen. Anders convergeert PBT niet binnen budget.

---

## 7. Replay buffer policy bij agent-vervanging

SAC-specifiek probleem. Bij exploit kopieert PBT een winnaars-checkpoint naar een verliezer. De *replay buffer* is daarin niet meegekopieerd. Drie opties:

| Optie | Voor | Tegen |
| --- | --- | --- |
| **A. Behoud verliezer-buffer** | Sample-efficiency, geen vooruit-druk verloren | Off-policy mismatch: buffer-distribution past niet meer bij gekopieerde policy. Distributional drift |
| **B. Gooi verliezer-buffer weg, sample fresh** | Schoon | Sample-verlies — paar uur vooruit-druk weg |
| **C. Kopieer winnaars-buffer mee** | Volledig schoon | Disk/transfer-cost (GB-orde); raakt bestaande `ModelSync.py` (die syncet ONNX, geen NPZ-buffers) |

**Aanbeveling: B.** SAC bootstrap met 20-30 min experience collection is gangbaar (zie `bootstrap.py`). Vergeleken met de gewonnen optimization-progress is dat goedkoop. C is overkill; A introduceert silent bias die juist tegen PBT's exploit-doel ingaat.

Documenteer hard-fail wanneer een buffer in onbekende staat is — geen silent merge (conform CLAUDE.md "no config fallbacks").

---

## 8. Opponent-diversity via historic champions

FTW's diversity-engine was de population zelf: iedere agent zag zijn 29 medebewoners als opponents. Wij hebben symmetrische 5v5-self-play tegen `current champion`. PBT verandert dat **deels** — population members zijn impliciet diverse opponents tijdens de run.

**Extra laag (optioneel):** `ModelWatcher`-opponents rouleren tussen current population + top-3 historic champions (uit `resources/models/rl_pawn/.../history/`). Diversity-boost tegen catastrophic policy-collapse (één dominante stijl die zichzelf bevestigt).

Niet in scope voor eerste PBT-run — eerst kale population-diversity testen. Historic champions zijn een Fase D-addition (zie §12).

---

## 9. Implementatie-impact (high-level)

Geen code in deze notitie. Bestanden die geraakt worden:

### Python-kant (4090, coordinator)

| Bestand | Wijziging |
| --- | --- |
| `train/rl/rl_pawn/pbt/coordinator.py` (nieuw) | Population-state, fitness-aggregator, exploit/explore loop, per-machine `rewards.json` overschrijver via SCP |
| `train/rl/rl_pawn/pbt/search_space.py` (nieuw) | Laadt `pbt_search_space.json`, valideert per-weight bounds, genereert mutaties |
| `train/rl/rl_pawn/pbt/fitness.py` (nieuw) | Leest DualKPI-delta-logs per member, aggregeert over N match-cycles, normaliseert |
| `train/rl/rl_pawn/trainSAC/bootstrap.py` | Optionele `--pbt-member-id` flag; bij set, member-specifieke `rewards.json`-path consumeren |
| `scripts/deploy/pbt.sh` (nieuw) | `pbt start/stop/status` workflow; opt-in trigger i.p.v. automatisch |

### Java-kant

Niets verandert structureel. Java leest `rewards.json` zoals altijd via `JsonRewardCatalog`. De PBT-coordinator overschrijft die file op de target-machine en triggert een SAC-restart.

### Config

| Bestand | Wijziging |
| --- | --- |
| `resources/models/rl_pawn/pbt_search_space.json` (nieuw) | Per-weight bounds + mutation kans + welke rewardgroups |
| `resources/config/deploy.json` | Nieuwe sectie `pbt: { enabled, population_size, mutation_interval_cycles, member_machines: [...] }` |
| `resources/config/servers.json` | Geen wijziging — bestaande `sac_trainer_slots` is de capaciteit |

### Wat **niet** verandert

- `DualKPIDeltaGate` (`feedback_delta_gate_untouched`)
- `MultiHeadSACCritic`-architectuur (Fase 2.5 status-quo blijft basis)
- BC-pretrain pipeline
- Runtime inference path (PBT is alleen training-side)
- Mission/skill/route policy stack

---

## 10. Wat dit NIET probeert te doen

- **FTW's volledige stack importeren.** Geen V-trace, geen pixel-only, geen procedurele map-generation, geen two-timescale recurrent. Alleen de PBT-laag.
- **Continu draaiende background-loop.** Handmatig getriggerd via `scripts/deploy/pbt.sh start`. Tussen runs blijft alles status-quo (`feedback_no_auto_orchestration`).
- **Welke** reward-components actief zijn auto-bepalen. Alleen schalen.
- **De DeltaGate vervangen of bijstellen.** Gate blijft fitness-input.
- **BC-anchor schenden.** PBT muteert RL-side weights, niet BC-targets (`feedback_rl_not_bc_anchor`).
- **Nieuwe metrics-infrastructuur.** Fitness komt uit bestaande DualKPI-logs (`feedback_minimal_metrics`).
- **Champion-promotion logica wijzigen.** PBT-winnaar wordt pas champion via de bestaande DeltaGate AND-promotion — geen shortcut.

---

## 11. Risico's

| Risico | Mitigatie |
| --- | --- |
| Population-grootte (3-5) te klein → noisy meta-gradient | Lange aggregation window (3+ match-cycles per fitness). Stop-criterium: 3 opeenvolgende exploit-events zonder fitness-winst → handmatige interrupt |
| Replay buffer reset bij elke exploit kost samples | Optie A in §7 als noodgreep wanneer sample-budget echt knelt — risico op distributional drift bewust accepteren |
| Mutation factor (sigma=0.2) te conservatief → trage convergence | Schaal op naar sigma=0.3 in volgende run; per-run gelogd in `pbt_audit.jsonl` |
| Search-space override van bestaande champion-weights → policy regression bij start | First-cycle members starten van **dezelfde** champion-checkpoint maar met geperturbeerde weights. Geen weight-reset, alleen reward-vector verschil |
| Coordinator crash mid-run | `pbt_state.json` per cycle op disk gepersistreerd; restart pakt laatste state op |
| `rewards.json` SCP-overschrijven race-conditioned met Java die file leest | Java leest `rewards.json` alleen bij SAC-restart, niet continu. Coordinator overschrijft strikt vóór trigger |
| Exploit kopieert checkpoint dat champion ondergraaft | DualKPI is AND-promotion → fitness kan niet false-positive op één KPI; verliezer-vervanger erft alleen de **weights** van winnaar, champion-status promotie loopt onveranderd via bestaande gate |
| Historic-champion rotation maakt training niet-stationary | Niet in Fase A-C scope; Fase D-opt-in pas na A-C-resultaten |
| `defender_presence`/`cover_escort` schaalmutaties trekken Bear/Hawk over hun rol heen | Bounded range per weight (zie §6) — hard cap voorkomt dat PBT ze tot Attack-prijzen escaleert |

---

## 12. Phased rollout

| Fase | Inhoud | Duur | Validatie |
| --- | --- | --- | --- |
| A | Handmatige PBT-stap: 3 members met handmatig gekozen geperturbeerde weights, één exploit-event aan het eind, geen automated coordinator. Infrastructuur-test. | 1-2 dagen | DualKPI-delta per member; mutation/SCP/restart-pipeline werkt zonder Java-crashes |
| B | Automated coordinator, één PBT-run van 48u, search-space beperkt tot 4 nieuwe weights (`defender_presence × 2`, `cover_escort × 2`). | 2-3 dagen | Convergerende fitness over de run; winnende weight-set beter dan Fase 2.5-baseline op DualKPI |
| C | Search-space uitbreiden naar bestaande contentieuze weights (`flag_event.team_captured` per rol, `objective_progress.progress_scale` per rol). Population 5 als 4090 dubbel-host. | 3-5 dagen | Niet-trivieel betere fitness dan Fase B; geen Movement_flag KPI-regressie |
| D (optioneel) | SAC-hyperparams meenemen in search-space. Historic-champion opponent rotation. | Open | Open |

Fase A is verplicht vóór B start — restart-pipeline-bugs vinden vóór er een meerdaagse run aan vasthangt.

---

## 13. Open keuzes voor de gebruiker

| Keuze | Default | Alternatief |
| --- | --- | --- |
| Population-grootte | 3 | 4-5 als 4090 dubbel-host |
| Mutation distributie | LogNormal(0, 0.2) | Categorical {0.5, 1.0, 2.0} per weight |
| Exploit/explore frequency | Per 3 match-cycles | Per fixed wall-clock (bv. 30 min) |
| Top/bottom-percentile | Top-1 / Bottom-1 (bij N=3) | Top-K voor andere N |
| Buffer-policy bij vervanging | B (gooi weg) | A (behoud) of C (kopieer mee) |
| Search-space scope eerste run | 4 nieuwe weights (Fase 2.5-componenten) | Ook bestaande contentieuze weights (Fase C) |
| Historic-champion rotation | Uit (Fase A-C) | Aan (Fase D) |
| SAC-hypers in search-space | Uit (alle fases tot D) | Aan (Fase D) |
| Wat te doen bij convergence-plateau | Handmatige interrupt + nieuwe search-space | Auto-stop bij N exploit-events zonder fitness-winst |

---

## 14. Referenties

- Jaderberg et al. 2017 — *Population Based Training of Neural Networks*, arXiv:1711.09846 (PBT-origineel, hyperparameter-meta-optimization)
- Jaderberg et al. 2019 — *Human-level performance in 3D multiplayer games with population-based reinforcement learning*, Science 364 (FTW; PBT + IMPALA + interne reward-weights als meta-target — primaire inspiratie voor dit document)
- [team-coordination-rollout.md](team-coordination-rollout.md) — voorganger; PBT triggert wanneer Fase 2.5 weight-bottleneck zich manifesteert
- [team-coordination.md](team-coordination.md) — onderliggende CTDE design-notitie (PBT is orthogonaal: tunet weights *van* de bestaande critic-architectuur, niet de architectuur zelf)
- [../rewards/reward-architecture.md](../rewards/reward-architecture.md) — reward-pipeline (waarbinnen PBT-getunde weights leven)
- [../rewards/training-parameters.md](../rewards/training-parameters.md) — bestaande hyperparams als beginpunt voor search-space bounds
