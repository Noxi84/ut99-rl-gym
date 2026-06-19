# Deploy-configuratie

`./scripts/deploy.sh` neemt geen command-line arguments -- alle instellingen staan in `resources/config/deploy.json`. Bewerk dat bestand en voer uit:

```bash
./scripts/deploy.sh
```

UCC compile, JAR build en rsync-based code sync gebeuren altijd (goedkoop en correctness-critical). Alle andere acties zijn gegate door `deploy.json`.

---

## Structuur van deploy.json

```jsonc
{
  "hosts": [],                          // [] = alle servers; substring filter (bv. ["4090", "3070"])
  "restart-bots": true,                 // false = sync code only, raak draaiende bots niet aan
  "clean-logs": true,                   // wipe /tmp/ut99-multi/*.log per server tijdens restart
  "extract-map-bounds": false,          // run extract-map-bounds.sh --discover voor code sync
  "recordings_sync": {
    "enabled": false                    // sync recordings van dev naar recording server
  },
  "models": {
    "rl_pawn": {
      "clean-experience": false,
      "prepare-training-csv": false,
      "keep-existing-model": true,
      "train-bc": false,
      "train-sac": true,
      "reset-sac-baseline": false,
      "reset-sac-to-bc-baseline": false,
      "reset-current-to-last-champion": false,
      "reset-champions": false,
      "convert-from-jsons": false,
      "replay-export": false
    }
  }
}
```

---

## Per-model flags

Elke flag moet letterlijk aanwezig zijn (geen fallbacks; missing keys -> crash):

| Flag | Effect |
|---|---|
| `clean-experience` | Wipe `rl-replay-buffer/<key>/*.npz` op elke server |
| `prepare-training-csv` | Wipe `csv-training-data/<key>/` en regenereer via distributed CSV writers |
| `keep-existing-model` | Wipe `.pt`/`.onnx` niet -- BC hervat van bestaand checkpoint |
| `train-bc` | Run BC pre-training voor dit model |
| `train-sac` | Start SAC trainer voor dit model |
| `reset-sac-baseline` | Soft reset: SAC `best_mean_return` + `baseline_return` only (implies `train-sac: true`) |
| `reset-sac-to-bc-baseline` | Hard reset: wipe `_sac.pt` + `_sac_best.pt` + `_sac_checkpoint.pt` -- trainer valt terug op BC weights bij volgende start. Gebruik wanneer policy collapsed in lokaal optimum (mutually exclusive met `reset-sac-baseline`; implies `train-sac: true`) |
| `reset-current-to-last-champion` | Restore: kopieer de laatste **promoted** champion (`.pt` -> `_sac_best.pt`+`_sac.pt`, ONNX -> live + push naar alle servers), wis stale checkpoint/inflight/delta, herstart SAC bootstrappend van de champion. Bots worden gedeferd tot de champion-ONNX gepusht is. Gebruik wanneer current verstoord is en je terug wil naar champion i.p.v. BC. Hard-fail bij ontbrekende/arch-incompatibele champion. Implies `train-sac: true` + `clean-experience: true`; mutually exclusive met `reset-sac-baseline`, `reset-sac-to-bc-baseline`, `reset-champions`, `train-bc` |
| `reset-champions` | Wis `champions/<key>/` + `bundles.json` op alle servers en bootstrap een nieuwe champion vanaf BC baseline. Defert bot-start tot na champion-bootstrap |
| `convert-from-jsons` | Converteer JSON recordings naar .rec.gz binary format |
| `replay-export` | Volledige replay-pipeline: .rec.gz -> experience .npz met huidige reward config |

---

## Pipeline-volgorde (per deploy.sh run)

```
1. Dev-side (altijd)
   +-- Compile UCC
   +-- Build JAR
   +-- Extract map bounds (alleen bij extract-map-bounds: true)

2. Per server in parallel
   +-- Kill processen (bots als restart-bots; BC/SAC trainers als train-bc=true)
   +-- Per-model wipes (experience / model files / CSVs)
   +-- rsync code
   +-- Start bots (als restart-bots en niet retraining)

3. Wacht op ucc-bin startup
   (overgeslagen als restart-bots=false of retraining actief)

4. prepare-csv.sh
   Voor elk model met prepare-training-csv: true

5. train-bc.sh
   Voor alle modellen met train-bc: true
   Parallel over bc_trainer_slots (uit servers.json)
   Bots op niet-trainer machines starten parallel met BC
   Trainer-machine bots starten zodra hun BC-slot vrijkomt
   Zodra BC klaar is: SAC early-start op vrije SAC trainer
   (overgeslagen als reset-sac-baseline flag actief)
```

---

## Veelvoorkomende scenarios

### Sync code + restart bots

```jsonc
{ "hosts": [], "restart-bots": true, "clean-logs": true,
  "extract-map-bounds": false,
  "models": { "rl_pawn": { /* alle flags false */ } } }
```

### Sync only, raak niets aan

```jsonc
{ "hosts": [], "restart-bots": false, "clean-logs": false,
  "extract-map-bounds": false,
  "models": { "rl_pawn": { /* alle flags false */ } } }
```

### Volledige retrain

Alle modellen: `clean-experience: true`, `prepare-training-csv: true`, `train-bc: true`, `keep-existing-model: false`.

### Resume BC na feature slot toevoegen

`rl_pawn`: `train-bc: true`, `keep-existing-model: true`, `prepare-training-csv: true`.

### Hard reset SAC naar BC weights

Gebruik wanneer action-mode saturation het model uit gradient-bereik duwt en exploration niet kan herstellen.

`rl_pawn`: `reset-sac-to-bc-baseline: true` (auto-implies `train-sac: true`).

### Restore current naar laatste champion

Gebruik wanneer het live SAC-model verstoord is (KPI-regressie, collapse) en je *niet* helemaal naar BC wil terugvallen maar naar de laatste **promoted** champion. Dit is de offline, "harde" tegenhanger van de DeltaGate's in-trainer actor-only rollback: het pusht de champion-ONNX naar álle bots en wist de replay-buffer.

`rl_pawn`: `reset-current-to-last-champion: true` (auto-implies `train-sac: true` + `clean-experience: true`).

De pipeline: stopt SAC → champion `.pt` in de bootstrap-ladder (`_sac_best.pt`+`_sac.pt`) → champion-ONNX over de live ONNX + push naar alle servers → wis stale checkpoint/inflight/delta → herstart SAC (bootstrapt van champion) → start bots (gedeferd tot de ONNX gepusht is, zodat ze op de champion opkomen en de geschoonde buffer met champion-experience vullen). Faalt hard als er geen promoted champion is of als `model.json`/`features.json` sinds de promotie wijzigde (incompatibele ONNX) — gebruik dan `reset-sac-to-bc-baseline`.

---

## Notities

- Server PCs hebben geen git access -- `deploy.sh` rsynct vanaf dev.
- Server inventory staat in `resources/config/servers.json`.
- `deploy.json` is gecommit; tweaken voor een specifieke run verschijnt in `git status`. Revert naar de gecommitte default na een non-standard run.
- BC training loopt automatisch parallel op basis van `bc_trainer_slots` in `servers.json`.
- Validatie is strikt: elk model in `models/index.json` moet voorkomen in `deploy.json`, elke flag moet een letterlijke `true`/`false` zijn. Missing keys crashen `deploy.sh`.
- `clean-logs: true` verwijdert `/tmp/ut99-multi/instance_*.log`, `orchestrator.log` en `sync_replay.log` op elke gedeployde server tijdens restart.
