# Self-Play & Champion-Gate Architectuur

Bron van waarheid voor het multi-bot platform en het closed-loop self-play champion-gate systeem dat het `rl_pawn` model continu laat verbeteren door het tegen bevroren snapshots van zichzelf te laten spelen.

---

## 1. Wat het systeem doet

Het `rl_pawn` model traint continu op alle vijf machines tegelijk. Het **champion-gate** systeem laat de **live policy** (`current`) periodiek spelen tegen een **bevroren snapshot** van zichzelf (`champion`) in dezelfde CTF-match. De SAC trainer evalueert via DualKPIDeltaGate wie wint — en handelt daarop.

Drie soorten beslissingen:

| Beslissing | Wanneer | Effect |
|---|---|---|
| **PROMOTE** | Alle 3 KPI's overtreffen hun baseline over `promote_window_cycles` cycli op rij | Snapshot van current naar nieuwe champion in de pool, oudste gedropt |
| **ROLLBACK** | Een of meer KPI's zakken onder rollback-margin | Champion-ONNX overschrijft trainingsmodel; bots swappen automatisch |
| **NEUTRAL** | Mixed of binnen marge | Doortrainen |

DeltaGate-baseline (RL vs UT99) is de **vloer** — RL mag niet onder UT99 zakken. De champion-current vergelijking is het **plafond** — current moet zichzelf overtreffen. Beide checks zitten in de DualKPIDeltaGate binnen de SAC trainer.

---

## 2. Big picture

```
                       DEV machine
                  +--------------------------------+
                  |  python3 -m train.common.*     | --- via SSH ---+
                  |  champion_store / _pool / Sync |                |
                  |  scripts/deploy.sh             |                |
                  +--------------------------------+                |
                                                                    v
   +-----------------------------------------------------------------------+
   |  TRAINER (primary, machine met meeste trainer-slots)                   |
   |                                                                       |
   |   +-------------------------------------------------------------+    |
   |   | SAC trainer (rl_pawn)                                        |    |
   |   |   +-- DualKPIDeltaGate (promote/rollback in-process)        |    |
   |   |   +-- snapshots -> champion_store / pool                    |    |
   |   +-----------------------------+-------------------------------+    |
   |                                 |                                     |
   |                                 v                                     |
   |       +----------------------+                                        |
   |       | models/trainingmodel |                                        |
   |       |   rl_pawn.onnx (live)|                                        |
   |       +----------+-----------+                                        |
   |                  |                                                    |
   |       +----------v-----------+                                        |
   |       | models/champions/    | <-- snapshots + bundles.json           |
   |       |   rl_pawn/0001..N/   |                                        |
   |       +----------+-----------+                                        |
   |                  |                                                    |
   |                  |  rsync (ModelSync, ChampionSync)                   |
   +------------------+----------------------------------------------------+
                      |
       +--------------+--------------+--------------+-------------+
       v              v              v              v             v
   +--------+    +--------+    +--------+    +--------+    +--------+
   | srv 1  |    | srv 2  |    | srv 3  |    | srv 4  |    | srv 5  |
   |  bots  |    |  bots  |    |  bots  |    |  bots  |    |  bots  |
   +---+----+    +---+----+    +---+----+    +---+----+    +---+----+
       |             |             |             |             |
       |  PLAYER_SCORES logs (incl. policy_role per bot)       |
       +-------------+-------------+-------------+-------------+
                            ^
                            |  SSH-grep door SAC trainer
                            |
        DualKPIDeltaGate aggregeert van alle 5 servers
```

De pool houdt de laatste **3** bundles aan voor diversiteit. Bij promote schuift er een nieuwe bovenaan, oudste valt eraf.

---

## 3. Storage layout

```
{sessions_dir}/models/
+-- trainingmodel/                    <-- live (SAC writes here)
|   +-- rl_pawn.onnx              <-- ModelWatcher polls this
|   +-- rl_pawn.onnx.data        <-- external tensor weights
+-- champions/                        <-- frozen snapshots
    +-- rl_pawn/
    |   +-- 0001-bc-bootstrap/
    |   |   +-- rl_pawn.onnx
    |   |   +-- rl_pawn.onnx.data
    |   |   +-- snapshot.json         <-- fingerprints + KPI@snap + match_history
    |   +-- 0002-flak-fix/
    |   +-- ...
    +-- bundles.json                  <-- rotating pool van {model_key: counter}
```

