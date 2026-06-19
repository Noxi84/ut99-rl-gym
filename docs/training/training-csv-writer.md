# Gedistribueerde CSV-generatie

CSV-generatie voor BC-training verdeelt het werk over meerdere servers met ZIP-level sharding, per-model caching, shard manifests en throughput-gewogen scheduling.

---

## End-to-end overzicht

```
 DEV machine                          CSV writers                          TRAINER (4090)
 ============                         ===========                          ==============

 json-recording-sessions/             json-recording-sessions/
 +-- rl_pawn/*.zip      --rsync-->  (alleen toegewezen ZIPs)             csv-training-data/
                                                                          +-- rl_pawn/
                                       csv-training-data-staging/         |   +-- data_part001.csv
                                       +-- run_<id>/rl_pawn/<shard>/     |   +-- data_part002.csv
                                           +-- data_part00001.csv         |   +-- .ziphash
                                           +-- data_part00002.csv         +-- .throughput
                                           +-- manifest.json
                                                                          csv-generation-history/
                                                                          +-- run_<id>/
                                                                              +-- rl_pawn_4090_s0.json
                                                                              +-- rl_pawn_3070_s0.json
                                                                              +-- ...
```

---

## Pipeline

```
 [1] ZIP op dev   [2] Per-model     [3] Shard plan     [4] Sync ZIPs   [5] CSV generatie  [6] Sync results   [7] Publish + history
                      hash check        + throughput       per shard       per shard          naar trainer
 +----------+    +------------+    +---------------+   +----------+   +--------------+   +--------------+   +------------------+
 | zip dirs |--->| sha256 per |--->| inventory     |-->| rsync    |-->| shard JVM    |-->| rsync terug  |-->| per-model publish|
 | -> .zip  |   | model vs   |   | throughput-    |   | per shard|   | --run-id     |   | staging ->   |   | manifest -> hist |
 |          |   | .ziphash   |   | gewogen        |   | alleen   |   | --shard-id   |   | trainer      |   | throughput update|
 +----------+   | per model  |   | bin-packing    |   | toegew.  |   | manifest.json|   | inbox        |   | hash opslaan     |
                +------------+   +---------------+   | ZIPs     |   +--------------+   +--------------+   +------------------+
                 skip ongewijzigd                     +----------+    per model seq,
                 per model                                            shards parallel
```

---

## Serversconfiguratie

Het veld `roles.csv_writer_slots` in `resources/config/servers.json` bepaalt beschikbaarheid als CSV writer en hoeveel concurrent shard JVMs erop draaien.

| Server | csv_writer_slots | Rol |
|---|---|---|
| 4090 | 4 | Primary trainer + writer |
| 4070 | 4 | Writer + bot-runner |
| 3070 | 2 | Writer + bot-runner |
| 2070 | 0 | bot-runner (geen writer) |
| P15v | 1 | Writer + bot-runner |

---

## Per-model hash check (partial reruns)

Elke modelsubdirectory heeft een `.ziphash` bestand op de trainer. Bij het starten van CSV-generatie wordt per model een SHA-256 hash berekend over alle ZIPs op dev en vergeleken met de opgeslagen hash.

```
 sha256(rl_pawn/*.zip) --+--- hash gelijk + CSVs aanwezig ---> SKIP (model ongewijzigd)
                         |
                         +--- hash verschilt of geen CSVs ---> PROCESS
```

Ongewijzigde modellen worden volledig overgeslagen. Bij publicatie worden alleen verwerkte modellen vervangen; bestaande CSVs van ongewijzigde modellen blijven behouden.

---

## Virtual workers en shard planning

### Virtual worker pool

Elke fysieke writer met `csv_writer_slots=N` wordt uitgevouwen tot N virtual workers. Elke virtual worker draait als aparte JVM.

```
 Fysieke machines               Virtual workers          Heap per JVM
 ==================             ===============          ============
 4090 (slots=4, 64 GB)  ---->   vw0 (4090_s0)           12 GB
                                vw1 (4090_s1)           12 GB
                                vw2 (4090_s2)           12 GB
                                vw3 (4090_s3)           12 GB
 4070 (slots=4, 32 GB)  ---->   vw4 (4070_s0)            6 GB
                                vw5 (4070_s1)            6 GB
                                vw6 (4070_s2)            6 GB
                                vw7 (4070_s3)            6 GB
 3070 (slots=2, 32 GB)  ---->   vw8 (3070_s0)           12 GB
                                vw9 (3070_s1)           12 GB
 2070 (slots=0)         ---->   (geen writer)
 P15v (slots=1, 32 GB)  ---->   vw10 (p15v_s0)          24 GB

 Heap = 75% van fysiek RAM / csv_writer_slots
```

### Throughput-gewogen bin-packing

ZIPs worden per model verdeeld over virtual workers met greedy bin-packing:

