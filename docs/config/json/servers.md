# servers.json -- serverconfiguratie

**Bestand:** `resources/config/servers.json`

De gecentraliseerde machine-inventory die deploy, runtime, training en replay-routing aanstuurt.

---

## Overzicht

De serverconfiguratie beschrijft vier domeinen tegelijk:

| Domein | Wat het bepaalt |
|---|---|
| Connectiviteit | Hostname, SSH user (SSH-wachtwoord staat apart — zie [SSH-wachtwoord](#ssh-wachtwoord-niet-in-git)) |
| Capaciteit | GPU/CPU-instances, CUDA aan/uit, bot-JVM heap-plafond |
| Rollen | BC trainer-slots, SAC trainer-slots, CSV writer-slots |
| Runtime-layout | Display-bases, poorten, gamespeed, gamestyle, extra env |

---

## Top-level structuur

| Pad | Type | Betekenis |
|---|---|---|
| `version` | number | Contractversie van de inventory |
| `recording_server` | string | `machine_id` van de machine die BC-recordings produceert |
| `machines[]` | array | Lijst van alle bekende machines |

## Structuur per machine

| Pad | Type | Betekenis |
|---|---|---|
| `machine_id` | string | Stabiele clusteridentiteit |
| `hostname` | string | FQDN of hostnaam voor matching |
| `ssh.user` | string | SSH-gebruiker (wachtwoord staat apart — zie [SSH-wachtwoord](#ssh-wachtwoord-niet-in-git)) |
| `capacity.gpu_instances` | integer | Aantal GPU bot-instances |
| `capacity.cpu_instances` | integer | Aantal CPU bot-instances |
| `capacity.cuda_enabled` | boolean | CUDA libraries geladen |
| `capacity.jvm_heap_max_mb` | integer | Plafond op de bot-JVM `-Xmx` (MB). De launcher capt de heap-formule uit `runtime.json`: `Xmx = min(heap_base + instances*heap_per_instance, jvm_heap_max_mb)`. Nodig omdat ZGC zijn heap met shared memory backt en multi-mapt → fysieke RSS ≈ 2× `-Xmx`; op RAM-krappe machines (3070: 32 GB, formule 16 GB) trof dat de kernel-OOM-killer, dus daar capt het plafond op 11 GB. Op machines met RAM-ruimte wint de formule en bijt het plafond niet. |
| `roles.bc_trainer_slots` | integer | Gelijktijdige BC trainer-processen |
| `roles.sac_trainer_slots` | integer | Gelijktijdige SAC trainer-processen |
| `roles.csv_writer_slots` | integer | Gelijktijdige CSV-shard-processen |
| `roles.bc_trainer_priority` | integer | Volgorde-prioriteit voor BC-trainer-toewijzing (lager = eerder) |
| `ports.display_base` | integer | Basis display-index |
| `ports.web_port_base` | integer | Eerste webservice-poort |
| `ports.game_port_base` | integer | Eerste game-poort |
| `ports.game_port_step` | integer | Poortstap tussen instances (minimaal 11) |
| `ports.udp_port_base` | integer | UDP-port voor command-kanaal |
| `ports.state_udp_port_base` | integer | UDP-port voor state-kanaal |
| `gameplay.speed` | number | UT99 gamespeed multiplier |
| `gameplay.style` | string | Gameplay-stijl: `classic`, `hardcore`, `turbo` |
| `env` | object | Extra environment-variables per machine |

---

## SSH-wachtwoord (niet in git)

Het SSH-wachtwoord staat **bewust niet** in `servers.json` (die in git/GitHub zit). Het wordt centraal geresolved door `ServerInventory._resolve_ssh_password()`, in deze volgorde — **geen fallback**: ontbreekt het, dan crasht de inventory hard (conform de no-config-fallbacks-regel):

1. Environment-variable `UT99_SSH_PASSWORD`
2. `resources/config/secrets.local.json` → key `ssh_password`

`secrets.local.json` staat in `.gitignore` en wordt dus nooit gecommit. Kopieer `secrets.local.json.example` om hem aan te maken:

```json
{ "ssh_password": "..." }
```

**Distributie naar peers:** de file is git-ignored maar wordt wél door de rsync-gebaseerde `sync-code.sh` (en dus `deploy.sh`) naar alle machines gekopieerd — rsync volgt `--exclude`-patronen, niet `.gitignore`. De dev-machine is de enige bron; elke deploy pusht code + secret samen. Dit is de uitzondering op "config sync is NIET automatisch": de secret leeft niet in git, maar reist wel mee met de code-rsync.

**Eén resolutie, alle consumers:** Python (`load_servers()` → `ModelSync` / `ChampionSync` / `player_scores_eval`) én de shell-scripts (via `ServerInventory list-tsv` → `common.sh`'s `PASSES[]`) krijgen het wachtwoord allemaal via deze ene functie — nooit door `servers.json` direct te parsen.

---

## Afgeleide semantiek

| Afgeleide waarde | Regel |
|---|---|
| Totale instances | `gpu_instances + cpu_instances` |
| BC-trainer-capable | `bc_trainer_slots > 0` |
| SAC-trainer-capable | `sac_trainer_slots > 0` |
| Trainer-capable | BC of SAC slots > 0 |
| CSV-writer machine | `csv_writer_slots > 0` |

---

## `game_port_step` -- minimaal 11

UT99 servers binden meerdere poorten per instance:

| Offset | Doel |
|---|---|
| `gameport` | Game UDP + TCP |
| `gameport + 1` | Heartbeat |
| `gameport + 10` | Query port |

Bij `step < 11` botst de game-port van een hogere instance met de query-port van een lagere instance. Dit veroorzaakt bind-failures of stille port-verlies. Gebruik `game_port_step >= 11` (12 voor schone spacing).

---

## Huidige cluster

| Machine | Hostname | GPU | CPU | CUDA | BC | SAC | CSV | Game base | Speed |
|---|---|---:|---:|---|---:|---:|---:|---:|---:|
| `4090` | `desktop-4090.fritz.box` | 0 | 0 | ja | 1 | 2 | 4 | 7400 | 1.0 |
| `4070` | `desktop-4070.fritz.box` | 5 | 0 | ja | 1 | 0 | 4 | 7777 | 1.0 |
| `3070` | `desktop-3070.fritz.box` | 2 | 0 | ja | 1 | 0 | 2 | 7877 | 1.0 |
| `2070` | `desktop-2070.fritz.box` | 1 | 0 | ja | 0 | 0 | 0 | 7300 | 1.0 |
| `p15v` | `LAPTOP-P15v.fritz.box` | 1 | 0 | ja | 0 | 0 | 1 | 7200 | 1.0 |

### Rolverdeling

| Machine | Hoofdrol |
|---|---|
| `4090` | Primary trainer (BC + SAC), CSV writer (geen bot instances) |
| `4070` | BC worker, CSV writer, bot-runner (5 GPU instances) |
| `3070` | BC worker, CSV writer, bot-runner (2 GPU instances) |
| `2070` | Experience-only bot-runner (1 GPU instance) |
| `p15v` | CSV writer, bot-runner (1 GPU instance) |

---

## Primary trainer selectie

Niet hardcoded; afgeleid uit de inventory:

| Sorteerregel | Richting |
|---|---|
| `bc_trainer_slots + sac_trainer_slots` | Hoogste eerst |
| `gpu_instances` | Hoogste eerst |
| `machine_id` | Alfabetische tie-break |

Hiermee is `4090` de primary trainer.

### Trainer workers

| Type | Filter | Huidige workers |
|---|---|---|
| BC trainer | `bc_trainer_slots > 0` | `4090`, `4070`, `3070` (1 slot elk) |
| SAC trainer | `sac_trainer_slots > 0` | `4090` (2 slots) |

---

## Hoe de configuratie geladen wordt

### Python

De Python loader normaliseert `servers.json` naar een machine-objectmodel.

| Consumer | Verantwoordelijkheid |
|---|---|
| Server-inventory | Validatie en normalisatie |
| Model-sync | Bepaalt sync-targets voor ONNX-distributie |
| Property reader | Neemt `servers.json` op in de split config root |

### Shell

Deploy- en runtime-scripts laden de inventory via een gedeeld shell-script.

| Functie | Resultaat |
|---|---|
| Parse servers config | Laadt JSON via Python in shell-arrays |
| Filter servers | Filtert machines op hostname |
| Find primary trainer | Selecteert de primaire trainer |
| Get trainer workers | Alle trainer-capable workers |
| Get CSV writers | CSV-writers, gesorteerd op capaciteit |

### Bot-proces (JVM)

Het bot-proces leest `servers.json` rechtstreeks.

| Consumer | Verantwoordelijkheid |
|---|---|
| Machine-inventory | Leest machines in geheugen-records |
| Multi-instance launcher | Logt inventory snapshot bij startup |
| Root config loader | Neemt `servers.json` op in globale split config |

---

## Runtime-flow

### Launcher-flow per machine

```
Runtime host
    -> Script leest server-inventory
    -> Zoekt machine op hostname
    -> Exporteert machine_id, speed, style, CUDA, extra env
    -> Start JVM met instances, GPU-count en poortbases
```

### Instance-verdeling

```
Machine record
    |
    +-- GPU instances (0..N-1) -> Gedeelde ONNX GPU-sessies
    +-- CPU instances (N..M)   -> Gedeelde ONNX CPU-sessies
```

---

## Training en replay-routing

### SAC model assignments

SAC gebruikt een apart assignment-bestand buiten git om modellen aan trainer-workers te koppelen (`UT99_SESSIONS_DIR/sac-trainer-assignments.conf`).

De inventory bepaalt:
- Welke machines SAC mogen draaien (`sac_trainer_slots`)
- Welke machine de default fallback is (primary trainer)
- Welke machines assignments mogen ontvangen

### Replay sync flow

```
Producer-machine
    -> sync_replay.sh
    -> Leest primary trainer + SAC assignments
    -> Per model: rsync NPZ-batches naar assigned trainer
```

### Model-distributie

| Flow | Bron | Targets |
|---|---|---|
| ONNX sync | Trainer die model exporteert | Alle andere machines |
| SAC replay sync | Producer-machine | Assigned trainer per model |
| CSV shard scheduling | Primary trainer | Alle CSV writers |

---

## Deploy-flow

```
deploy.sh
    -> Parse servers config
    -> Filter machines
    -> Compileer UCC mod
    -> Build JAR
    -> Per machine (parallel):
        -> Kill processen
        -> Sync code
        -> Start bots
```

---

## Validatieregels

### Harde regels

| Regel | Betekenis |
|---|---|
| `machine_id` uniek | Stabiele clusteridentiteit |
| `hostname` uniek | Geen ambigue host-matching |
| `gpu_instances >= 0` | Negatieve capaciteit ongeldig |
| `cpu_instances >= 0` | Negatieve capaciteit ongeldig |
| `bc/sac/csv_*_slots >= 0` | Capaciteiten, geen flags |

### Praktische regels

| Regel | Reden |
|---|---|
| Poortreeksen mogen niet overlappen | Voorkomt instance-conflicten |
| Trainer-machines horen CUDA te hebben | BC en SAC zijn GPU-gericht |
| `env` klein en expliciet houden | Machinegedrag moet uitlegbaar blijven |
| Hostname stabiel houden | Runtime matching gebruikt hostname |

---

## Updateprocedure

1. Werk `resources/config/servers.json` bij.
2. Valideer de inventory.
3. Deploy naar doelmachines.

**Checklist:**

| Controle | Reden |
|---|---|
| BC/SAC trainer-slots correct? | Beinvloedt primary trainer en worker-pool |
| CSV writer-slots correct? | Beinvloedt shard-planning |
| Poortbases en step correct? | Voorkomt poortconflicten |
| GPU/CPU instances correct? | Beinvloedt runtime instance-verdeling |
| Speed en style correct? | Beinvloedt trainingsphysica |