| Element | Wat | Waarom |
|---|---|---|
| Counter (`0001`, `0042`, ...) | Monotone integer | Eenduidig, sorteerbaar, geen collisions |
| Optionele tag-suffix | `0042-flak-cooldown-fix` | Mens-leesbare context |
| `snapshot.json` | Fingerprints + KPI bij creatie | Cross-language compat-check + forensiek |
| `bundles.json` | Pool van 3 bundles, elk een dict `{model_key: counter}` | Roterend opponent-rooster |

---

## 4. Lifecycle: van match tot decision

```
   DualKPIDeltaGate eval-cycle in SAC trainer
         |
         v
   SSH-grep PLAYER_SCORES van alle servers
         |
         v
   per match-bucket (host:instance), per player:
       eerste/laatste snapshot -> KPI over window-span
         |
         v
   bucket per policy_role:
       current RL-policy  -> current_values[]
       verified baseline  -> baseline_values[]   (vooraf vastgelegd)
       (UT99 stock = genegeerd)
         |
         v
   per KPI: ratio = avg(current_values) / baseline
            KPI's: combat_score, shots_on_target_rate, flag_score
         |
         v
   per KPI: append ratio aan match-aligned window
         |
         v
   +-----------------------------------------------+
   | alle 3 ratio's >= promote-drempel?  (AND)     |
   +-------+-------------------------------+-------+
         YES                              NO
           |                               |
           v                               v
        PROMOTE        +-----------------------------------+
                       | een ratio <= rollback-drempel?    |
                       | (OR-rollback)                     |
                       +------+---------------------+------+
                            YES                     NO
                              |                      |
                              v                      v
                          ROLLBACK        NEUTRAL / INSUFFICIENT
```

### 4.1 Promote-actie

1. Snapshot huidige `trainingmodel/rl_pawn.onnx` naar `champions/rl_pawn/{volgende_counter}/`
2. Schrijf `snapshot.json` met fingerprints (sha256 over `features.json`, `model.json`, `rewards.json`) + KPI@snap
3. Append nieuwe bundle aan `bundles.json` head; drop pool[3] (oudste)
4. ChampionSync rsynct naar alle non-trainer servers

### 4.2 Rollback-actie

1. Newest champion ONNX (`.onnx` + `.onnx.data`) kopieert over `trainingmodel/rl_pawn.onnx`
2. ModelSync rsync naar alle servers
3. ModelWatcher detecteert mtime-verandering en hot-swapt de ONNX-sessie
4. Bots draaien direct met champion-weights — geen restart nodig

### 4.3 KPI's

Het `rl_pawn` model gebruikt drie KPI's via DualKPIDeltaGate (AND-promotie, OR-rollback):

| KPI | Formule |
|---|---|
| `combat_score` | `frags + (damage_dealt - 0.3 * damage_taken) / 80` |
| `shots_on_target_rate` | `shotsOnTarget / shots` |
| `flag_score` | `1*taken + 7*captured + 3*returned` |

Marges zijn per-KPI omdat de eenheden verschillen (kill-equivalents/min vs. ratio in [0,1]).

---

## 5. Configuratie

### 5.1 Gate-config

DualKPIDeltaGate cadence, baselines, promote/rollback windows staan in `resources/models/rl_pawn/export_gate.json`.

### 5.2 `resources/config/gameplay.json` -- `ai_bots[]`

Per RL-bot: `snapshot` bepaalt welke ONNX de bot draait.

| `snapshot` waarde | Betekenis |
|---|---|
| `"current"` | Live trainingsmodel ONNX (bot leert mee) |
| `"rl_pawn/<counter>"` | Frozen champion (bot draait die specifieke ONNX) |

`snapshot` is verplicht per bot-entry (geen fallback).

### 5.3 `resources/config/servers.json`

Bepaalt waar bots draaien en wie de **primary trainer** is (hoogste totaal van `bc_trainer_slots + sac_trainer_slots`, tiebreak op `gpu_instances`).

---

## 6. Hoe gebruik je het

