# Joint Reward Architecture

Beschrijft het reward-systeem voor het joint `rl_pawn` model: hoe rewards berekend worden, hoe ze gedecomposeerd worden naar per-head kanalen, en hoe alles geconfigureerd wordt.

**Gerelateerde documenten:**

- [sparse-events.md](sparse-events.md) -- Sparse event rewards (flag, frag, death, pickup)
- [training-parameters.md](training-parameters.md) -- SAC/BC hyperparameters, config keys

---

## Een model, per-head decompositie

Het joint `rl_pawn` model output movement + view + fire acties samen. De reward computer berekent een scalar reward (alle componenten gesommeerd) plus een per-head decompositie:

| Head-kanaal | Bron-componenten |
|---|---|
| `reward_movement` | pickup_event, flag_event, alive_bonus, objective_progress, speed, facing, enemy_spacing, collision, stuck, dodge, idle_urgency, exposed_idle, void_avoidance, score_gain_rate, flak_avoidance |
| `reward_view` | view_alignment + acquisition, spawn_attention, 1/2 x view_smoothness |
| `reward_pitch` | pitch_alignment, 1/2 x view_smoothness |
| `reward_fire` | shot_on/off_target, enemy_killed_by_fire, primary_fire_aim, projectile_aim, shock_combo, frag, flag_carrier_kill, fire_holding_penalty, fire_penalty, fire_cooldown_penalty, damage_dealt, self_damage, friendly_fire, headshot, ammo_consumption_penalty |
| `reward_altFire` | shot_on/off_target_alt, fire_holding_penalty_alt |
| `reward_team_assist` | team_assist |
| `residual` | death, damage_taken |

**Invariant:** `scalar = movement + view + pitch + fire + altFire + team_assist + residual`. Per-head kolommen worden naast de scalar in de NPZ-batches geschreven voor multi-head Q-decompositie in de SAC-trainer.

---

## Reward Pipeline

```
Per tick (30 Hz):
  prev GameStateDto + curr GameStateDto + prevAction
      |
      v
  RewardComputer.computeWithBreakdown(prev, curr, action)
      |
      v
  RewardBreakdown
      |
      v
  JointRewardDecompositionStrategy
      |
      v
  RewardDecomposition: movement / view / pitch / fire / altFire / team_assist / residual + scalar
      |
      v
  ExperienceCollector.recordJoint
      |
      v
  rl-replay-buffer/rl_pawn/batch_*.npz
      |
      v
  Python SAC trainer
```

De recorder buffert een tick en schrijft de transitie `(state, action, reward, next_state, done)` + per-head decomp + target label op de volgende tick. Bij `done` (health <= 0) wordt de buffer gereset.

---

## Reward-code structuur (per-reward module)

Elke reward is een **vertical slice**: één sub-package `rewards/<skill>/<reward>/` met drie files die samen één reward volledig beschrijven.

| File | Rol |
|---|---|
| `<Name>Params` | Typed `RewardBlock`-record -- de geparsede weights/thresholds + `metadata()` + `enabled()`. |
| `<Name>Reward` | `RewardComponent` -- `compute(RewardContext)` (+ vaak `computeDetailed()` met een `Result`-record voor de breakdown). |
| `<Name>Module` | `RewardModule<Params>` -- bindt de drie samen: `id()`, `parse(support, block)`, `create(ctx)`. |

De SPI:

```java
public interface RewardModule<P extends RewardBlock> {
  RewardId id();                                         // stabiele id + rewards.json-key
  P parse(RewardParseSupport support, JsonNode block);   // block -> typed Params
  RewardComponent create(RewardComponentContext ctx);    // Params -> Reward
}
```

- **`parse`** leest het (reeds rewardgroup-merged) block via `RewardParseSupport`: strikte, uniform ge-prefixte validatie (`requireDouble/Int/Boolean/String`, `requireWeights`, `metadata`), geen fallbacks.
- **`create`** haalt de eigen typed Params uit `ctx.catalog()` (type-safe, geen cast) plus cross-reward deps: `ctx.catalog().endgameUrgency()` voor de team-rewards, `ctx.modelKey()` voor `MovementAction`'s `bIdle`-index.

### Orchestratie

