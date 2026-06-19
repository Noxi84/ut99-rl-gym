# Mission Architecture

## Overzicht

De mission-laag bepaalt het strategische doel van elke bot. Dit doel wordt als feature aan het joint `rl_pawn` model gegeven, samen met engagement- en tactical-informatie. Het model beslist vervolgens alle motoracties (movement + viewrotation + shooting).

```
+---------------------------------------------------------------------+
|                       HIERARCHIE                                     |
|                                                                      |
|  +---------------------------------------------------------------+  |
|  | MISSION  "wat is het doel?"                                    |  |
|  |                                                                |  |
|  |  CAPTURE_FLAG -- RETURN_HOME -- STUCK_RECOVER -- INTERCEPT    |  |
|  +-----------------------------+----------------------------------+  |
|                                |                                     |
|  +-----------------------------v----------------------------------+  |
|  | ENGAGEMENT (orthogonaal)  TACTICAL (orthogonaal)               |  |
|  |                                                                |  |
|  |  IGNORE/TRACK/COMMIT_SHOT   CARRIER_SHADOW_DENY /             |  |
|  |  /BREAK/PRESSURE_CARRIER    MIDFIELD_FALLBACK / DENY / NONE   |  |
|  +-----------------------------+----------------------------------+  |
|                                |                                     |
|  +-----------------------------v----------------------------------+  |
|  | POLICY (low-level)  "welke actie voer ik uit?"                 |  |
|  |                                                                |  |
|  |  rl_pawn (joint) -- movement + yaw/pitch + fire/altFire       |  |
|  |  + target_index aux head                                      |  |
|  +-----------------------------+----------------------------------+  |
|                                |                                     |
|  +-----------------------------v----------------------------------+  |
|  | COMMANDCONTROLLER (60 Hz)  "stuur het commando"                |  |
|  |  + MovementConstraintApplier (tactical clamp post-inference)   |  |
|  +----------------------------------------------------------------+  |
+----------------------------------------------------------------------+
```

---

## Stack

| Laag | Rol |
|---|---|
| Mission | Bepaalt het hoge doel (feature-input naar model) |
| Engagement | Tactische posture en attention target (feature-input naar model) |
| Tactical | Spatial constraint op movement (clamp post-inference, niet via features) |
| Policy | `rl_pawn` LSTM produceert alle actie-outputs |
| Controller | Eigenaar van gecombineerde command-state, past tactical clamp toe |

### Kernprincipe

```
Mission -> Engagement/Tactical -> rl_pawn policy -> CommandController (incl. clamp)
```

- **Mission** zegt wat het doel is -- wordt als feature aan het model gegeven
- **Engagement** bepaalt hoe de bot zich tot enemies verhoudt -- wordt als feature gegeven
- **Tactical** bewaakt de no-pass boundary -- clamps movement post-inference
- **Policy** beslist alle motoractie (movement + viewrotation + shooting)

---

## Missions

| Mission | Wanneer actief | Reden |
|---|---|---|
| `CAPTURE_FLAG` | Default voor Attack/Cover -- geen flag, niet stuck, geen enemy-carrier | `DEFAULT_CAPTURE` |
| `RETURN_HOME` | Bot draagt de flag, of Defender bij idle (guard own base) | `HAS_FLAG` of `DEFAULT_CAPTURE` |
| `STUCK_RECOVER` | Stuck-detectie triggert | `STUCK_DETECTED` |
| `INTERCEPT_CARRIER` | Enemy heeft onze vlag | `ENEMY_HAS_FLAG` |

### Rol-bewuste mission policy

| Rol | Idle-gedrag | Enemy heeft onze vlag |
|---|---|---|
| Defend | `RETURN_HOME` (guard own base) | Altijd `INTERCEPT_CARRIER` |
| Attack / Cover | `CAPTURE_FLAG` (rush enemy flag) | `INTERCEPT_CARRIER` mits carrier waargenomen |
| Alle rollen | Draagt flag: `RETURN_HOME` | Stuck: `STUCK_RECOVER` |
| **Counter-grabber** (rol-blind) | n.v.t. | `CAPTURE_FLAG` — dichtste bot bij enemy-vlag pakt die (defensive grab) i.p.v. mee te jagen |

### Counter-grab split (enemy heeft onze vlag, wij die van hen nog niet)