De champion-store leeft op de **primary trainer** (default `desktop-4090.fritz.box`; formeel de machine met de hoogste `bc_trainer_slots + sac_trainer_slots` in `servers.json`). Beheer 'm via de `train.common.*` Python-modules **op die machine**, vanuit de project-root met de venv-interpreter. Vanaf dev via SSH:

```bash
ssh kris@desktop-4090.fritz.box \
  "cd /home/kris/projects/ut99neuralnet && .venv/bin/python3 -m train.common.champion_store list"
```

Bot-snapshots zelf staan in `resources/config/gameplay.json` (lokaal in de repo) en zet je met de hand — zie 6.2.

### 6.1 Dagelijks gebruik

| Wat | Commando (op de trainer, vanuit project-root) |
|---|---|
| Champion-overzicht | `.venv/bin/python3 -m train.common.champion_store list [--model-key rl_pawn]` |
| Een champion details | `.venv/bin/python3 -m train.common.champion_store show rl_pawn/0007` |
| Bundles.json bekijken | `.venv/bin/python3 -m train.common.champion_pool show` |
| Live SAC-trainer output | `ssh kris@<trainer> tmux attach -t sac_rl_pawn` |
| Promote/rollback events | `ssh kris@<trainer> "grep -E 'DUAL_KPI_(PROMOTED\|ROLLBACK)' /tmp/ut99-multi/rl_pawn_sac.log"` |

### 6.2 Eerste keer self-play opstarten

Pin de rode bots op een champion door hun `rl_pawn`-`snapshot` in `resources/config/gameplay.json` te zetten. Mogelijke waarden:

| Waarde | Effect |
|---|---|
| `"current"` | Live trainingsmodel (bot leert mee) |
| `"rl_pawn/<counter>"` | Vaste, frozen champion |
| `"rl_pawn/newest"` | Auto-roteert: `SnapshotResolver` leest `bundles.json` bij elke bot-start en pakt de nieuwste *promoted* counter |

`"rl_pawn/newest"` is de "set and forget"-keuze voor closed-loop self-play — na elke PROMOTE pakken de champion-bots automatisch de nieuwste bundle, zonder dat je `gameplay.json` opnieuw hoeft aan te raken. Daarna:

```bash
bash scripts/deploy.sh    # met restart-bots: true
```

### 6.3 Ochtendroutine (na een nacht self-play)

`current` (live policy) verandert continu. Bij PROMOTE ontstaan nieuwe champions (`0002`, `0003`, ...) in `bundles.json`.

- Staan je champion-bots op `"rl_pawn/newest"`, dan volgen ze de nieuwste promoted counter automatisch — geen actie nodig.
- Staan ze op een vaste counter, dan blijven ze tegen die champion draaien tot je `gameplay.json` bijwerkt.

```bash
# Wat is er vannacht gebeurd? (op de trainer)
ssh kris@<trainer> "cd /home/kris/projects/ut99neuralnet && .venv/bin/python3 -m train.common.champion_store list"
ssh kris@<trainer> "cd /home/kris/projects/ut99neuralnet && .venv/bin/python3 -m train.common.champion_pool show"
ssh kris@<trainer> "grep -E 'DUAL_KPI_(PROMOTED|ROLLBACK)' /tmp/ut99-multi/rl_pawn_sac.log | tail"
```

| Gate-resultaat vannacht | Actie |
|---|---|
| Alleen NEUTRAL | Geen actie. |
| PROMOTE | Nieuwe counter in pool[0]. Met `"rl_pawn/newest"` automatisch opgepikt; met vaste counter: `gameplay.json` bijwerken + deploy. |
| ROLLBACK | `trainingmodel/*.onnx` overschreven door champion. Pin desgewenst op een oudere counter uit de pool, of wacht tot delta weer divergeert. |

### 6.4 Self-play stoppen / bijsturen

| Doel | Hoe |
|---|---|
| Promote/rollback uitschakelen | Stop SAC trainer: `ssh kris@<trainer> tmux kill-session -t sac_rl_pawn` |
| Bot terug naar live policy | Zet die bot's `"snapshot"` op `"current"` in `gameplay.json` + deploy |
| Alle rode bots terug naar live | Idem voor elke bot |

### 6.5 Snapshot-beheer (op de trainer, vanuit project-root)