- **`RewardModules`** (in `catalog/`) ontdekt alle 27 modules via classpath-scanning: elke class met `@RewardModuleComponent` wordt automatisch opgepikt -- zelfde zelf-registrerende patroon als `@TrainingFeatureComponent` voor de feature-resolvers. De volgorde komt uit `RewardId.ordinal()` (de enum is de single source of truth), niet uit de niet-deterministische scan-volgorde. Een static-init guard crasht bij class-load als een `RewardId` geen of een dubbele module heeft.
- **`JsonRewardCatalog`** is enkel orchestrator: rewardgroup-merge -> loop over `RewardModules.all()` -> `EnumMap<RewardId, RewardBlock>` -> typed-accessor façade. De per-reward parse-logica zit hier niet meer.
- **`RewardComputer`** bouwt de componenten via dezelfde registry (`module.create(ctx)`) in een `EnumMap`; de enum-volgorde garandeert deterministische reward-sommatie.

### Reward-signaal catalog (uitkomst-laag)

Waar `RewardId` (27 componenten) de *registratie* draagt, draagt **`RewardSignal`** (58 signalen, in `java-rewards` `core/`) de *uitkomst*: één benoemde scalar-bijdrage per tick. Eén component levert via zijn `Result` vaak meerdere signalen (bv. `CombatEventReward` → frag, death, shot-on/off, …). `RewardSignal` is de single source of truth voor de uitkomst-laag:

- **`RewardBreakdown`** is een `double[]` geïndexeerd op `RewardSignal.ordinal()`, gevuld via een keyed `Builder` (`builder.set(SIGNAL, value)`) — geen positionele constructie of per-veld accessors meer. De declaratievolgorde is contract (array-index); voeg nieuwe signalen achteraan toe.
- **Decompositie-routing** leeft in `java-aiplay` (`JointRewardDecompositionStrategy`): een tabel `RewardSignal → (RewardChannel, JointWeightKey, factor)` met een volledigheidsguard die bij class-load crasht als een signaal geen routing heeft. De skill-kanalen (`RewardChannel`: movement/view/pitch/fire/altFire/team_assist/residual) en de `JointRewardWeights`-multipliers horen hier, niet bij de signalen — zo blijft de module-DAG (java-aiplay → java-rewards) acyclisch.
- **`RewardCategory`** (sparse/dense/action) hangt aan elk signaal voor de logging-subtotalen.

De vijf consumenten — per-head decompositie, de window-log in `PerModelExperienceRecorder`, de categorie-subtotalen en de offline diagnostics in `GenerateExperienceFromRecordingsMain` — itereren allemaal over de catalog. Voorheen herhaalde elk van die plekken de signaal-lijst handmatig; ze dreven uiteen (o.a. `teamAssist` telde nergens mee in de window-log).

### Uitzondering: EndgameUrgency

`EndgameUrgencyParams` valt buiten dit patroon -- geen `RewardId`, geen `RewardBlock`, geen eigen Reward. Het is een **top-level** `rewards.json`-knop (buiten `rewardgroups`), apart geparsed door `JsonRewardCatalog` en gedeeld door de 3 team-rewards.

### Een reward toevoegen

1. Maak `rewards/<skill>/<reward>/` met `<Name>Params` (record `implements RewardBlock`), `<Name>Reward` (`implements RewardComponent`) en `<Name>Module` (`implements RewardModule<<Name>Params>`).
2. Voeg de `RewardId`-constante toe.
3. Annoteer de module met `@RewardModuleComponent` -- de scanner pikt haar automatisch op (geen handmatige registratie in `RewardModules` meer).
4. Voeg de typed accessor toe aan `RewardCatalog` + `JsonRewardCatalog`.
5. Per nieuw breakdown-signaal: voeg een `RewardSignal`-constante toe (achteraan, met `RewardCategory`) plus een routing-regel in `JointRewardDecompositionStrategy.buildRouting()` (skill-kanaal + gewicht-sleutel). De volledigheidsguard crasht bij class-load als de routing ontbreekt.
6. Laat `RewardComputer.computeWithBreakdown` het signaal vullen via `builder.set(RewardSignal.X, ...)`.
7. Voeg het block toe aan `rewardgroups.default.rewards` in `rewards.json`.