Zonder coördinatie sturen mission + navTarget + reward *alle* bots naar de EFC — niemand pakt de
enemy-vlag, dus de EFC krijgt vrije capture-passage (capture is sowieso geblokkeerd tot onze vlag
terug is). [`CounterGrabResolver`](../../java-model/src/main/java/aiplay/shared/objective/CounterGrabResolver.java)
lost dit decentraal op: de bot die het dichtst bij de enemy-vlag staat (positie-only tie-break met
distance-bucket + naam, rol-blind) pakt die vlag → blokkeert ook *hún* capture → flag standoff. De
overige bots onderscheppen de EFC op een **equal-speed cut-off punt** richting de enemy base i.p.v.
de huidige EFC-positie achterna te lopen. Zodra de grab landt (`ownTeamHasEnemyFlag`) vervalt de
split en geldt de bestaande dual-flag intercept-logica. Eén resolver, aangeroepen door mission,
navTarget-feature én `objective_progress`-reward — geen drift.

---

## Annotator Pipeline

De mission-annotator draait op 5 Hz en deelt dezelfde logica tussen runtime en offline CSV-labeling.

```
GameStateDto
  |
  +-->  StuckDetector.isStuck()
  |       |
  |       v
  +-->  WorldFacts.derive(frame, isStuck)
          |
          v
   +---------------------------------------------+
   | MissionPolicy.evaluate(facts)                |
   |   hasFlag=true           -> RETURN_HOME      |
   |   isStuck=true           -> STUCK_RECOVER    |
   |   enemyHasOurFlag + cond -> INTERCEPT_CARRIER|
   |   anders                 -> CAPTURE_FLAG     |
   +-------------------+-------------------------+
                       |
   +-------------------v-------------------------+
   | EngagementPolicy.evaluate(facts, mission)    |
   |   role-aware attention prior                 |
   |   engagement + dwell hysteresis              |
   +-------------------+-------------------------+
                       |
   +-------------------v-------------------------+
   | TacticalPolicy.evaluate(facts, mission)      |
   |   carrier-shadow / midfield-fallback / NONE  |
   |   grace period op carrier-positie            |
   +-------------------+-------------------------+
                       v
   Result(MissionIntent, EngagementIntent, TacticalIntent)
```

---

## Attention prior (wie kijkt de bot aan)

`EngagementPolicy.pickAttentionPrior` kiest het `AttentionTargetType`; `AimTargetSelector`
(java-model `shared.engagement`, via de dunne `AimTargetEnricher` pipeline-adapter) zet dat type om
naar de concrete `annotatedAimEnemy` en voedt zo de aux target-head, de `aim_target_index_onehot_*`
feature én de aim-reward. Prioriteit (eerste match wint):

| Prioriteit | Conditie | Attention target | Reden |
|---|---|---|---|
| **0** | **Bot draagt zelf de vlag** (rol-blind) | `ENEMY_THREAT_TO_SELF` | Carrier-survival: kijk naar wie *jou* aanvalt (schiet / kijkt naar je / nadert), niet naar de mede-EFC die je vlag draagt. De vluchtende EFC wordt genegeerd tenzij hij actief op je schiet (acute tier). Geen threat → null → objective (naar huis kijken). |
| **1** | **Enemy heeft onze vlag** (rol-blind), bot recovert | `ENEMY_CARRIER` | Kijk naar de EFC die je gaat onderscheppen — niet naar een rol-prior die je view aan een verre teamgenoot koppelt. Sub-override: word je *acuut* aangevallen (closest enemy dichtbij + schiet/facing) → `ENEMY_THREAT_TO_SELF`. |
| 2 | Defend, geen flag in play | `ENEMY_NEAREST_TO_HOME_FLAG` | Pre-aim op meest waarschijnlijke aanvaller |
| 3 | Cover, geen flag in play | `ENEMY_NEAREST_TO_ATTACKER` | Volg de threat die de Attacker gaat engagen |
| 4 | Attack / overig | `ENEMY_PLAYER` | Sticky-closest (Attack = hard-pin tot death) |

De prior is geprioriteerd op urgentie (survival > recover-objective > rol-default) zodat de bot
altijd kijkt naar wat relevant is voor zijn *eigen* positie, niet naar een rol-aanname die positie
negeert. Twee opgeloste bugs:

