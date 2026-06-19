# Route-cost Φ Design Notitie

**Status:** DRAFT — design-notitie. Niet geïmplementeerd. Input voor toekomstige refactor van `ObjectiveProgressReward` naar strikt PBRS volgens Ng/Harada/Russell (ICML 1999).

**Update 2026-06-10:** de afstandsfunctie `D(a,b)` in dit ontwerp kan nu geodesisch (langs de
beloopbare ruimte) via `RouteDistances`/`GeodesicField` — zie
[../rewards/geodesic-distance-field.md](../rewards/geodesic-distance-field.md). Dat lost het
orthogonale gang-topologie-probleem op (misleidende euclidische gradient); dit document blijft
over subgoal-transities (pickup-dips, capture-reset) gaan.

## Achtergrond

Op 2026-05-06 is een naive strikt PBRS-poging in `ObjectiveProgressReward` geprobeerd en gerevert wegens twee training-relevante artefacten met `Φ(s) = -dist(self(s), resolveObjective(s)) · scale(s)`:

1. **Stationary baseline shift** — `F = scale · d · (1 − γ)`. Met scale=0.05, γ=0.99, d=5000 UU: +2.5 reward per tick puur door stilstaan ver van target. Over een 30 s episode bij 15 Hz: ~+1125 returns-shift; sparse events (flag_captured=25, frag=5) verdwijnen onder de baseline. Theoretisch policy-equivalent (Corollary 2 uit de paper: V'=V−Φ), praktisch een grote critic-baseline shift.
2. **Subgoal Φ-dip** — bij flag-pickup switcht het objective van enemy_flag (dist≈0) naar home_base (dist≈D). Φ springt van 0 naar -D·scale → eenmalig `F ≈ -γ·D·scale ≈ -297` op exact de tick waarop FlagEventReward.taken (+5..+10) een gewenste subgoal beloont. Sterk negatief net-signaal precies wanneer de bot het juiste deed.

Beide problemen ontstaan omdat naive Φ alleen de **huidige** subgoal-afstand modelleert, niet de **totale resterende route-cost**. De paper §4 gebruikt expliciet `Φ ∝ −(remaining_subgoals/N)·t` om subgoal-completion een Φ-stijging te geven.

## Reconcile (uitgevoerd 2026-05-06)

Tot 2026-05-06 stond [reward-architecture.md](../rewards/reward-architecture.md) op een eerdere ontwerp-versie van de priorities (capture-push P2 naar home base, single-carrier P3, distance-norm voor recovery), terwijl `RewardUtils.resolveMovementPrimaryObjective` (RewardUtils.java:135-181) reeds was geherstructureerd naar: P1/P2 = recovery via halfveld-test, P3 = dual-flag standoff. De doc is op 2026-05-06 gereconciliëerd naar de code (priority-tabel, mermaid en Both-Carrier Standoff sectie). Code is authoritative voor priority-logica; design hieronder volgt die definitie.

## Voorstel: route-cost Φ

Definieer Φ als negatieve totale resterende route-cost tot capture/return-completion, niet huidige-subgoal-afstand. Notatie: `D(a,b)` = afstand UU; `OF` = own flag location; `EF` = enemy flag location; `OB` = own base; `EB` = enemy base.

| Priority | Toestand | route_cost(s) | Volgt na completion |
|---:|---|---|---|
| 4 | Bot zonder enemy flag, normal attack | `D(self, EF) + D(EF, OB)` | → P5 |
| 5 | Bot met enemy flag, carrier to home | `D(self, OB)` | → P4 (capture, EF respawnt) |
| 1 | Own flag dropped, recovery | `D(self, OF) + D(OF, OB)` | → P4 (na return) |
| 3 | Both-carrier (code: target=OF=enemy-carrier-pos) | `D(self, OF) + D(OF, OB)` | → P1 of P4 afhankelijk |

Definitie: `Φ(s) = -route_cost(s) · scale(s)`. Reward `F = γ·Φ(curr) − Φ(prev)`.

## Per-case spike-analyse

Aanname: γ = 0.99, scale = 0.05 (Attack), D(EB, OB) = 6000 UU. Self-positie geconcretiseerd per case voor numerieke checks.

| # | Case | F naive | F stage-aware | Effect |
|---:|---|---:|---:|---|
| 1 | Normal attack progress (P4, Δdist = -20 UU) | +3.49 | +6.49 | Vergelijkbaar; baseline iets groter wegens grotere route_cost. |
| 2 | **Enemy flag pickup** (P4 → P5, self ≈ EF) | **-297.0** | **+3.0** | Stage-aware lost de pickup-dip op (route_cost identiek voor/na). |
| 3 | Carrier progress to home (P5, Δdist = -20 UU) | +3.99 | +3.99 | Identiek (P5 route_cost = P5 dist). |
| 4 | Own flag dropped (P4 → P1) | varieert (+, doordat OF dichterbij is dan EF) | varieert (afhankelijk van OF-locatie) | Beide geven Φ-jump; stage-aware niet noodzakelijk schoon. |
| 5 | Own flag return (P1 → P4, self ≈ OF, OF returned) | -99 | -196 | Stage-aware verergert; route-cost springt van klein recovery-rest naar grote attack-cycle. |
| 6 | Both-carrier entry (P4 → P3, enemy pakt onze vlag op) | -98 (concreet voorbeeld) | +202 (concreet voorbeeld) | Hangt sterk af van enemy-carrier locatie; geen schoon zero-spike. |
| 7 | **Capture/reset** (P5 → P4, EF respawnt at EB, self ≈ OB) | **-297** | **-594** | Stage-aware verergert; capture-cycle reset → 2× route-cost. |

**Inzichten:**

- Case 2 (de oorspronkelijke motivatie) wordt opgelost.
- Case 7 (capture/reset) wordt erger met stage-aware (route-cost springt 2× zoveel). FlagEventReward.captured (Attack: +25) compenseert deels niet voldoende.
- Cases 4-5-6 (recovery, return, standoff entry) blijven Φ-mismatches tussen voor/na priority — geen schone fix mogelijk zonder verdere subgoal-decompositie.
- **Stationary baseline groeit** met stage-aware: route_cost ≈ 2× dist → F_idle ≈ 2× naive (baseline ~+5/tick i.p.v. +2.5).

## Trade-offs

| Aspect | Naive (huidige Quasi-PBRS) | Stage-aware (route-cost) |
|---|---|---|
| Pickup-spike (case 2) | -297 ❌ | +3 ✓ |
| Capture-reset spike (case 7) | -297 | -594 (slechter) |
| Stationary baseline | +2.5/tick | +5/tick (groter) |
| Recovery/return transitions | Φ-mengsel | Φ-mengsel (geen verbetering) |
| Implementation complexity | Trivial (zit er al) | 4 priority-cases × per-case route-formule + edge-case handling |
| Theorem 1 invariance | Quasi (clamp breekt) | Strict |

## Open issues

1. **Capture-reset Φ-jump**: hoe te framen? Terminal-style (Φ_capture = 0 + accept Φ-spike) vs continuïteit (post-capture als P4 met initial route_cost). Beide maken een keuze die FlagEventReward.captured-magnitude beïnvloedt.
2. **Scale-tuning na route-cost adoptie**: huidige `progress_scale` (0.01..0.05) afgestemd op naive Δdist-magnitudes. Bij route-cost (×2 magnitude) moet scale ~halveren om sparse events niet te overschaduwen. Per rewardgroup separately tunen.
3. **Multi-bot coordinatie**: in self-play 2v2/4v4 kan een teammate de carrier-rol oppakken (`teammateCarrier_proximity_norm`). Φ moet dan teammate-state encoderen (escort vs intercept). Buiten scope van eenvoudige route-cost.
4. **Empirische validatie**: na implementatie 1 SAC-generation runnen met Φ + transition-events gelogd, anomalous spikes detecteren in andere dan de hier-verwachte plekken.

## Backlog implementatie-stappen

In volgorde, na akkoord op design:

1. Beslissing: capture-reset-handling (terminal Φ=0 vs route-cost continuïteit).
2. Beslissing: scale-tuning per rewardgroup (Attack/Cover/Defend/DeathMatch).
3. Refactor `ObjectiveProgressReward.computePhi(state)` om route_cost te gebruiken i.p.v. single-target distance. Verwijder ±50 UU clamp (overbodig met juiste Φ).
4. Empirische spike-validatie: log Φ + transition events in 1 SAC-generation, check anomalies.
5. Doc-tabel-update in [reward-architecture.md](../rewards/reward-architecture.md) classificatie naar PBRS (strict) na empirische groen.

## Referenties

- Ng, A. Y., Harada, D., & Russell, S. (1999). *Policy invariance under reward transformations: Theory and application to reward shaping*. ICML 1999. — Theorem 1 (necessity & sufficiency); §4 (subgoal-based heuristics, gridworld experiments).
- [reward-architecture.md](../rewards/reward-architecture.md) — PBRS-classificatie tabel (sectie "PBRS-classificatie").
- [reward-architecture.md](../rewards/reward-architecture.md) — `objective_progress` reward + 5-level priority systeem.
- `java-aiplay/src/main/java/aiplay/rl/rewards/RewardUtils.java:135-205` — `resolveMovementPrimaryObjective` + `isOwnFlagReturnPriority`.
- `java-aiplay/src/main/java/aiplay/rl/rewards/ObjectiveProgressReward.java` — huidige Quasi-PBRS implementatie.