De read-side volgt automatisch: per-head decompositie, window-log, sparse/dense/action-subtotalen en de offline diagnostics itereren over de `RewardSignal`-catalog — geen handmatige synchronisatie meer (zie [Reward-signaal catalog](#reward-signaal-catalog-uitkomst-laag)).

---

## Rewardgroups

`resources/models/rl_pawn/rewards.json` gebruikt `rewardgroups`. `rewardgroups.default` bevat de gedeelde reward-weights. Elke benoemde groep (`rewardgroup0`, `rewardgroup1`, ...) is een feature-id; de declaratievolgorde is de featurevolgorde.

Een bot selecteert groepen via `gameplay.json ai_bots[].rewardgroups`. Die features worden multi-hot gezet. Reward properties worden in opgegeven volgorde gemerged: eerste groep wint, latere groepen vullen ontbrekende properties aan, `default` vult de rest.

```json
{
  "rewardgroups": {
    "default": {
      "frag": 5.0
    },
    "rewardgroup0": {
      "name": "Attack",
      "frag": 6.0
    },
    "rewardgroup1": {
      "name": "Aggressive",
      "death": -1.0
    }
  }
}
```

Met `"rewardgroups": ["Attack", "Aggressive"]` krijgt de bot `frag = 6.0` uit Attack, `death = -1.0` uit Aggressive, en overige rewards uit `default`.

---

## Reward Decompositie per Head

Het model bevat een policy met drie action-heads (movement / viewrotation / fire). Voor multi-head Q-learning wordt de scalar reward gedecomposeerd -- elke head krijgt credit voor gedrag dat het zelf bestuurt. De policy is een netwerk; alleen het critic-doel verschilt per head.

| Gedrag | Movement-head | View-head | Fire-head |
|---|---|---|---|
| Naar objective bewegen | Primary | -- | -- |
| Enemy op afstand houden | Secundair | -- | -- |
| View op target richten | -- | Primary | -- |
| Schieten op aligned enemy | -- | -- | Primary |
| Schieten naast enemy | -- | -- | Penalty |
| Enemy doden | Kleine frag | Kleine frag | Frag + kill-by-fire |
| Vlag returnen | Primary (prio 1) | Indirect via target | -- |

---

## PBRS-classificatie

Potential-based reward shaping (PBRS) heeft de vorm `F(s,a,s') = gamma * Phi(s') - Phi(s)`. Dit is de enige vorm van reward-shaping die gegarandeerd de optimale policy ongemoeid laat.

### Categorieen

| Categorie | Betekenis |
|---|---|
| PBRS / Quasi-PBRS | Delta-vorm Phi(s')-Phi(s). Quasi = geen gamma-factor of target uit curr. |
| Task | Direct op echte game-metric (score, HP, flag-events). |
| Regularizer | Actie-gebonden constraint (smoothness, fire-cooldown, collision). |
| Heuristic | State-conditionele bonus zonder verschil-vorm. Verandert optimale policy. |

### Componenten per categorie

**Quasi-PBRS:**

| Component | Vorm |
|---|---|
| Pitch acquisition | `bonus * (prevErr - currErr)` |
| View alignment acquisition | `bonus * (dotCurr - dotPrev)` op gedeeld lead target |
| Objective progress | `scale(curr) * clamp(prevDist - currDist, +/-50 UU)` |
| Enemy spacing delta | `(currDist - prevDist)` of omgekeerd, conditioneel buiten ideal band |
| Void avoidance (edge exposure) | `-scale * (exposure(curr) - exposure(prev))`, exposure = fractie van de 8 floor-delta-sectoren met een val-grade drop (anticipatieve val-vermijding; 0 op void-vrije maps) |

De afstanden in objective progress (en de EFC-threat-delta) zijn **geodesisch** (langs de
beloopbare ruimte) op maps met `"geodesic_field": true` in de per-map JSON; anders euclidisch.
Zie [geodesic-distance-field.md](geodesic-distance-field.md) — lost het lokale-optimum-probleem
van vogelvlucht-afstand op bij gangen/obstakels (eerst-weg-van-het-doel routes).

**Task:**

| Component | Vorm |
|---|---|
| Combat events (frag, death, kill-by-fire) | Sparse score/death-delta |
| Flag events (alle 6) | Sparse state-transities |
| Flag carrier kill | Sparse compound event |
| Damage delta (dealt, taken, self, friendly) | Per-tick HP-delta met engine-attributie |
| Score gain rate | Rolling score-rate |

**Regularizer:**

| Component | Vorm |
|---|---|
| Collision / stuck / floorDrop | Actie x state |
| Idle urgency | Conditioneel per-tick (alleen carrier/recover) |
| View smoothness (smoothness, oscillation, postFire) | Actie-magnitude |
| Fire holding penalty | Sustained off-target fire |
| Fire penalty / cooldown penalty | Per-edge onset |
| Pitch extreme penalty | State-based excess |

**Heuristic:**

| Component | Vorm |
|---|---|
| Alive bonus | Per-tick constante |
| Pitch alignment | State-cost |
| View alignment | Dot-product bonus |
| Facing | `bonus * max(0, dot)` |
| Speed | `scale * speed`, cap 20 UU/tick |
| Spawn attention | State-based dot (alleen no-live-enemy frames) |
| Enemy spacing (tooClose/ideal) | State-based bonus/penalty |
| Primary fire aim | Sustained aim score |
| Projectile aim | `exp(-minDist/sigma) * rangeFactor` |
| Endgame attack bonus | `weight * urgency * botDepthInEnemyHalf` |

### Ontwerpregels

| Regel | Toepassing |
|---|---|
| Nieuwe dense shaping -> PBRS-vorm | Formuleer als Phi(s') - Phi(s) met dezelfde Phi-functie. |
| Heuristic rewards klein houden | Ze verschuiven de optimale policy. Laat task-rewards domineren. |
| DeltaGate naast non-PBRS | PBRS voorkomt cycles; niet alle Goodhart-paden. |

---

## Endgame Catchup Modulator

Wanneer een team in de slotfase achter staat, schakelen Defend/Cover-bots tijdelijk van campen/escorteren naar mee-aanvallen.

### Urgency-formule

```
urgency = behindIndicator x clamp01((rampStart - r) / (rampStart - rampFull))
  r               = remaining_time_norm
  behindIndicator = achterstand ? 1.0 : 0.0
  rampStart       = 0.20 (laatste 20% van de match)
  rampFull        = 0.05 (laatste 5%)
```

| Situatie | Urgency |
|---|---|
| Team staat gelijk of voor | 0 |
| Meer dan 20% resttijd | 0 |
| Laatste 5% + achterstand | 1.0 |
| Lineair daartussen | 0 -- 1 |

### Effect op rewards

| Reward | Bij urgency = 1 |
|---|---|
| Endgame attack bonus | Defender/Cover op enemy base krijgt positieve pull |
| Defender presence | Camping-signaal naar 0 (x (1 - urgency)) |
| Cover escort | Escort-signaal naar 0 (x (1 - urgency)) |

Attack-rol wordt niet geraakt (endgame bonus = 0). De `cover_escort.berth_penalty`
(capture-funnel, zie
[mission-architecture.md](../policy/mission-architecture.md#escort-standoff--capture-funnel-release-de-teamgenoot-blokkeert-de-capture-niet-2026-06-06))
wordt bewust níét gemoduleerd — anti-blokkade-correctie, geen escort-shaping.

### Config

```json
// resources/models/rl_pawn/rewards.json
"endgame_urgency": {
  "ramp_start_remaining_norm": 0.20,
  "ramp_full_remaining_norm": 0.05
}

// per rewardgroup
"endgame_attack_bonus": 0.0    // default + Attack
"endgame_attack_bonus": 0.5    // Cover
"endgame_attack_bonus": 0.7    // Defend
```

De policy ziet `remaining_time_norm`, `score_diff_norm` en `match_phase_late` als features, waardoor de reward-shift als correlerend signaal binnenkomt.

---

## Dense vs Sparse Balans

Bij 30 Hz tellen dense rewards snel op:

```
200 ticks x 0.05 reward/tick = 10.0 reward (20% van flag_captured = 50.0)
```

Dense subcomponenten klein houden. Sparse events (capture, frag) moeten zichtbaar blijven in de value function.

| Schaal | Typisch bereik |
|---|---|
| Flag capture (sparse) | +50.0 |
| Flag taken/return (sparse) | +5.0 |
| Objective progress (dense, per tick) | 0.03-0.06 x delta |
| Enemy spacing penalty (dense, per tick) | -0.05 |
| Shot on-target (sparse, per schot) | +0.35 |

---

## Ontwerpregels

| Regel | Rationale |
|---|---|
| Movement target is nooit "enemy" | Voorkomt blind chase |
| Viewrotation mag enemy-targeted zijn | Aim is taak van viewrotation |
| Shooting krijgt only-on-shot-onset reward | Voorkomt credit voor aim-werk van viewrotation |
| Enemy spacing is secundair | CTF objectives blijven leidend |
| Dropped own flag heeft topprioriteit | Returnen is vaak belangrijker dan doorgaan |
| Both-carrier: movement != viewrotation | Movement naar huis, viewrotation naar carrier |
| Dense scales klein houden | Voorkomt overschaduwen van sparse CTF events |
| Kill-by-fire via state-delta | Directe attributie via state-vergelijking |
| Flag drop-afstand via 2D | Verticale offsets mogen near-home niet inflaten |
| Closest enemy via werkelijke afstand | Slot-volgorde heeft hysteresis |