| Wat | Commando |
|---|---|
| Handmatige snapshot (auto-counter, optionele tag) | `.venv/bin/python3 -m train.common.champion_store create rl_pawn [--tag <tag>]` |
| Snapshot wissen | `.venv/bin/python3 -m train.common.champion_store delete rl_pawn 7` |
| Alles wissen + opnieuw bootstrap | `reset-champions: true` in `deploy.json`, daarna `bash scripts/deploy.sh` |
| Manual rsync champions | `.venv/bin/python3 -m train.common.ChampionSync all` |

`create` synct standaard naar alle servers; `--no-sync` slaat dat over.

### 6.6 Cold start

`bash scripts/deploy.sh` doet alles:

1. Build + sync code naar 5 servers
2. Restart bots + trainers (BC/SAC)
3. SAC trainer bootstrap't pool automatisch als `champions/` leeg is: kopieert BC-baseline ONNX naar `champions/rl_pawn/0001-bc-bootstrap/`, voegt counter toe aan `bundles.json`, syncrt naar alle servers.

Daarna de rode bots in `gameplay.json` op `"rl_pawn/newest"` zetten om ze als champion te markeren (zie 6.2).

---

## 7. Veiligheidskleppen

### 7.1 Fingerprint-validatie

Bij elke champion-load vergelijkt de runtime de fingerprint in `snapshot.json` met sha256 van de huidige `features.json` + `model.json` + `rewards.json`. Mismatch resulteert in een hard fail (bot start niet).

| Bestand | Mismatch effect |
|---|---|
| `features.json` | Hard fail -- input-tensor klopt niet |
| `model.json` | Hard fail -- architectuur veranderd |
| `rewards.json` | Informatief (forensisch) -- beinvloedt frozen ONNX niet |

### 7.2 DeltaGate sanity-belt

DualKPIDeltaGate weigert PROMOTE als de huidige baseline-meting `rl_avg < ut99_avg` toont (current sub-UT99). Een zelf-snapshot verslaan rechtvaardigt geen promote als current onder de UT99-vloer ligt.

### 7.3 Experience-filtering

Elke bot tagt zijn NPZ-rijen met `policy_role` (0=CURRENT, 1=CHAMPION). Of CHAMPION-rijen worden meegenomen is configureerbaar in `resources/models/rl_pawn/sac.json`:

| `champion_experience_enabled` | Gedrag |
|---|---|
| `true` (default) | NPZ inclusief role=1 rijen; SAC ziet champion-transities als off-policy samples |
| `false` | Champion-batch wordt overgeslagen bij flush; ingest dropt role=1 rijen |