- **Prio 0** (carrier-survival): in de dual-flag standoff zette een flag-carrier zijn viewrotation op
  de mede-EFC in de eigen base i.p.v. op de recoverer die hem probeerde te killen. Consistent met de
  `FLAG_CARRIER_EVASION` engagement (break-contact terwijl de carrier zijn aanvaller in het oog houdt
  tot een teamgenoot de vlag returnt). Rangschikking `AimTargetSelector#pickMostThreateningToSelf`:
  acute aanvaller (zichtbaar + schiet/facing) > zichtbaar > schiet/facing buiten zicht; ties op nabijheid.
- **Prio 1** (recover, rol-blind): een recoverer die naar de enemy base liep om de EFC te onderscheppen
  keek met zijn viewrotation naar een verre enemy aan de *andere* kant van de map — de enemy naast onze
  flag-carrier in de eigen base (Cover's `ENEMY_NEAREST_TO_ATTACKER` koppelt aim aan de verre Attacker;
  een Attacker bleef hard-gepind op een oude target). Nu rol-blind `ENEMY_CARRIER` → aim volgt de
  INTERCEPT/counter-grab nav (beide richting enemy base). Geldt voor zowel de interceptor
  (`INTERCEPT_CARRIER`) als de counter-grabber (`CAPTURE_FLAG`) — beide hebben `enemyTeamHasOurFlag`.

---

## Frame-annotatie

| Annotatie | Bron | Consumer |
|---|---|---|
| Mission | Mission policy | CSV state-bucketing |
| Engagement | Engagement policy | CSV state-bucketing |
| Attention target | Engagement policy | target_index aux-head, cross-feed features |
| Tactical facts | Tactical policy | MovementConstraintApplier (direct via intent bus) |

### Model-features uit deze laag

| Model | Features | Aantal |
|---|---|---|
| `rl_pawn` | `target_index_onehot_*`, `aim_target_index_onehot_*` (engagement-driven attention) | 10 |

---

## Runtime-parameters

| Parameter | Waarde | Betekenis |
|---|---|---|
| `mission_annotator_fps` | 5 | Annotator tick-frequentie |
| `mission_min_dwell_ms` | 750 | Minimum tijd voordat mission mag wisselen |
| `anti_stuck_*` | div. | Stuck-detectie drempels |
| `engagement_min_dwell_ms` | zie config | Hysteresis voor engagement-transities |
| `attention_target_min_dwell_ms` | zie config | Target-lock hysteresis |
| `commit_shot_hold_ms` | zie config | Commit-shot vasthouden |
| `visible_grace_ms` | zie config | Zichtbaarheids-grace period |
| `tactical_min_dwell_ms` | zie config | Tactical state dwell |
| `carrier_line_margin_norm` | zie config | Carrier-shadow marge |

---

## Objective Resolution

`navTarget` feature-input naar `rl_pawn` en `objective_progress` dense reward gebruiken dezelfde priority chain in twee aparte bronnen. Bij wijziging altijd beide synchroon aanpassen -- drift veroorzaakt reward/feature inconsistentie.

**Priority order (identiek in beide bronnen):**

| Prioriteit | Conditie | Objective |
|---|---|---|
| 0 | Bot draagt enemy flag | [`CarrierObjectiveResolver`](../../java-model/src/main/java/aiplay/shared/objective/CarrierObjectiveResolver.java): own flag base (preserve + stage capture) — of een nabije **gedropte** eigen vlag wanneer terughalen een goedkope on-route detour is. Wanneer de capture geblokkeerd is én een enemy over midfield staat: een **staging-zone** rond de base i.p.v. het exacte punt (zie hieronder) |
| 1 | Own flag dropped + flag op enemy helft | Defender: own base. Anderen: chase drop. |
| 2 | Own flag dropped + bot op home helft | Own flag locatie (recovery touch) |
| 3a | Own flag carried + **ik ben counter-grabber** | Enemy flag locatie (defensive grab → standoff) |
| 3b | Own flag carried + niet-grabber / dual-flag | EFC cut-off punt richting enemy base (intercept) |
| 5 | Defender idle | Own flag base-locatie (guard own base) |
| 6 | Anderen idle | Enemy flag locatie (rush enemy flag) — draagt een **teammate** de enemy-vlag, dan is dit de live carrier-positie: **escort-standoff** + **capture-funnel-release** (zie hieronder) |

