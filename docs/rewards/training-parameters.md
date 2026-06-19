# Training Parameters

Referentie van BC en SAC configuratie voor het joint `rl_pawn` model. Bron-van-waarheid zijn de JSON-bestanden in `resources/models/rl_pawn/`.

---

## SAC Hyperparameters (`resources/models/rl_pawn/sac.json`)

| Parameter | Waarde | Toelichting |
|---|---:|---|
| `lr_actor` | 5e-5 | Gehalveerd t.o.v. critic na drift-naar-BC |
| `lr_critic` / `lr_temperature` | 1e-4 | |
| `gamma` | 0.97 | Korter horizon dan klassiek 0.99 |
| `tau` | 0.005 | Target-network soft-update |
| `batch_size` | 1536 | |
| `replay_buffer_capacity` | 200000 | |
| `min_buffer_size` | 10000 | |
| `actor_update_period` | 2 | Actor steps per critic step |
| `temperature_init` | 0.02 | |
| `auto_temperature` | true | |
| `target_entropy` | -9.0 | Som over 10 action-dims (zie `target_entropy_per_dim`) |
| `temperature_min` / `_max` | 0.001 / 1.0 | |
| `bc_alpha` | 0.1 | BC-anchor pull |
| `bc_alpha_anneal_steps` | 50000 | |
| `bc_log_std_anchor_alpha` | 0.0 | |
| `log_std_init` | -1.0 | |
| `log_std_min` / `_max` | -5.0 / 2.0 | |
| `action_smoothness_alpha` | 2.0 | Jitter penalty |
| `action_bias_alpha` | 5.0 | Bias-correctie |
| `experience_record_interval` | 1 | Geen sub-sampling: elke 30 Hz-tick (fire-onsets niet missen) |
| `critic_warmup_steps` | 10000 | |
| `min_steps_before_export` | 10000 | |
| `export_interval_steps` | 500 | |
| `max_grad_norm` | 1.0 | |
| `reward_normalization` | true | |

---

## BC Hyperparameters (`resources/models/rl_pawn/bc.json`)

| Parameter | Waarde |
|---|---:|
| `batch_size` | 8192 |
| `lr` | 0.001414 |
| `weight_decay` | 0.0001 |
| `grad_clip_norm` | 1.0 |
| `label_smoothing` | 0.02 |
| `pretrain_steps` | 1000 |
| `warmup_steps` | 200 |
| `save_every_steps` | 100 |
| `log_every_steps` | 50 |
| `early_stop_patience` | 15 |
| `val_split` | 0.1 |
| `data_loader.num_workers` | 2 |
| `data_loader.persistent_workers` | true |
| `data_loader.prefetch_factor` | 2 |
| `data_loader.pin_memory` | false |

---

## DualKPI DeltaGate

DeltaGate-promotie leeft in `resources/models/rl_pawn/export_gate.json`. Zie [delta-gate.md](../training/delta-gate.md) voor het volledige mechanisme.

De gate is volledig ratio-based: elke KPI wordt uitgedrukt als `ratio = current / baseline`. PROMOTE vereist dat alle 3 ratios boven hun `promote_*_min_ratio` zitten (AND); ROLLBACK triggert wanneer een ratio onder zijn `rollback_*_max_ratio` zakt (OR). De cadence is match-aligned (`matches_per_eval_cycle`), geen wall-clock timer.

| Parameter | Waarde | Beschrijving |
|---|---:|---|
| `promote_combat_score_min_ratio` | 0.85 | combat_score-ratio drempel voor PROMOTE |
| `promote_aim_min_ratio` | 0.85 | shots_on_target_rate-ratio drempel voor PROMOTE |
| `promote_movement_min_ratio` | 0.80 | flag_score-ratio drempel voor PROMOTE |
| `promote_window_cycles` | 1 | Opeenvolgende cycli boven drempel voor PROMOTE |
| `rollback_combat_score_max_ratio` | 0.70 | combat_score-ratio onder dit → ROLLBACK |
| `rollback_aim_max_ratio` | 0.50 | shots_on_target_rate-ratio onder dit → ROLLBACK |
| `rollback_movement_max_ratio` | 0.40 | flag_score-ratio onder dit → ROLLBACK |
| `rollback_window_cycles` | 2 | Opeenvolgende cycli onder drempel voor ROLLBACK |
| `matches_per_eval_cycle` | 5 | MATCH_ENDED-count per eval-cyclus (match-aligned) |
| `consecutive_rollback_adam_wipe_threshold` | 3 | Adam-wipe escalatie na N opeenvolgende rollbacks |

---

## Reward-configuratie

De reward-shape leeft als rewardgroups deep-merge in `resources/models/rl_pawn/rewards.json`. Per groep (default, Attack, Cover, Defend, DeathMatch) wordt elk reward-blok apart geconfigureerd:

```json
"rewardgroups": {
  "default": {
    "name": "Default",
    "rewards": {
      "combat_event": {
        "kind": "sparse",
        "owner": "shooting",
        "weights": { "frag": 0.5, "death": -1.5, "shot_on_target_bonus_primary": 0.45, "shot_on_target_bonus_alt": 0.7 },
        "shot_min_aim_score": 0.85,
        "shot_precision_exponent": 2.0
      },
      "damage_delta": { "weights": { "dealt_per_hp": 0.0625 } }
    }
  },
  "rewardgroup0": { "name": "Attack", "rewards": { ... } }
}
```

Per-bot rewardgroups-selectie via `gameplay.json -> ai_bots[].rewardgroups`.

| Reward-doc | Verwijzing |
|---|---|
| Sparse events | [sparse-events.md](sparse-events.md) |
| Reward-architectuur | [reward-architecture.md](reward-architecture.md) |

### Globale keys in `rewards.json`

| Key | Waarde | Beschrijving |
|---|---:|---|
| `match_duration` | 60000 | Voor time-multiplier op flag-events |
| `reward_breakdown_enabled` | true | Per-window logging |
| `reward_breakdown_window_size` | 200 | Ticks per logwindow |
| `projectile_speed_flak_primary_uu` | 2700.0 | UTChunk maxSpeed |
| `projectile_speed_flak_secondary_uu` | 1200.0 | FlakSlug speed |