```
 Zonder throughput-data (eerste run):
   Criterium: minimaliseer bytes per virtual worker
   Effect: gelijke byte-verdeling over alle workers

 Met throughput-data (volgende runs):
   Criterium: minimaliseer geschatte verwerkingstijd per virtual worker
   geschatte_tijd = toegewezen_bytes / (machine_throughput / slots)
   Effect: snellere machines krijgen proportioneel meer ZIPs
```

### Verwerking per model

Modellen worden sequentieel verwerkt. Binnen elk model draaien alle shards parallel:

```
 rl_pawn:
   4090_s0 --> shard (X ZIPs) --+
   4090_s1 --> shard (X ZIPs) --+
   4070_s0 --> shard (X ZIPs) --+-- alle JVMs parallel, wacht op alle
   3070_s0 --> shard (X ZIPs) --+
   p15v_s0 --> shard (X ZIPs) --+
   ...                          +
```

Als een server niet bereikbaar is (SSH ping faalt), wordt die overgeslagen. Resterende workers krijgen alle ZIPs.

---

## JVM heap en GC

### Automatische heap sizing

| Stap | Mechanisme |
|---|---|
| RAM opvragen | `/proc/meminfo` per reachable writer via SSH |
| Heap berekenen | 75% van fysiek RAM / `csv_writer_slots` |
| JVM flags | `-Xmx<heap>m -Xms512m -XX:+UseG1GC` |
| GC logging | `-Xlog:gc*:file=/tmp/csv_gc_<model>_<shard>.log` |

### ZIP-level sharding en memory

De pipeline verwerkt ZIPs sequentieel binnen een shard. Memory peak per JVM = grootte van een enkele ZIP, niet alle ZIPs samen.

```
 ZIP 1 --> lees --> filter --> schrijf --> VRIJGEVEN
 ZIP 2 --> lees --> filter --> schrijf --> VRIJGEVEN
 ZIP 3 --> lees --> filter --> schrijf --> VRIJGEVEN

 Peak heap: max(ZIP_1, ZIP_2, ZIP_3) + vaste overhead
```

---

## CSV pipeline per shard

Elke shard JVM verwerkt zijn toegewezen ZIPs sequentieel door 6 fasen.

### Fase 1: ZIP laden

```
 ZIP bestand
   |
   v
 ZIP entries itereren (streaming)
   |
   v
 Per entry: GameState JSON -> GameStateDto conversie
   |
   v
 List<GameStateDto> per ZIP
```

Conversie gebeurt direct tijdens het lezen -- nooit tegelijk ruwe JSON en DTOs in geheugen.

### Fase 2: Frame filtering

```
 Ruwe frames
   |
   +-- Null frames verwijderen
   +-- Dode frames verwijderen (health <= 0)
   +-- Respawn cooldown (1000 ms na dood)
   +-- Idle blokken verwijderen (stilstand > 400 ms)
   |
   v
 Gefilterde frames
```

### Fase 3: Groepering en balancering

```
 Gefilterde frames
   |
   +-- Groepeer in 1-seconde buckets (op elapsed time)
   +-- Balanceer frames binnen elke bucket
   +-- Verrijk met framenummers
   +-- Verwijder identieke buckets (zelfde positie + rotatie)
   |
   v
 LinkedHashMap<seconde, List<GameStateDto>>
   |
   +-- Flatten naar lineaire lijst
   +-- Grouped map vrijgeven
   |
   v
 sessionFrames (platte lijst)
```

### Fase 4: Sliding window + validatie + deduplicatie

```
 sessionFrames (N frames)
   |
   v
 Sliding window [i .. i+windowSize)    <-- schuift van frame 0 tot N-windowSize
   |
   +-- Window validatie (model-specifiek)
   |     ACCEPT --> doorgaan
   |     SKIP   --> volgende window
   |     STOP   --> stop iteratie
   |
   +-- Bucket-deduplicatie op canoniek sample
   |     State bucket key: afgeronde featurewaarden --> max 4 rijen per bucket
   |     Afgewezen --> volgende window
   |
   v
 Geaccepteerd canoniek sample
```

Dedup bucket key voor `rl_pawn`: locationX, locationY, hasFlag, viewRotationX.

### Fase 5: Augmentatie

Per geaccepteerd window worden meerdere varianten gegenereerd door synthetische yaw-rotaties:

```
 Canoniek sample (echte opname)
   |
   +-- Pre-compute canonieke featurewaarden: float[frame][feature]
   |
   +-- Identity variant (origineel)                       --> CSV rij
   +-- Augmented variant 1 (yaw +45 graden)               --> CSV rij
   +-- Augmented variant 2 (yaw -45 graden)               --> CSV rij
   +-- ...                                                --> CSV rij
   +-- Augmented variant 7                                --> CSV rij
```

Het `rl_pawn` model gebruikt 8 varianten per window: 1 identity + 7 yaw-rotaties (45-graden stappen).

| Feature-categorie | Herberekend bij augmentatie |
|---|---|
| Egocentrische vlag (flag_sin, flag_cos, ...) | Ja |
| Egocentrische spelers (enemy1_sin, ...) | Ja |
| Collision ring | Ja |
| Snelheid projectie | Ja |
| View rotatie | Ja |
| Dodge richting | Ja |
| Niet-augmented (health, hasFlag, speed, ...) | Nee (cached) |