Prioriteit 3a/3b is de [counter-grab split](#counter-grab-split-enemy-heeft-onze-vlag-wij-die-van-hen-nog-niet); de grabber-keuze is rol-blind en positie-gebaseerd via `CounterGrabResolver`.

### Carrier-objective: gedropte eigen vlag returnen (prioriteit 0)

Een capture is geblokkeerd tot onze eigen vlag thuis is. Default staat de carrier daarom bij zijn base
(preserve de vlag — sterven = drop — en stage voor instant-capture zodra onze vlag terug is). Maar ligt
onze eigen vlag **DROPPED** (stationair, returnbaar via touch) ongeveer op de route naar huis, dan haalt
de carrier hem zélf op: dat unblocked de capture meteen i.p.v. bij base te wachten op een teammate.
[`CarrierObjectiveResolver`](../../java-model/src/main/java/aiplay/shared/objective/CarrierObjectiveResolver.java)
beslist dit via een puur geometrische detour-test — `extra = d(bot,vlag) + d(vlag,home) − d(bot,home)` is
de omweg-kost; ≤ `MAX_RETURN_DETOUR_UU` (1500) → divert, anders naar base. Een **CARRIED** eigen vlag
(bewegende EFC) wordt nooit gechased (kan niet via touch terug, en de kostbare enemy-vlag riskeren is fout
— de 2026-05-29 carrier-first beslissing). Eén resolver, gedeeld door de `navTarget`-feature én de
`objective_progress`/`facing`-rewards (geen drift; de carrier is via `!carrier` uitgesloten van het
verhoogde own-flag-return reward-pad zodat reward en feature op dezelfde richting trekken).

### Carrier-staging-zone: beperkt manoeuvreren wanneer scoren geblokkeerd is (2026-05-31)

Default trekken drie krachten de carrier naar het **exacte** `ownFlag.baseLocation`: de
`objective_progress`-floor staat voor een carrier op 0 (dense pull tot `dist→0`), de quadratische
`carrier_proximity_bonus` piekt op het centrum, en de `navTarget`-bearing wijst er constant heen. Dat is
correct wanneer de carrier kán scoren (onze vlag thuis → base raken = instant capture). Maar is de capture
**geblokkeerd** (onze vlag CARRIED door een enemy of off-route DROPPED), dan kan de carrier niet scoren en
hoeft hij alleen te *stagen + overleven* tot een teammate de vlag terugbrengt — en dan is recht naar het
exacte base-punt lopen **te voorspelbaar**: de enemy campt het punt, kill, vlag dropt.

[`CarrierObjectiveResolver`](../../java-model/src/main/java/aiplay/shared/objective/CarrierObjectiveResolver.java)
vervangt het punt in dat geval door een **staging-zone**: een cirkel met radius
`STAGE_ZONE_FRACTION` (0.12 ≈ 160 UU op CTF-AndAction) × inter-base-afstand rond de eigen base — bewust
KLEIN gehouden (cover-zoek-ruimte rond de vlagstand, ruim onder de ~220 UU rocket-splash) zodat de
carrier op de capture-trigger blijft i.p.v. weg te dwalen. De zone is **conditioneel** op dreiging —
alleen actief wanneer een enemy dicht bij onze base staat
([`HalfFieldGeometry.enemyDepthInOwnHalf`](../../java-model/src/main/java/aiplay/shared/field/HalfFieldGeometry.java)
`> STAGE_ZONE_ENEMY_DEPTH_THRESHOLD`, 0.40 ↔ enemy in de binnenste ~30% van onze helft). Zonder nabije
dreiging gaat de carrier gewoon naar het exacte base-punt (klaar om te scoren zodra onze vlag terug is);
zodra onze vlag thuis is (`status == HOME`) of de carrier zelf een gedropte vlag ophaalt, vervalt de zone
ook. **2026-05-31**: radius 0.35 → 0.12 en drempel 0.0 → 0.40 na live observatie dat de brede,
bijna-altijd-actieve zone de carrier zonder objective-pull liet en de policy dat vacuüm vulde door in de
achterhoek te kruipen (makkelijk doelwit i.p.v. het bedoelde onvoorspelbaar stagen).

Het objective-*target* blijft `ownFlag.baseLocation` (single-source-anker); de zone is een gedeelde
shaping-laag eroverheen die beide bronnen via `stageZoneRadiusUu`/`isStagingZoneActive` lezen, dus geen
drift:

| Bron | Binnen de zone | Buiten de zone |
|---|---|---|
| `objective_progress` reward | floor = zone-radius → afstand-tot-base geclamped → geen progress-pull (vrij) | pull naar de zone-rand (rim = zachte wand) |
| `carrier_proximity_bonus` | uit (geen pull naar centrum) | n.v.t. (alleen relevant bij capture-mogelijk) |
| `navTarget` feature | effectieve afstand `max(0, dist−R)` → bearing squasht naar neutraal (0,1) | bearing wijst naar de zone-rand (≈ richting base) |

Dit hergebruikt exact het `efcEngagementFloor`-standoff-mechanisme (clamp op `max(dist, floor)`) dat de
EFC-interceptors al gebruiken. **Vereist een SAC-retrain** om in de policy in te dalen — het verandert
reward + feature, geen runtime-regel.

### Escort-standoff + capture-funnel-release: de teamgenoot blokkeert de capture niet (2026-06-06)

Live observatie: een teamgenoot loopt vlak **tegen de flag-carrier aan** terwijl die met de enemy-vlag
vlakbij de eigen (aanwezige) home-flag staat — pawns botsen, dus de escort duwt en blokkeert fysiek de
capture-route. Oorzaak: zodra een teammate de enemy-vlag draagt is `enemyFlag.location` de live
carrier-positie, en wezen **alle** dense pulls met optimum op afstand 0 naar de carrier:

| Bron | Pull (Cover) | Pull (Attack) |
|---|---|---|
| `objective_progress` (priority-6 fallback, floor 0) | 0.025/UU | **0.08/UU** |
| `cover_escort.proximity_bonus` (ramp piekte op 0 UU) | 0.06/tick | — |
| `team_assist.escort_proximity_dense` (geen carrier-uitsluiting) | 0.04/tick | — |
| `navTarget`-bearing | wees exact naar de carrier | idem |

[`EscortObjectiveResolver`](../../java-model/src/main/java/aiplay/shared/objective/EscortObjectiveResolver.java)
(single source voor reward + feature, naast `CarrierObjectiveResolver`/`CounterGrabResolver`) lost dit
in twee lagen op:

1. **Escort-standoff (250 UU, altijd tijdens een teammate-carry)** — hetzelfde
   `max(dist, floor)`-clampmechanisme als de EFC-chase (280 UU): `objective_progress` clampt de
   afstand-tot-carrier, `cover_escort` rekent zijn ramp op de geclampte afstand (vlak plateau binnen de
   band), en de `navTarget`-bearing squasht binnen de band naar neutraal (effectieve afstand
   `max(0, dist − 250)`). De band-rand wordt de attractor: meelopen en threats clearen blijft beloond,
   ÍN de carrier stappen levert niets meer op. 250 < 280 omdat een vriendelijke escort geen
   rocket-splash-marge nodig heeft; ruim boven de ~100 UU waar fysiek duwen speelt.
   `team_assist.escort_proximity_dense` sluit de carrier voortaan als target uit — de carry-fase is
   exclusief `cover_escort`, dus de twee ramps stapelen nooit meer op de carrier.
2. **Capture-funnel-release** — kan de carrier **scoren** (own flag `HOME`) én zit hij in de last-mile
   (< 500 UU van eigen base = `carrier_proximity_radius_uu`, de zone van zijn kwadratische
   capture-burst), dan gaan álle escort-pulls volledig los (progress-term 0, bearing neutraal,
   proximity én far_penalty uit): zelfs de band-rand-attractor zou de escort de funnel in trekken. De
   nieuwe `cover_escort.berth_penalty` (0.05 voor Cover én Attack) straft binnen de standoff-band
   tijdens dit venster — een al-plakkende escort krijgt zo een echte gradient om uit het capture-pad
   te stappen i.p.v. alleen een weggevallen pull. De sparse `flag_event.team_captured` +
   `team_assist.team_captured_assist` (binnen `assist_radius_uu` 1500 van de base) houden "in de buurt
   zijn op het capture-moment" lonend.

Dropt de carrier de vlag (kill), dan is er geen teammate-carrier meer → standoff en funnel vervallen
instant en de teamgenoot kan de drop vrij contesten — het gewenste "help hem als hij de vlag verliest"
blijft volledig intact. Defenders (priority 5, base-anker) krijgen géén escort-floor: hun objective is
de base zelf, niet de carrier. **Vereist een SAC-retrain** om in de policy in te dalen — het verandert
reward + feature, geen runtime-regel.
