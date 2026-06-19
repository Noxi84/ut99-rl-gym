# Scripts & Main Classes Reference

Volledige tabel van alle shell-scripts en entry points. Zie [deploy-config.md](deploy-config.md) voor deploy.json configuratie en [platform-architecture.md](platform-architecture.md) voor architectuur.

---

## Top-level scripts

| Script | Wat het doet | Aanroep |
|---|---|---|
| `scripts/deploy.sh` | Main deploy orchestrator. Compileert UCC op dev, deployt parallel naar servers, optioneel retrain. Leest alle settings uit `resources/config/deploy.json`. | `./scripts/deploy.sh` |
| `scripts/install-ut99.sh` | Automated OldUnreal UT99 installer + headless config | `./scripts/install-ut99.sh` |

---

## Deploy subscripts (`scripts/deploy/`)

Allemaal aanroepbaar standalone of via `deploy.sh`.

| Script | Wat het doet | Aanroep |
|---|---|---|
| `common.sh` | Gedeelde functies (SSH wrappers, server parsing, logging, role-resolution). Source door alle andere subscripts. | `source scripts/deploy/common.sh` |
| `load-deploy-config.sh` | Sourced helper: leest `deploy.json` (via `load_deploy_config.py`) en exporteert `DEPLOY_HOSTS`, `DEPLOY_RESTART_BOTS`, `DEPLOY_CLEAN_LOGS`, plus per-model arrays. Valideert velden + past implicit rules toe (`reset-*-baseline => train-*`). | `source scripts/deploy/load-deploy-config.sh` |
| `load_deploy_config.py` | Python validator + JSON loader voor deploy.json | (gebruikt door load-deploy-config.sh) |
| `kill-processes.sh` | Kill bot/trainer processen op een of alle servers | `bash scripts/deploy/kill-processes.sh --all [--kill-sac]` |
| `compile-ucc.sh` | Compileer UCC op dev, update fallback `.u`. | `bash scripts/deploy/compile-ucc.sh` |
| `extract-map-bounds.sh` | Run UCC batchexport -> parse T3D -> schrijf `resources/config/maps/<map>.json`. Modes: active / named / `--all` / `--discover`. | `bash scripts/deploy/extract-map-bounds.sh [--discover]` |
| `sync-code.sh` | Rsync code + pre-built JAR + kopieer `.u` naar UT99 System dirs + deploy INI. Geen remote build. | `bash scripts/deploy/sync-code.sh --host H --user U --pass P` |
| `sync-recordings.sh` | Push dev's `from-dev/*.rec.gz` naar recording_server (idempotent) | `bash scripts/deploy/sync-recordings.sh` |
| `clean-logs.sh` | Verwijder log-bestanden op een of alle servers | `bash scripts/deploy/clean-logs.sh --all` |
| `clean-experience.sh` | Verwijder `.npz` experience-bestanden (en optioneel models, CSVs, BC-cache). Globale flags + per-model wipes via `--wipe-experience-for`, `--wipe-models-for`, `--wipe-csv-for`, `--wipe-bc-cache-for`. | `bash scripts/deploy/clean-experience.sh --all [opties...]` |
| `prepare-csv.sh` | Distributed CSV-generatie: ZIP op dev -> distribueer naar CSV workers (volgens `csv_writer_slots`) -> genereer CSV shards -> sync terug naar trainer -> valideer + publish. Hash-check skipt als up-to-date. | `bash scripts/deploy/prepare-csv.sh [model_keys...]` |
| `train-bc.sh` | Run BC pre-training (blocking). Distribueert modellen over `bc_trainer_slots` automatisch. Honoreert `EARLY_START_SAC`/`EARLY_START_BOTS` env vars. | `bash scripts/deploy/train-bc.sh [model_keys...]` |
| `train-sac.sh` | Start/restart SAC training op trainer (tmux session). | `bash scripts/deploy/train-sac.sh [model_keys...]` |
| `reset-sac-baseline.sh` | Stop SAC, reset `best_mean_return`/`baseline_return` in checkpoint, restart SAC. | `bash scripts/deploy/reset-sac-baseline.sh [model_keys...]` |
| `reset-sac-to-bc-baseline.sh` | Hard reset: stop SAC, wipe alle SAC checkpoints + best ONNX. Trainer valt terug op BC weights bij volgende start. | `bash scripts/deploy/reset-sac-to-bc-baseline.sh [model_keys...]` |
| `reset_sac_checkpoint.py` | Checkpoint mutator: reset `best_mean_return` en `baseline_return`. | (gebruikt door reset-sac-baseline.sh) |
| `reset-sac-to-champion.sh` | Hard champion-restore (champion -> current): stop SAC, kopieer champion `.pt` in de bootstrap-ladder + champion ONNX over het live model + push naar alle servers, wipe stale checkpoint/buffer-state, restart SAC. Voert de deploy-flag `reset-current-to-last-champion` uit (impliceert `train-sac` + `clean-experience`). Helper: `train.common.restore_to_champion`. | `bash scripts/deploy/reset-sac-to-champion.sh [model_keys...]` |
| `replay-export.sh` | Volledige replay-pipeline per model: JSON -> .rec.gz (dev-side, cached) -> push naar recording_server -> genereer experience .npz (per-model, cached op corpus + reward config) -> mirror naar trainer. | `bash scripts/deploy/replay-export.sh [--force-convert=k1,k2] <model_keys...>` |
| `start-bots.sh` | Start `multi_instance.sh` in tmux op een of alle servers. | `bash scripts/deploy/start-bots.sh --all` |
| `status.sh` | Server status: CPU load, instances, GPU/CPU config, gamespeed, process counts (UCC, Java, BC, SAC, sync), tmux sessions, GPU-utilization + VRAM. | `bash scripts/deploy/status.sh [host-filter]` |