Identity varianten hergebruiken de pre-computed canonieke waarden. Alleen augmented varianten herberekenen yaw-gevoelige features.

### Fase 6: CSV serialisatie

```
 Per variant:
   +-- Timeline features: per frame in window
   |     feature1_F1; feature1_F2; ... ; feature1 (laatste frame geen suffix)
   |
   +-- Target features: via target projector
   |     target1; target2; ...
   |
   v
 Enkele CSV-rij (puntkomma-gescheiden)
   |
   v
 RollingCsvWriter
   +-- Rotatie naar nieuw bestand bij > 50 MB per part
   +-- data_part00001.csv, data_part00002.csv, ...
   +-- Header herhaald per part
```

Formattering: float features 6 decimalen (`0.123456`), boolean features `0`/`1`, scheidingsteken `;`.

---

## Shard manifest

Elke shard schrijft een `manifest.json` naar zijn output directory:

```
 manifest.json
 +-- run_id: "run_20260405_123456"
 +-- model: "rl_pawn"
 +-- shard_id: "4090_s0"
 +-- start_time / end_time
 +-- status: "complete" of "failed"
 +-- zips[]:
 |     +-- zip_name, zip_bytes
 |     +-- frames_in, buckets
 |     +-- rows_out, runtime_ms
 +-- totals:
       +-- zip_count, total_zip_bytes
       +-- total_frames_in, total_rows_out
       +-- total_csv_parts, total_csv_bytes
       +-- runtime_ms, peak_heap_mb
```

Het manifest wordt geschreven in een `finally`-blok: bij succes met `status=complete`, bij crash met `status=failed`.

---

## Run history en throughput tracking

Na succesvolle publicatie worden alle shard manifests gekopieerd naar een permanente history directory op de trainer.

```
 csv-generation-history/
 +-- run_20260405_123456/
 |     +-- rl_pawn_4090_s0.json
 |     +-- rl_pawn_3070_s0.json
 |     +-- ...
 +-- run_20260406_091500/
       +-- ...
```

Uit de manifests van de laatst voltooide run wordt per machine de throughput berekend en opgeslagen in `csv-training-data/.throughput`:

```
 .throughput (tekstbestand, space-separated):
 4090 500000         <-- 500.000 bytes/sec
 3070 300000         <-- 300.000 bytes/sec
 p15v 200000         <-- 200.000 bytes/sec
```

Bij de volgende run gebruikt de shard planner deze throughput-waarden als gewicht in de bin-packing.

---

## Directory layout

| Directory | Machine | Levensduur | Doel |
|---|---|---|---|
| `json-recording-sessions/rl_pawn/` | Dev + writers | Permanent | ZIP-bronbestanden |
| `csv-training-data-staging/<run>/rl_pawn/<shard>/` | Writer | Per run | Shard CSV output + manifest |
| `csv-training-data-inbox/<run>/rl_pawn/<shard>/` | Trainer | Per run | Verzamelpunt na sync |
| `csv-training-data/rl_pawn/` | Trainer | Permanent | Finale CSVs voor BC-training |
| `csv-training-data/rl_pawn/.ziphash` | Trainer | Permanent | Per-model ZIP hash (partial reruns) |
| `csv-training-data/.throughput` | Trainer | Permanent | Throughput per machine (scheduling) |
| `csv-generation-history/<run>/` | Trainer | Permanent | Shard manifests |
| `/tmp/csv_shard_<run>_rl_pawn_<shard>.txt` | Writer | Per run | ZIP-list per shard |
| `/tmp/csv_gc_rl_pawn_<shard>.log` | Writer | Persistent | GC diagnostiek |
| `/tmp/deploy-logs/csv_rl_pawn_<shard>.log` | Dev | Per run | Shard stdout/stderr |

Shard ID formaat: `<machine_id>_s<slot_number>` (bijv. `4090_s0`, `3070_s0`).

CSV-bestanden worden bij publicatie hernummerd met zero-padding: `data_part001.csv`, `data_part002.csv`, etc.

---

## Cleanup

`clean-experience.sh --clean-csv` verwijdert op alle servers:

- `csv-training-data/`
- `csv-training-data-staging/`
- `csv-training-data-inbox/`
- `/tmp/csv_shard_*.txt`

Run history (`csv-generation-history/`) en GC logs worden niet automatisch opgeruimd.

---

## Betrokken scripts

| Bestand | Rol |
|---|---|
| `resources/config/servers.json` | `roles.csv_writer_slots` configuratie per machine |
| `scripts/deploy/common.sh` | Server parsing, CSV writer ontdekking, SSH/rsync wrappers |
| `scripts/deploy/prepare-csv.sh` | Orchestrator: hash check, shard plan, sync, launch, publish, history |
| `scripts/deploy/clean-experience.sh` | Cleanup staging/inbox/shard bestanden |