SAC is off-policy en kan leren van willekeurige (s,a,r,s') tuples. Champion = vorige promoted snapshot dus skill-vergelijkbaar met current. DualKPIDeltaGate vangt eventuele regressie op.

BC traint op menselijke opnames (CSV pad), ziet geen bot-NPZ -- onafhankelijk van deze vlag.

---

## 8. Multi-bot per UT99-server

Self-play vereist dat een UT99-server bots van beide soorten herbergt (current + champion).

### 8.1 Threads & processen

Een UT99-server (`ucc-bin`) wordt gedeeld door alle RL-bots in dezelfde instance. Elke RL-bot krijgt eigen virtuele threads:

```
Instance 0 (ucc-bin op poort 7777)
|
+-- MrBlue-Attack (RL, team=1, snapshot=current)
|     +- Bot-loop      (BT tick, ~20 Hz)
|     +- Producer      (game state poll, ~60 Hz)
|     +- Policy exec   (inference, 30 Hz)
|     +- Command exec  (controller, ~50 Hz)
|
+-- MrRed-Attack2 (RL, team=0, snapshot=rl_pawn/0001)
|     +- idem (eigen ThreadLocal identity)
|
+-- stock UT99 bots (engine-AI, geen virtuele threads)
```

`SKIP_SERVER_START` in de blackboard zorgt dat alleen de eerste RL-bot de `ucc-bin` start; volgende bots verbinden eraan.

### 8.2 Per-bot vs gedeeld

| Gedeeld (per instance) | Per RL-bot |
|---|---|
| `ucc-bin` proces | Identity (naam, team, role) -- ThreadLocal |
| Game-port + UDP-ports | UDP command sender (eigen `botIdx`) |
| State receiver (single thread, latest frame) | Predictor + ModelWatcher (eigen ONNX-pad bij champion) |
| Predictor session-cache | Experience collector per model |
| | Behavior tree + blackboard |

Champions wonen op een ander ONNX-pad dan current, dus de session-cache houdt aparte ONNX-instanties -- correct gebruik per bot is gegarandeerd.

### 8.3 Bot-identiteit (ThreadLocal)

Elke thread van een bot krijgt naam, team, role en snapshot-keuze in een ThreadLocal context. De state-converters lezen "self player" uit een gedeelde StateFrame en moeten weten welke bot ze bedienen.

Het snapshot-string wordt gemapt naar `CURRENT` of `CHAMPION` -- gebruikt door experience writers en de PLAYER_SCORES logger om elke rij correct te taggen.

### 8.4 Roster-transport (map-URL → RLCTFGame)

`RunUT99ServerActionNode` geeft de roster aan de UC-mod door als **map-URL parameters**. Twee harde UT99-limieten sturen de encoding:

| Limiet | Constraint |
|---|---|
| **Array-grootte** | `RLCTFGame.uc` roster-arrays zijn `[16]` → **max 16 RLBots per server** (`RLBotCount` afgeleid uit de roster-lengte; transport-arrays `MAX_SLOTS=32`). |
| **URL-lengte** | UT99 kapt de `InitGame` `Options`-string af op **~1024 chars** (+ een ~127-char `?Name=...`-DefaultPlayer-prefix uit `User.ini`). |

Daarom is de roster **URL-compact** gecodeerd, en staan de UDP-poorten **vóór** de roster zodat ze de afkapping altijd overleven:

```
...?RLUdpPort=P?RLStateUdpPort=Q            <- transport-poorten EERST
   ?Apr=cls|skin|face|voice,cls|...         <- gededupeerde appearance-tabel
   ?RLBots=name|team|aprIdx,name|...        <- één veld per bot, refereert Apr-index
```

Appearances worden geïnterneerd (self-play-rosters delen er doorgaans een handvol over beide teams), wat 10–16 bots ruim onder de 1024-grens houdt. Namen/appearances mogen geen `,` `|` `?` bevatten (Java faalt fast). Bij veel *unieke* appearances kan de URL alsnog naderen — `RunUT99ServerActionNode` logt dan een waarschuwing vóór er stil wordt afgekapt.

> **Regressie-signatuur:** maar enkele RL-bots joinen (rest stock-UT99-aangevuld via `MinPlayers`) **én** ze staan stil + `PLAYER_SCORES=0` = URL-overflow. Check `server.log`: een afgekapte `Log: Browse:`-URL + `InitGame ... RLUdpPort=0 RLStateUdpPort=0` (geen command-receiver → stil; geen state-sender → geen scores). Oudere per-bot `?RLBotNApr=<volledige appearance>`-encoding overliep al bij ~5 bots.

---

## 9. Canonical perspective normalisatie

Het model is getraind op blauw-perspectief (team=1). Bots op rood (team=0) transformeren hun input-features naar blauw via een 180-graden kaartrotatie.

```
   BLAUWE BOT                         RODE BOT

   Features (native blauw)            Features (native rood)
        |                                  |
        |                             Normalizer (180 graden rotatie)
        |                                  |
        v                                  v
   Model input (blauw)                Model input (blauw)
        |                                  |
   Actie (relatief)                   Actie (relatief)
   forward = forward                  forward = forward van rood
```

Acties zijn relatief (forward/left/right + yaw-delta + pitch-delta) en werken voor beide teams zonder verdere transformatie.

| Categorie features | Transformatie |
|---|---|
| Absolute positie / kijkrichting / snelheid | Negeren (x-1) |
| Verticale componenten | Ongewijzigd |
| Egocentrische snelheid (forward/right) | Ongewijzigd (rotatie-invariant) |
| Collision rays (yaw-relatief) | 180 graden ring-permutatie (8 paren) |
| Collision rays (world-axis) | As-permutatie +/-X / +/-Y |
| Route one-hot (left/right) | Swap |
| `team_norm` | Override naar 1.0 (canonical blauw) |
| Mission/engagement/attention/status | Ongewijzigd (al team-aware op DTO-niveau) |

Normalisatie gebeurt voor de feature-cache. Zowel model-input als geschreven NPZ bevatten altijd blauw-perspectief -- SAC traint op uniforme data zonder team-onderscheid.

---

## 10. UDP transport

Volledige packet-layout staat in [docs/webservicemod/webservice-mod-architecture.md](webservicemod/webservice-mod-architecture.md). Voor self-play relevant:

- **Commands**: 12-byte UDP-packet per tick. Byte `[1]` = `botIdx` (positie in `gameplay.json` `ai_bots[]`). Routing via `RLBots[botIdx]`-array -- O(1), geen string-matching.
- **State**: multi-packet state sender op 60 Hz. Een receiver-thread per instance, latest frame via `AtomicReference`.
- **Magic-byte + bounds-validatie** dropt corrupte packets met diagnostic counters (`DropCount`, `BadMagicCount`, `BadIndexCount`).

---

## 11. Heap & schaling

Een bot-runtime = 4 virtual threads + buses + buffers = ca. 500 MB heap. UT99 stock bots zijn engine-intern, geen heap-impact.

| Configuratie | Servers | RL-bots/server | Totaal RL-bots | Heap |
|---|---|---|---|---|
| 2 RL bots | 60 | 2 | 120 | ca. 60.5 GB |
| 2 RL + 3 UT99 | 60 | 2 | 120 | ca. 60.5 GB |
| 4 RL bots | 30 | 4 | 120 | ca. 60.5 GB |
| 2 RL bots | 30 | 2 | 60 | ca. 30.5 GB |

Champion-bots hebben dezelfde heap-footprint als current-bots -- een extra ONNX-sessie per snapshot in de cache, maar de inference-grafiek is identiek.

---

## 12. Config-bestanden

| Bestand | Rol |
|---|---|
| `resources/models/rl_pawn/export_gate.json` | DualKPIDeltaGate cadence, promote/rollback window cycles, baselines |
| `resources/config/gameplay.json` | `ai_bots[]` met per-bot `snapshot` |
| `resources/config/servers.json` | Machine-inventory, primary-trainer derivatie |
| `resources/config/maps/<map>.json` | Per-map symmetric-flag voor team-alternation |
| `resources/models/rl_pawn/features.json` | Gehasht voor fingerprint-validatie |
| `resources/models/rl_pawn/model.json` | Gehasht voor fingerprint-validatie |
| `resources/models/rl_pawn/rewards.json` | Gehasht (informatief) |

---

## 13. Veelvoorkomende scenario's

### 13.1 "DualKPIDeltaGate beslist consequent ROLLBACK"

Oorzaken in volgorde van waarschijnlijkheid:

1. **Reward-shape veranderd** zonder dat champion daarmee getraind was. Check `rewards_fingerprint` in `snapshot.json` (`champion_store show`).
2. **SAC instabiliteit** (collapse, exploration spike). Check `delta_baseline.json` voor recente gen-rollbacks.
3. **Champion is genuinely sterker** -- meer training laten doortrainen tot baseline herstelt.

### 13.2 "Fingerprint mismatch na config-wijziging"

Bij aanpassing van `features.json` of `model.json` worden alle bestaande champions incompatibel:

```bash
# Optie A: specifieke snapshots droppen (op de trainer)
.venv/bin/python3 -m train.common.champion_store delete rl_pawn 5

# Optie B: clean-slate — zet reset-champions: true in deploy.json, daarna:
bash scripts/deploy.sh
```

ONNX-files zelf zijn niet kapot -- ze passen alleen niet meer bij de huidige feature-schema. Bij volgende deploy bouwt de SAC trainer de pool opnieuw op met `0001-bc-bootstrap`.

### 13.3 "Geen PROMOTE/ROLLBACK -- alleen NEUTRAL of INSUFFICIENT"

Geen champion-bots actief. Check `gameplay.json` -- minstens een bot moet een non-`current` snapshot hebben. Anders ziet de gate alleen current-data en kan geen vergelijking maken.

---

## 14. Map-symmetry team-alternation

Bij self-play en `<map>.symmetric=true` zet de multi-instance launcher automatisch team-alternation aan: even instances gebruiken normale `gameplay.json` teams, odd instances krijgen team `0<->1`. DualKPIDeltaGate splitst op `policy_role_mask`, dus current/champion blijven correct gebucket ongeacht teamkleur.