---

## Champion / self-play store (`train.common.*` op de trainer)

De champion-snapshot store wordt beheerd via Python CLI-modules **op de primary trainer** (default `desktop-4090.fritz.box`; formeel de machine met de hoogste `bc_trainer_slots + sac_trainer_slots` in `servers.json`). PROMOTE/ROLLBACK-beslissingen worden in-process gemaakt door de SAC trainer (DualKPIDeltaGate); deze CLI's zijn voor handmatige inspectie en beheer.

De store leeft alleen op de trainer (`<sessions>/models/champions/`), dus draai de commando's daar — vanuit de project-root, met de venv-interpreter. Vanaf dev via SSH:

```bash
ssh kris@desktop-4090.fritz.box \
  "cd /home/kris/projects/ut99neuralnet && .venv/bin/python3 -m train.common.champion_store list"
```

| Doel | Commando (na `.venv/bin/python3 -m`) |
|---|---|
| Snapshots per model (counter, tag, datum, fingerprint-compat, match-history) | `train.common.champion_store list [--model-key <mk>]` |
| Volledige `snapshot.json` van een champion | `train.common.champion_store show <mk>/<counter>` |
| Fingerprint-check over alle snapshots | `train.common.champion_store validate` |
| Handmatige snapshot van huidige ONNX (auto-counter, optionele tag; synct) | `train.common.champion_store create <mk> [--tag <tag>]` |
| Snapshot-directory wissen | `train.common.champion_store delete <mk> <counter>` |
| `bundles.json` (de rotating pool) printen | `train.common.champion_pool show` |
| Pool (re)bootstrappen vanuit trainingmodel ONNX (idempotent; synct) | `train.common.champion_pool bootstrap` |
| Promoted bundle handmatig toevoegen | `train.common.champion_pool promote <mk> <counter>` |
| Hele `champions/` tree naar alle servers rsyncen | `train.common.ChampionSync all` |

`create` / `bootstrap` / `promote` syncen standaard naar alle servers; voeg `--no-sync` toe om dat over te slaan.

**Champions wissen + opnieuw seeden:** zet `reset-champions: true` in `deploy.json` en draai `scripts/deploy.sh` — dat verwijdert `<sessions>/models/champions/` op de trainer en laat de SAC trainer de pool opnieuw bootstrappen uit de BC-baseline.

**Een bot aan een champion pinnen:** edit `resources/config/gameplay.json` met de hand — zet het `"snapshot"`-veld van de model-entry op `"current"` (live policy), `"<mk>/<counter>"` (vaste champion), of `"<mk>/newest"` (auto-roteert naar de nieuwste promoted bundle). Daarna `scripts/deploy.sh` (met `restart-bots: true`). Zie [self-play-architecture.md §6.2](self-play-architecture.md).

---

## Runtime scripts (`scripts/runtime/`)

Draaien op servers tijdens bot-operatie.

| Script | Wat het doet | Aanroep |
|---|---|---|
| `multi_instance.sh` | Bot orchestrator: N instances in 1 JVM, GPU/CPU split, start `sync_replay.sh`. | `bash scripts/runtime/multi_instance.sh [N]` |
| `sync_replay.sh` | Synct `.npz` (NORMAL mode) of `.rec.gz` (CAPTURE=true mode) experience naar trainer elke 30s, per-model, met `--remove-source-files`. | (gestart door multi_instance.sh) |

---

## Java entry points

| Entry point | Doel | Aanroep |
|---|---|---|
| MultiInstanceLauncher | Production entry point (JAR default main). N bot threads in 1 JVM met GPU/CPU split. | `java -jar java-aiplay-1.0.jar --instances=N --gpu-instances=G --display-base=20 --web-port-base=6080 --game-port-base=7777 --game-port-step=2 --udp-port-base=11000 --state-udp-port-base=11500` |
| GenerateTrainingCsvMain | Converteer JSON gameplay-recordings naar BC training CSVs. Distributed mode via `--source-dir`/`--output-dir`/`--zip-list-file`. | `java -cp java-aiplay-1.0.jar aiplay.GenerateTrainingCsvMain [model_keys...]` |
| GenerateExperienceFromRecordingsMain | Replay tool: lees `.rec.gz` -> genereer experience `.npz` met huidige reward config. | `java -cp java-aiplay-1.0.jar aiplay.GenerateExperienceFromRecordingsMain [model_keys...]` |
| ConvertJsonRecordingsToRecGzMain | Converteer JSON recordings naar binary `.rec.gz` format. | `java -cp java-aiplay-1.0.jar aiplay.ConvertJsonRecordingsToRecGzMain` |
| DiscoverMapsInRecordingsMain | Scan recording-sessions voor unieke mapnamen -> stdout. | `java -cp java-aiplay-1.0.jar aiplay.DiscoverMapsInRecordingsMain` |
| ExtractMapBoundsMain | Parse UT99 T3D level-exports -> schrijf per-map JSON config (bounds, symmetrie, spawn points). | `java -cp java-aiplay-1.0.jar aiplay.ExtractMapBoundsMain` |
| RecordLauncher | Records human gameplay (keyboard + game state) naar JSON. Aparte module java-liverecorder (fat-jar). | `java -jar java-liverecorder/target/java-liverecorder-1.0.jar` |

---

## Python entrypoints

```bash
# BC pre-training (CSV -> ONNX)
python -m train.rl.rl_pawn.trainBC

# SAC fine-tuning (off-policy, continuous + binary actions, target aux head)
python -m train.rl.rl_pawn.trainSAC
```

### Operationele notities

- SSH start in `/home/kris/`. Gebruik absolute paden of prefix met `cd /home/kris/projects/ut99neuralnet &&`.
- Python venv: `/home/kris/projects/ut99neuralnet/.venv/` op de primary trainer (4090).
- tmux sessions starten ook in `/home/kris/`, dus `cd` moet binnen de tmux command string.
- Alle BC trainers syncen ONNX (`.onnx` + `.onnx.data`) automatisch naar alle servers na elke save.

Voorbeeld tmux + SSH:

```bash
# Wachtwoord uit de niet-getrackte secrets.local.json (key ssh_password) — niet in git.
sshpass -p "$(jq -r .ssh_password /home/kris/projects/ut99neuralnet/resources/config/secrets.local.json)" \
  ssh -o StrictHostKeyChecking=no kris@desktop-4090.fritz.box \
  "tmux new-session -d -s mysession 'cd /home/kris/projects/ut99neuralnet && bash scripts/my_script.sh'"
```
