"""SAC training loop for the joint VR+shooting policy — the single low-level
policy in productie.

Includes:

- Per-head CAPS smoothness + per-head action_bias regularisers operating
  exclusively on the dims listed in ``cfg.action_smoothness_dims`` and
  ``cfg.action_bias_dims`` (joint config: ``[0, 1]`` = yaw/pitch only). Binary
  fire/altFire dims are exempt — applying CAPS to fire/altFire collapses
  fire-rate.
- Aux target loss: BC-supervised cross-entropy on the ``TargetHead`` logits,
  anchored by ``cfg.aux_target_alpha``.
- Multi-head critic support: ``cfg.critic_mode`` dispatches between shared
  single-Q and multi-head-Q Bellman/actor paths. Per-skill reward
  decomposition is pulled from the joint async batch provider extras.
- Dual-KPI DeltaGate: combat_score AND shots_on_target_rate both pass to
  promote; OR-rollback flags per-skill regression. Reads baselines from
  ``resources/models/rl_pawn/baseline.json``.
- Hard-fail probes: per-head + cross-head violations filtered by a 2-cycle
  window, fire+altFire collapse triggers immediate rollback. PyTorch FP32
  probe runs every export tick; ONNX FP16 probe runs in
  ``export_validation``.

This file owns the joint SAC orchestration in full; the only shared imports
are kernel primitives (sample_action, compute_critic_loss,
compute_sac_actor_loss, compute_aux_target_loss, soft_update,
clamp_temperature).
"""
from __future__ import annotations

import contextlib
import json
import shutil
import signal
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

import numpy as np
import torch
import torch.nn.functional as F

from train.common import ChampionSync, champion_pool, champion_store
from train.common.ModelSync import sync_onnx_to_servers
from train.common.TrainerLogger import log_print
from train.model.bc_sequence_network import export_actor_onnx
from train.rl.shared.delta_gate import DualKPIDeltaGate
from train.rl.shared.player_scores_eval import (
    KPI_COMBAT_SCORE, KPI_FLAG_SCORE, KPI_SHOTS_ON_TARGET_RATE, compute_delta,
    compute_delta_per_weapon, count_match_ends_since,
)
from train.rl.shared.sac_core.checkpoint_io import (
    copy_onnx_with_data, save_training_state, strip_compile_prefix,
)
from train.rl.shared.sac_core.networks import soft_update
from train.rl.shared.sac_core.replay_buffer import ReplayBuffer
from train.rl.shared.sac_core.sac_step import (
    clamp_temperature, compute_aux_target_loss, compute_critic_loss,
    compute_sac_actor_loss, compute_temperature_loss,
)

from train.rl.rl_pawn.trainSAC.bootstrap import bootstrap
from train.rl.rl_pawn.trainSAC.config_loader import (
    JOINT_MODEL_KEY, JointSACBundle, _CONFIG_DIR,
)
from train.rl.rl_pawn.trainSAC.export_validation import (
    rollback_onnx, sample_replay_pair, validate_actor_sane,
    validate_exported_onnx_sane,
)
from train.rl.rl_pawn.trainSAC.joint_batch_provider import (
    JointAsyncBatchProvider,
)
from train.rl.rl_pawn.trainSAC.probes import (
    JointFeatureIndices, ProbeViolationTracker,
)
from train.rl.rl_pawn.trainSAC.strata import (
    StratificationFeatureIndices,
)


_shutdown_requested = False


def _handle_signal(signum, frame):
    global _shutdown_requested
    _shutdown_requested = True


@dataclass
class JointBaselines:
    """Decoupled baselines for the dual-KPI DeltaGate.

    Loaded from ``resources/models/rl_pawn/baseline.json`` (a Fase 4
    user-managed artefact). When absent we fall back to (1.0, 1.0) so the
    gate's ratio-math doesn't divide by zero — but every ratio is then in
    *absolute* units, which is meaningless for promotion gating. The trainer
    logs this loud-and-clear so it's obvious the file is missing.

    ``per_weapon`` (optioneel) bevat per-wapen baselines voor multi-weapon
    mode. Wanneer aanwezig schakelt de training_loop over op
    DualKPIDeltaGate.evaluate_per_weapon() — AND-promotion over alle actieve
    wapens. Schema in baseline.json:
        "per_weapon_baselines": {
            "PulseGun": {"combat_score": X, "aim_rate": Y, "movement_score": Z},
            "Eightball": {...},
            ...
        }
    """
    combat_score: float
    shots_on_target_rate: float
    movement_flag_score: float
    source: str   # "baseline.json" | "placeholder"
    per_weapon: Optional[Dict[str, Dict[str, float]]] = None


def _load_baselines(logger) -> JointBaselines:
    """Read decoupled baselines uit baseline.json.

    Full-joint schema: velden ``decoupled_movement_flag_score``,
    ``decoupled_shooting_combat_score`` en
    ``decoupled_vr_shots_on_target_rate``.
    Mogen ``null`` zijn — Fase 4b populeert ze via baseline-meting over
    ``measurement_window_count`` tijdsvensters van ``measurement_window_minutes``
    minuten. Null → 'placeholder' modus met luide waarschuwing zodat
    promote/rollback-output direct herkenbaar is als absoluut-niet-baseline-
    relatief.

    Strict-load voor non-null waardes: missing key (anders dan ``null``-value)
    is een schema-bug en crasht.
    """
    baseline_path = _CONFIG_DIR / "baseline.json"
    if not baseline_path.exists():
        log_print(
            logger,
            f"WARNING: baseline.json not found at {baseline_path} — using "
            f"placeholder (1.0, 1.0, 1.0). DualKPIDeltaGate ratios will be in "
            f"absolute units; do NOT trust promote/rollback decisions until "
            f"user fills baseline.json (Fase 4 sectie 10.2).",
        )
        return JointBaselines(1.0, 1.0, 1.0, "placeholder")
    with baseline_path.open() as f:
        data = json.load(f)

    schema_keys = (
        "decoupled_movement_flag_score",
        "decoupled_shooting_combat_score",
        "decoupled_vr_shots_on_target_rate",
    )
    for key in schema_keys:
        if key not in data:
            raise RuntimeError(
                f"baseline.json missing required key {key!r}; "
                f"Fase 4a schema requires {schema_keys}"
            )
    combat_val = data["decoupled_shooting_combat_score"]
    aim_val = data["decoupled_vr_shots_on_target_rate"]
    movement_val = data["decoupled_movement_flag_score"]
    if combat_val is None or aim_val is None or movement_val is None:
        log_print(
            logger,
            f"WARNING: baseline.json bevat null waardes "
            f"(decoupled_shooting_combat_score={combat_val}, "
            f"decoupled_vr_shots_on_target_rate={aim_val}, "
            f"decoupled_movement_flag_score={movement_val}) — Fase 4b "
            f"baseline-meting nog niet uitgevoerd. Gebruik placeholder "
            f"(1.0, 1.0, 1.0); promote/rollback ratios zijn dan absoluut. "
            f"DO NOT trust gating-output tot baseline.json gevuld is.",
        )
        return JointBaselines(1.0, 1.0, 1.0, "placeholder-null-baseline")

    # Optioneel: per-weapon baselines voor multi-weapon mode.
    # Schema: per_weapon_baselines: {weapon: {combat_score, aim_rate, movement_score}}.
    # Wanneer aanwezig, schakelt de gate over op AND-promotion over wapens.
    per_weapon = data.get("per_weapon_baselines")
    if per_weapon is not None:
        if not isinstance(per_weapon, dict) or not per_weapon:
            raise RuntimeError(
                f"baseline.json: per_weapon_baselines must be a non-empty dict, "
                f"got {type(per_weapon).__name__}"
            )
        for weapon, kpis in per_weapon.items():
            for required in ("combat_score", "aim_rate", "movement_score"):
                if required not in kpis:
                    raise RuntimeError(
                        f"baseline.json: per_weapon_baselines[{weapon!r}] "
                        f"missing required KPI {required!r}; expected keys "
                        f"combat_score, aim_rate, movement_score"
                    )
        log_print(logger,
            f"Per-weapon baselines loaded for {len(per_weapon)} weapon(s): "
            f"{sorted(per_weapon.keys())} — DualKPIDeltaGate will use "
            f"AND-promotion over actieve wapens.")
    return JointBaselines(
        combat_score=float(combat_val),
        shots_on_target_rate=float(aim_val),
        movement_flag_score=float(movement_val),
        source=str(baseline_path),
        per_weapon=per_weapon,
    )


def _persist_promoted_baselines(
    logger,
    combat_score: float,
    shots_on_target_rate: float,
    movement_flag_score: float,
    per_weapon: Optional[Dict[str, Dict[str, float]]] = None,
    champion_step: Optional[int] = None,
) -> None:
    """Write promoted DualKPI baselines back to baseline.json.

    DualKPIDeltaGate.update_baselines() is intentionally in-memory. The joint
    trainer needs this persistence layer so restart/redeploy keeps comparing
    candidates against the last promoted champion instead of stale bootstrap
    numbers from an old baseline measurement.
    """
    baseline_path = _CONFIG_DIR / "baseline.json"
    data: dict = {}
    if baseline_path.exists():
        with baseline_path.open() as f:
            data = json.load(f)

    data["decoupled_shooting_combat_score"] = float(combat_score)
    data["decoupled_vr_shots_on_target_rate"] = float(shots_on_target_rate)
    data["decoupled_movement_flag_score"] = float(movement_flag_score)
    data["measurement_date"] = time.strftime("%Y-%m-%d", time.gmtime())
    data["_last_promote_update"] = {
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "DUAL_KPI_PROMOTE",
        "champion_step": champion_step,
        "combat_score": float(combat_score),
        "shots_on_target_rate": float(shots_on_target_rate),
        "movement_flag_score": float(movement_flag_score),
        "note": "shots_on_target_rate is per-minute KPI used by DualKPIDeltaGate",
    }
    if per_weapon is not None:
        data["per_weapon_baselines"] = {
            str(weapon): {
                "combat_score": float(kpis["combat_score"]),
                "aim_rate": float(kpis["aim_rate"]),
                "movement_score": float(kpis["movement_score"]),
            }
            for weapon, kpis in sorted(per_weapon.items())
        }

    tmp_path = baseline_path.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(data, indent=2) + "\n")
    tmp_path.replace(baseline_path)
    log_print(logger, f"DUAL_KPI_BASELINE_PERSISTED: {baseline_path}")


def _read_current_kpis(logger, since_ts_unix_s: float) -> tuple[Optional[float], Optional[float], Optional[float]]:
    """Pull live combat_score + shots_on_target_rate + flag_score from PLAYER_SCORES logs.

    ``since_ts_unix_s`` is the anchor timestamp of the previous gate-eval (or
    the candidate deploy time on the first eval): compute_delta restricts
    its window to PLAYER_SCORES emits at or after this timestamp, so the
    KPIs cover exactly the matches counted by the match-aligned trigger.

    Returns ``(combat, aim_rate, movement)`` or ``(None, None, None)`` if any compute_delta
    fails (e.g. no logs yet, no UT99 servers reachable). Caller treats None as
    "skip this eval cycle".
    """
    try:
        combat = compute_delta(kpi=KPI_COMBAT_SCORE,
                                since_ts_unix_s=since_ts_unix_s).rl_avg_gain
        aim = compute_delta(kpi=KPI_SHOTS_ON_TARGET_RATE,
                             since_ts_unix_s=since_ts_unix_s).rl_avg_gain
        movement = compute_delta(kpi=KPI_FLAG_SCORE,
                                  since_ts_unix_s=since_ts_unix_s).rl_avg_gain
        return combat, aim, movement
    except Exception as e:
        log_print(logger, f"DUAL_KPI_FETCH_FAILED: {type(e).__name__}: {e}")
        return None, None, None


# Minimum sample-count per weapon voor de gate-eval. Wapens onder deze drempel
# worden door _read_current_kpis_per_weapon weggefilterd zodat low-sample
# fluctuaties de AND-promotion niet blokkeren. Conservatief gekozen op 5 —
# DeltaResult.is_significant() default is 20 maar dat is voor whole-match
# eval; per-weapon binnen één 10-min window is bouncier.
_PER_WEAPON_MIN_OBS = 5


def _read_current_kpis_per_weapon(
    logger,
    since_ts_unix_s: float,
) -> Optional[Dict[str, Dict[str, float]]]:
    """Per-weapon variant: returnt {weapon: {combat_score, aim_rate, movement_score}}.

    Vraagt compute_delta_per_weapon op voor elke KPI, beperkt tot
    PLAYER_SCORES emits sinds ``since_ts_unix_s`` (de anker-timestamp van de
    vorige gate-eval). Voor elk wapen waar ALLE drie de KPIs voldoen aan
    _PER_WEAPON_MIN_OBS RL én UT99 obs, neemt de helper de rl_avg_gain als
    current value. Wapens met onvoldoende data in één van de KPIs worden
    volledig overgeslagen (eventueel volgende cyclus weer mogelijk).

    Returnt None bij fetch-failure (caller behandelt als 'skip cycle'); leeg
    dict wanneer geen enkel wapen voldoet (caller logt insufficient).
    """
    try:
        per_combat = compute_delta_per_weapon(kpi=KPI_COMBAT_SCORE,
                                               since_ts_unix_s=since_ts_unix_s)
        per_aim = compute_delta_per_weapon(kpi=KPI_SHOTS_ON_TARGET_RATE,
                                            since_ts_unix_s=since_ts_unix_s)
        per_movement = compute_delta_per_weapon(kpi=KPI_FLAG_SCORE,
                                                 since_ts_unix_s=since_ts_unix_s)
    except Exception as e:
        log_print(logger, f"DUAL_KPI_PER_WEAPON_FETCH_FAILED: {type(e).__name__}: {e}")
        return None

    result: Dict[str, Dict[str, float]] = {}
    common_weapons = set(per_combat) & set(per_aim) & set(per_movement)
    for weapon in sorted(common_weapons):
        if not (per_combat[weapon].is_significant(_PER_WEAPON_MIN_OBS)
                and per_aim[weapon].is_significant(_PER_WEAPON_MIN_OBS)
                and per_movement[weapon].is_significant(_PER_WEAPON_MIN_OBS)):
            continue
        result[weapon] = {
            "combat_score": per_combat[weapon].rl_avg_gain,
            "aim_rate": per_aim[weapon].rl_avg_gain,
            "movement_score": per_movement[weapon].rl_avg_gain,
        }
    return result


def _per_dim_smoothness(
    action_t: torch.Tensor, action_t1: torch.Tensor, dims: tuple[int, ...],
) -> torch.Tensor:
    """CAPS over a subset of dims. Section 4.4: ``action_smoothness_dims=[0,1]``
    means only yaw/pitch get the smoothness penalty; fire/altFire dims must
    NOT be penalised or the policy collapses fire-rate. Empty dim tuple
    returns zero (smoothness disabled entirely)."""
    if not dims:
        return action_t.new_zeros(())
    diff = action_t[:, list(dims)] - action_t1[:, list(dims)]
    return (diff ** 2).mean()


def _per_dim_action_bias_reg(
    actor_mean: torch.Tensor,
    bc_mean: Optional[torch.Tensor],
    dims: tuple[int, ...],
) -> torch.Tensor:
    """Per-dim anti-bias regulariser. Drift away from BC batch-mean is gated
    on the dims listed in ``action_bias_dims``. Mirrors the VR pattern but
    restricted to a dim-slice; ``dims = (0, 1)`` for the joint config because
    binary fire/altFire have natural batch-mean shifts (engagement-density-
    driven) that aren't policy drift."""
    if not dims:
        return actor_mean.new_zeros(())
    dim_list = list(dims)
    actor_batch_mean = torch.tanh(actor_mean)[:, dim_list].mean(dim=0)
    if bc_mean is not None:
        bc_batch_mean = torch.tanh(bc_mean)[:, dim_list].mean(dim=0)
        return ((actor_batch_mean - bc_batch_mean) ** 2).sum()
    return (actor_batch_mean ** 2).sum()


_AUX_TARGET_LABEL_CONFIDENCE_FLOOR = 0.3

# Poll-cadens voor de SSH-based match-end counter. Match-ends gebeuren elke
# ~11 min (10 min match + ~90s ServerTravel) dus 60s drift is verwaarloosbaar
# vergeleken met de match-cycle. Lager waarde → snellere gate-respons maar
# meer SSH-load (5 hosts × ~2s = ~10s per poll).
_MATCH_COUNT_POLL_INTERVAL_S = 60.0


def _compute_aux_actor_loss(
    actor,
    bc_actor,
    states: torch.Tensor,
    target_labels: Optional[torch.Tensor],
    target_confidences: Optional[torch.Tensor],
    aux_target_alpha: float,
) -> tuple[torch.Tensor, dict]:
    """Aux TargetHead loss + diagnostic component breakdown.

    Behaviour driven by which signals are available:

    1. If high-confidence ``target_labels`` are supplied: use cross-entropy
       against real labels, confidence-weighted via ``compute_aux_target_loss``.
       Low-confidence non-fire fallback labels (0.1) are intentionally ignored
       during SAC because on-policy replay is slot-order biased and can collapse
       the target head to enemy0.
    2. If ``bc_actor`` is present: BC-anchor mode — KL between actor's target
       distribution and BC's target distribution. This runs in addition to CE
       so the target head stays close to the BC prior while the shared backbone
       is fine-tuned by SAC.
    3. Else: aux loss is zero. Logged once at startup.

    Returns ``(loss_term, components)`` where components is a dict suitable for
    log_kv. The ``loss_term`` is already multiplied by ``aux_target_alpha``;
    callers add it to ``actor_loss`` directly.
    """
    components: dict = {}
    if aux_target_alpha <= 0:
        return states.new_zeros(()), components

    _, target_logits = actor.forward_with_target(states)
    total = states.new_zeros(())

    if target_labels is not None and target_confidences is not None:
        valid_mask = (
            (target_labels >= 0)
            & (target_confidences >= _AUX_TARGET_LABEL_CONFIDENCE_FLOOR)
        )
        if int(valid_mask.sum().item()) == 0:
            components["aux_target_ce_skipped_low_conf"] = 1.0
        else:
            # Defensive: if a label points to a slot that the TargetHead masked
            # (absent enemy → ``finfo.min/2`` sentinel logit), F.cross_entropy
            # against that label returns ~1e38 in fp32 and pollutes the actor
            # loss with effective inf. High-confidence labels should be alive,
            # but filter at consumption-time so one bad transition cannot poison
            # the gradient.
            sentinel_threshold = torch.finfo(target_logits.dtype).min / 4.0
            chosen_logit = target_logits.gather(
                1, target_labels.clamp(min=0).unsqueeze(1),
            ).squeeze(1)
            alive_mask = chosen_logit > sentinel_threshold
            valid_mask = valid_mask & alive_mask
            if int(valid_mask.sum().item()) == 0:
                components["aux_target_ce_skipped_masked"] = 1.0
            else:
                loss_ce = compute_aux_target_loss(
                    target_logits[valid_mask],
                    target_labels[valid_mask],
                    target_confidences[valid_mask],
                )
                components["aux_target_ce"] = float(loss_ce.item())
                total = total + loss_ce

    if bc_actor is not None:
        with torch.no_grad():
            _, bc_target_logits = bc_actor.forward_with_target(states)
            bc_log_probs = F.log_softmax(bc_target_logits, dim=-1)
            bc_probs = bc_log_probs.exp()
        actor_log_probs = F.log_softmax(target_logits, dim=-1)
        # KL(BC || actor): keeps actor close to BC distribution.
        kl = (bc_probs * (bc_log_probs - actor_log_probs)).sum(dim=-1).mean()
        components["aux_target_kl_to_bc"] = float(kl.item())
        total = total + kl

    return aux_target_alpha * total, components


def _critic_step(
    *, actor, q1, q2, target_q1, target_q2,
    log_std_param, temperature,
    states, actions, rewards, next_states, dones,
    rewards_decomp, cfg,
    teammate_states=None, next_teammate_states=None,
) -> torch.Tensor:
    """Single critic forward+backward. Dispatches on cfg.critic_mode through
    the shared ``compute_critic_loss``; the dispatch lives in the kernel so
    this trainer only forwards the right rewards container.

    Fase 2.5 CTDE: ``teammate_states`` / ``next_teammate_states`` are forwarded
    when ``cfg.ctde_mode != "off"``; otherwise must be ``None``."""
    decomp_kwarg = rewards_decomp if cfg.critic_mode == "multi_head" else None
    return compute_critic_loss(
        actor, q1, q2, target_q1, target_q2,
        log_std_param, temperature,
        states, actions, rewards, next_states, dones, cfg,
        rewards_decomp=decomp_kwarg,
        teammate_states=teammate_states,
        next_teammate_states=next_teammate_states,
    )


def _maybe_rollback_to_champion(
    *, actor, actor_optimizer, log_std_param,
    champion_pt_path: Path, device, logger, reason: str,
) -> bool:
    """Restore actor weights from the live PROMOTED champion snapshot.

    Returns True on success, False when no champion exists yet (pre-first-
    PROMOTE) — caller should then skip export rather than revert to BC.
    Matches the current joint deployment pattern:
    no champion = no revert = keep training, only export-gate stays closed.

    Mirrors what DeltaGate.restore_baseline does, but without the rollback
    streak / Adam-wipe escalation — those are tied to gate state we manage
    separately for the joint trainer.
    """
    if not champion_pt_path.exists():
        log_print(logger, f"REVERT_NO_CHAMPION: {reason}; {champion_pt_path.name} missing — keep training, skip export")
        return False
    from train.model.bc_sequence_network import load_compatible_state_dict
    ckpt = torch.load(champion_pt_path, map_location=device, weights_only=False)
    load_compatible_state_dict(actor, ckpt["model_state_dict"])
    if "log_std" in ckpt.get("model_state_dict", {}):
        ls = ckpt["model_state_dict"]["log_std"]
        if ls.shape == log_std_param.shape:
            log_std_param.data.copy_(ls.to(device))
    log_print(logger, f"REVERT_TO_CHAMPION: {reason}")
    return True


def _ensure_joint_champion_bundle(
    *, model_key: str, promoted_best_onnx: Path, promoted_best_pt: Path,
    bc_baseline_onnx: Path, bc_baseline_pt: Path, logger,
) -> None:
    """Seed a champion_store snapshot + pool counter for the joint model.

    gameplay.json's Red team resolves ``rl_pawn/newest`` via
    SnapshotResolver, which now only follows promoted snapshots. If the
    durable ``*_sac_best.onnx`` exists, seed from that because it is updated
    only on PROMOTE. BC bootstrap remains a non-promoted fallback for explicit
    pinned counters, not for dynamic ``newest``.

    Idempotent: returns early when the pool already has a promoted counter for
    the model_key.
    """
    existing_bundle = champion_pool.newest_promoted_bundle(model_key)
    if existing_bundle is not None:
        counter = int(existing_bundle.counters[model_key])
        snapshot_dir = champion_store.find_snapshot_dir(model_key, counter)
        if snapshot_dir is not None:
            meta = champion_store.read_meta(snapshot_dir)
            compat = champion_store.is_compatible(meta, model_key)
            if compat["feature"] and compat["arch"]:
                return
            log_print(logger,
                f"CHAMPION_FINGERPRINT_STALE: promoted {model_key}/{counter:04d} "
                f"incompatible (feature={compat['feature']}, arch={compat['arch']}). "
                f"Re-bootstrapping.")

    has_compatible = False
    existing = champion_store.list_snapshots(model_key)
    for snap in existing:
        meta = champion_store.read_meta(snap)
        compat = champion_store.is_compatible(meta, model_key)
        if compat["feature"] and compat["arch"]:
            has_compatible = True
            break

    seeded_promoted = False
    if promoted_best_onnx.exists():
        snapshot_dir = champion_store.create_snapshot(
            model_key, promoted_best_onnx,
            src_pt=promoted_best_pt if promoted_best_pt.exists() else None,
            tag="promoted-bootstrap",
            kpi_at_snapshot={
                "decision": "PROMOTE",
                "source": "bootstrap-from-sac-best",
                "reason": "joint bootstrap from last promoted SAC best ONNX",
            },
        )
        seeded_promoted = True
        counter = champion_store.read_meta(snapshot_dir).counter
        log_print(logger,
            f"JOINT_CHAMPION_BOOTSTRAP: snapshot {model_key}/{counter:04d} "
            f"created from last promoted SAC best")
    elif not has_compatible:
        if not bc_baseline_onnx.exists():
            log_print(logger,
                f"JOINT_CHAMPION_BOOTSTRAP_DEFERRED: {bc_baseline_onnx.name} "
                f"missing — bootstrap deferred to first PROMOTE.")
            return
        else:
            snapshot_dir = champion_store.create_snapshot(
                model_key, bc_baseline_onnx,
                src_pt=bc_baseline_pt if bc_baseline_pt.exists() else None,
                tag="bc-bootstrap",
                kpi_at_snapshot={
                    "decision": "PROMOTE",
                    "source": "bootstrap-from-bc-baseline",
                    "reason": "BC baseline as initial champion",
                },
            )
            seeded_promoted = True
            counter = champion_store.read_meta(snapshot_dir).counter
            log_print(logger,
                f"JOINT_CHAMPION_BOOTSTRAP: snapshot {model_key}/{counter:04d} "
                f"created from BC baseline (promoted)")
    else:
        snaps = champion_store.list_snapshots(model_key)
        counter = champion_store.read_meta(snaps[0]).counter
        snapshot_dir = champion_store.find_snapshot_dir(model_key, counter)
        seeded_promoted = champion_pool.is_promoted_snapshot(model_key, counter)

    if champion_pool.newest_bundle() is None:
        champion_pool.bootstrap()
        log_print(logger, "CHAMPION_POOL_SEEDED: pool was empty, ran bootstrap()")
    if seeded_promoted:
        bundle = champion_pool.add_promoted_bundle(model_key, counter)
        bundle_kind = "promoted"
    else:
        bundle = champion_pool.add_bootstrap_bundle(model_key, counter)
        bundle_kind = "bootstrap"
    ChampionSync.sync_snapshot(model_key, snapshot_dir, wait=True)
    ChampionSync.sync_bundles_only(wait=True)
    log_print(logger,
        f"JOINT_CHAMPION_BOOTSTRAP: pool[0] now contains "
        f"{model_key}/{counter:04d} ({bundle_kind}, bundle={bundle.id}).")


def _publish_promoted_champion(
    *, model_key: str, onnx_path: Path, src_pt_path: Path,
    eval_result,  # DualKPIEvalResult | DualKPIPerWeaponEvalResult
    current_combat: float, current_aim: float, current_movement: float, logger,
) -> None:
    """Snapshot the current model into champion_store and append it to
    champion_pool head, then sync to all servers. Red-team bots resolving
    ``<mk>/newest`` get the new champion on the next bot restart / deploy cycle.
    """
    if not onnx_path.exists():
        log_print(logger,
            f"JOINT_CHAMPION_PUBLISH_FAILED: {onnx_path} missing — "
            f"local best_pt updated but self-play pool not refreshed")
        return
    src_pt = src_pt_path if src_pt_path.exists() else None
    kpi_at_snapshot = {
        "kpi_primary": KPI_COMBAT_SCORE,
        "kpi_secondary": KPI_SHOTS_ON_TARGET_RATE,
        "kpi_movement": KPI_FLAG_SCORE,
        "current_combat_score": current_combat,
        "current_aim_rate": current_aim,
        "current_movement_flag_score": current_movement,
        "promote_streak": eval_result.promote_streak,
        "decision": eval_result.decision,
        "reason": eval_result.reason,
        "source": "joint-dual-kpi-delta-gate",
    }
    snapshot_dir = champion_store.create_snapshot(
        model_key, onnx_path,
        src_pt=src_pt, kpi_at_snapshot=kpi_at_snapshot,
    )
    counter = champion_store.read_meta(snapshot_dir).counter
    bundle = champion_pool.add_promoted_bundle(model_key, counter)
    ChampionSync.sync_snapshot(model_key, snapshot_dir, wait=True)
    ChampionSync.sync_bundles_only(wait=True)
    log_print(logger,
        f"JOINT_CHAMPION_PUBLISHED: {model_key}/{counter:04d} "
        f"bundle={bundle.id} combat={current_combat:.3f} "
        f"aim={current_aim:.3f} movement={current_movement:.3f} "
        f"(Red-team will pick this up after bot restart/deploy)")


def _rollback_joint_to_champion(
    *, actor, actor_optimizer, log_std_param,
    local_best_pt_path: Path,
    model_key: str, device, logger, reason: str,
) -> None:
    """Rollback: restore trainer actor weights in-memory from champion .pt.

    The next regular export tick (~1 export_interval_steps later) will
    produce a fresh ONNX from the restored actor and sync it to all servers
    via the normal DEPLOYED path. We deliberately do NOT overwrite
    trainingmodel/{mk}.onnx with the champion ONNX here: doing so would
    make current-team bots byte-identical to champion-team bots, killing
    self-play diversity and eliminating learning signal until the model
    diverges again. The brief window (one export interval, ~3-4 min) where
    bots still run the pre-rollback ONNX produces negligible misaligned
    experience relative to the replay buffer size.

    Prefers the champion_store .pt for the in-memory revert because it's
    version-consistent with the synced ONNX. Falls back to the local trainer
    snapshot (best_pt_path) when the pool entry is missing — same result for
    the most-recent champion since PROMOTE writes both.
    """
    bundle = champion_pool.newest_promoted_bundle(model_key)
    pool_pt_path: Optional[Path] = None
    if bundle is not None and model_key in bundle.counters:
        counter = bundle.counters[model_key]
        snapshot_dir = champion_store.find_snapshot_dir(model_key, counter)
        if snapshot_dir is not None:
            cand_pt = snapshot_dir / f"{model_key}.pt"
            if cand_pt.exists():
                pool_pt_path = cand_pt

    pt_source = pool_pt_path if pool_pt_path is not None else local_best_pt_path
    reverted = _maybe_rollback_to_champion(
        actor=actor, actor_optimizer=actor_optimizer,
        log_std_param=log_std_param,
        champion_pt_path=pt_source, device=device, logger=logger,
        reason=reason,
    )
    if not reverted:
        return

    log_print(logger,
        f"JOINT_ROLLBACK_ACTOR_ONLY: trainer weights restored to champion "
        f"(model_key={model_key}). Bots keep current ONNX until next "
        f"export tick deploys the restored actor.")


def run_sac(
    bundle: JointSACBundle,
    buffer_dir: Path,
    model_output_dir: Path,
    logger,
) -> None:
    """Joint SAC training entry — analogous to VR's ``run_sac``."""
    signal.signal(signal.SIGINT, _handle_signal)
    signal.signal(signal.SIGTERM, _handle_signal)

    cfg = bundle.sac_cfg
    probe_cfg = bundle.probe_cfg
    dual_kpi_cfg = bundle.dual_kpi_cfg

    device = cfg.device
    mk = cfg.model_key
    assert mk == JOINT_MODEL_KEY, f"Expected model_key={JOINT_MODEL_KEY}, got {mk!r}"

    log_print(logger, f"Joint SAC trainer starting: model={mk} device={device} "
                      f"action_dim={cfg.action_dim} critic_mode={cfg.critic_mode}")
    log_print(logger, f"  buffer_dir={buffer_dir}")
    log_print(logger, f"  replay_capacity={cfg.replay_buffer_capacity} "
                      f"min_buffer={cfg.min_buffer_size}")
    log_print(logger, f"  lr_actor={cfg.lr_actor} lr_critic={cfg.lr_critic} tau={cfg.tau}")
    log_print(logger, f"  target_entropy={cfg.target_entropy} "
                      f"per_dim={cfg.target_entropy_per_dim} "
                      f"temperature_init={cfg.temperature_init}")
    log_print(logger, f"  CAPS dims={cfg.action_smoothness_dims} α={cfg.action_smoothness_alpha}")
    log_print(logger, f"  action_bias dims={cfg.action_bias_dims} α={cfg.action_bias_alpha}")
    log_print(logger, f"  bc_anchor dims={cfg.bc_anchor_dims} α={cfg.bc_alpha}->{cfg.bc_alpha_min}")
    log_print(logger, f"  aux_target_alpha={cfg.aux_target_alpha}")
    log_print(logger, f"  reward_decomp_keys={cfg.reward_decomp_keys} "
                      f"decomp_norm={cfg.reward_decomp_normalization} "
                      f"head_weights={cfg.reward_head_weights}")
    log_print(logger, f"  event_priority fraction={cfg.event_priority_fraction} "
                      f"positive_fraction={cfg.event_priority_positive_fraction} "
                      f"reward_keys={cfg.event_priority_reward_keys} "
                      f"action_indices={cfg.event_priority_action_indices}")
    log_print(logger, f"  ctde_mode={cfg.ctde_mode} "
                      f"teammate_state_dim={cfg.teammate_state_dim} "
                      f"(Fase 2.5 CTDE — critic-side joint state)")

    # Paths — identical to VR pattern; mk is now "rl_pawn".
    pt_path = model_output_dir / f"{mk}_sac.pt"
    best_pt_path = model_output_dir / f"{mk}_sac_best.pt"
    sac_ckpt_path = model_output_dir / f"{mk}_sac_checkpoint.pt"
    onnx_path = str(model_output_dir / f"{mk}.onnx")
    best_onnx_path = str(model_output_dir / f"{mk}_sac_best.onnx")
    bc_baseline_onnx = str(model_output_dir / f"{mk}_bc_baseline.onnx")
    bc_baseline_pt = model_output_dir / f"{mk}_bc_baseline.pt"

    boot = bootstrap(
        cfg, device, mk,
        model_output_dir, sac_ckpt_path, bc_baseline_pt, bc_baseline_onnx, logger,
    )
    _ensure_joint_champion_bundle(
        model_key=mk,
        promoted_best_onnx=Path(best_onnx_path),
        promoted_best_pt=best_pt_path,
        bc_baseline_onnx=Path(bc_baseline_onnx),
        bc_baseline_pt=bc_baseline_pt,
        logger=logger,
    )
    actor = boot.actor
    log_std_param = boot.log_std_param
    q1, q2, target_q1, target_q2 = boot.q1, boot.q2, boot.target_q1, boot.target_q2
    temperature = boot.temperature
    actor_optimizer = boot.actor_optimizer
    critic_optimizer = boot.critic_optimizer
    temp_optimizer = boot.temp_optimizer
    bc_actor = boot.bc_actor
    bc_log_std_anchor = boot.bc_log_std_anchor

    # ------------------------------------------------------------------
    # GPU-throughput optimizations: torch.compile + AMP autocast.
    # Optimizers above are bound to the *original* parameters; torch.compile
    # wraps the modules but proxies parameters() / .data through to the same
    # tensors, so optimizer steps and Polyak soft_update keep working.
    # AMP uses bf16 on Ada Lovelace (4090): fp32-exponent range = no
    # GradScaler needed; the target_q clamp(-50,50) is far below the bf16
    # overflow threshold. fp16 path would require multi-optimizer scaler
    # coordination (single scaler.update() per iteration across critic +
    # actor + temp steps) — not implemented; raise to surface the gap.
    # ------------------------------------------------------------------
    if cfg.compile_models:
        actor = torch.compile(actor, mode="default", dynamic=False)
        q1 = torch.compile(q1, mode="default", dynamic=False)
        q2 = torch.compile(q2, mode="default", dynamic=False)
        target_q1 = torch.compile(target_q1, mode="default", dynamic=False)
        target_q2 = torch.compile(target_q2, mode="default", dynamic=False)
        if bc_actor is not None:
            bc_actor = torch.compile(bc_actor, mode="default", dynamic=False)
        log_print(logger,
            "torch.compile enabled (mode=default, dynamic=False) on actor + "
            f"q1 + q2 + target_q1 + target_q2{' + bc_actor' if bc_actor is not None else ''}")
    else:
        log_print(logger, "torch.compile DISABLED (sac.json compile_models=false)")

    if cfg.use_amp:
        if cfg.amp_dtype == "float16":
            raise NotImplementedError(
                "amp_dtype='float16' requires a shared GradScaler across the critic/"
                "actor/temp optimizer steps with a single scaler.update() per iteration. "
                "Not yet wired in this trainer. Use amp_dtype='bfloat16' (4090 has full "
                "bf16 support; bf16's fp32-exponent range removes the need for a scaler)."
            )
        amp_torch_dtype = cfg.amp_torch_dtype
        # TF32 op de overgebleven fp32-paden (soft_update, niet-autocast tensors,
        # initialisatie). Ada Lovelace TensorCores doen fp32 matmuls in TF32-format
        # (10-bit mantissa, fp32-exponent) wanneer dit op "high" staat. ~5-10%
        # extra throughput naast bf16 AMP. Process-global maar OK: elke trainer
        # is een eigen process.
        torch.set_float32_matmul_precision("high")
        log_print(logger,
            f"AMP enabled: dtype={cfg.amp_dtype} (no scaler — bf16 grads in fp32 range); "
            f"TF32 matmul precision=high (fp32 ops via TensorCores)")
    else:
        amp_torch_dtype = None
        log_print(logger, "AMP DISABLED (sac.json use_amp=false; FP32 training path)")

    def _autocast_ctx():
        if amp_torch_dtype is None:
            return contextlib.nullcontext()
        return torch.amp.autocast("cuda", dtype=amp_torch_dtype)

    def _backward_clip_step(loss, optimizer, params_to_clip):
        """Run backward + (optional) clip + optimizer.step under the active
        precision regime. params_to_clip=None ⇒ no grad-norm clipping (used by
        the temperature optimizer; matches pre-AMP behaviour)."""
        loss.backward()
        if params_to_clip is not None and cfg.max_grad_norm > 0:
            torch.nn.utils.clip_grad_norm_(params_to_clip, cfg.max_grad_norm)
        optimizer.step()

    global_step = boot.global_step
    best_exported_return = boot.best_exported_return
    best_observed_return = boot.best_observed_return
    baseline_return = boot.baseline_return
    last_export_step = boot.last_export_step

    # Dual-KPI DeltaGate + baselines.
    baselines = _load_baselines(logger)
    log_print(logger,
        f"Dual-KPI baselines from {baselines.source}: combat={baselines.combat_score:.3f} "
        f"aim={baselines.shots_on_target_rate:.3f} "
        f"movement={baselines.movement_flag_score:.3f}")
    delta_gate = DualKPIDeltaGate(
        cfg=dual_kpi_cfg,
        baseline_combat_score=baselines.combat_score,
        baseline_aim_rate=baselines.shots_on_target_rate,
        baseline_movement_score=baselines.movement_flag_score,
        baseline_per_weapon=baselines.per_weapon,
        logger=logger,
    )
    gate_mode = "per-weapon AND" if delta_gate.has_per_weapon_baselines() else "aggregate"
    log_print(logger,
        f"DualKPIDeltaGate enabled for {mk} (mode={gate_mode}): "
        f"eval every {dual_kpi_cfg.matches_per_eval_cycle} completed matches, "
        f"promote (AND, {dual_kpi_cfg.promote_window_cycles} cycles): "
        f"combat≥{dual_kpi_cfg.promote_combat_score_min_ratio:.2f}× "
        f"aim≥{dual_kpi_cfg.promote_aim_min_ratio:.2f}× "
        f"movement≥{dual_kpi_cfg.promote_movement_min_ratio:.2f}×, "
        f"rollback (OR, {dual_kpi_cfg.rollback_window_cycles} cycles): "
        f"combat≤{dual_kpi_cfg.rollback_combat_score_max_ratio:.2f}× "
        f"aim≤{dual_kpi_cfg.rollback_aim_max_ratio:.2f}× "
        f"movement≤{dual_kpi_cfg.rollback_movement_max_ratio:.2f}×, "
        f"min_steps_before_eval={dual_kpi_cfg.min_steps_before_eval}")

    # Probe trackers + feature indices.
    feature_idx = JointFeatureIndices.from_input_features(cfg.input_features)
    strata_idx = StratificationFeatureIndices.from_input_features(cfg.input_features)
    if not feature_idx.has_bearing():
        log_print(logger,
            "PROBE_FEATURES_MISSING: enemy_X_relSin/relCos not in input_features — "
            "CC1 target-yaw consistency probe will SKIP (logged, not failed). "
            "Add them to features.json before relying on this probe.")
    if not feature_idx.has_aim_alignment():
        log_print(logger,
            "PROBE_FEATURES_MISSING: enemy_X_aimAlignmentDot_norm not in "
            "input_features — CC2 fire→aim probe will SKIP.")
    pt_tracker = ProbeViolationTracker(
        consecutive_violations_for_rollback=probe_cfg.consecutive_violations_for_rollback,
        collapse_immediate=probe_cfg.collapse_immediate,
    )
    onnx_tracker = ProbeViolationTracker(
        consecutive_violations_for_rollback=probe_cfg.consecutive_violations_for_rollback,
        collapse_immediate=probe_cfg.collapse_immediate,
    )

    log_print(logger,
        f"Probes: mode={probe_cfg.mode} samples/probe={probe_cfg.samples_per_probe} "
        f"consecutive={probe_cfg.consecutive_violations_for_rollback}× "
        f"collapse_immediate={probe_cfg.collapse_immediate}")

    # Replay buffer with aux + (optional) reward-decomp tracking.
    state_dim = cfg.seq_len * cfg.input_size
    decomp_keys = list(cfg.reward_decomp_keys) if cfg.reward_decomp_keys is not None else None
    replay = ReplayBuffer(
        cfg.replay_buffer_capacity,
        state_dim,
        cfg.action_dim,
        target_features=cfg.target_features,
        target_feature_types=cfg.target_feature_types,
        aux_target_enabled=True,
        reward_decomp_keys=decomp_keys,
        event_priority_reward_keys=cfg.event_priority_reward_keys,
        event_priority_action_indices=cfg.event_priority_action_indices,
        event_priority_min_abs_reward=cfg.event_priority_min_abs_reward,
        teammate_state_dim=(cfg.teammate_state_dim if cfg.ctde_mode != "off" else 0),
    )

    batch_provider = JointAsyncBatchProvider(
        replay=replay,
        buffer_dir=str(buffer_dir),
        batch_size=cfg.batch_size,
        seq_len=cfg.seq_len,
        input_size=cfg.input_size,
        device=device,
        max_file_age_seconds=cfg.max_file_age_seconds,
        reward_normalization=cfg.reward_normalization,
        reward_decomp_normalization=cfg.reward_decomp_normalization,
        event_priority_fraction=cfg.event_priority_fraction,
        event_priority_positive_fraction=cfg.event_priority_positive_fraction,
        prefetch_depth=cfg.prefetch_depth,
        ingest_interval=cfg.ingest_interval,
        max_files_per_ingest=cfg.max_files_per_ingest,
        champion_experience_enabled=cfg.champion_experience_enabled,
        logger=logger,
    )
    batch_provider.start()
    log_print(logger,
        f"Async batch provider started (prefetch_depth={cfg.prefetch_depth},"
        f" reward_norm={cfg.reward_normalization},"
        f" decomp_reward_norm={cfg.reward_decomp_normalization},"
        f" event_priority={cfg.event_priority_fraction:.2f}/"
        f"{cfg.event_priority_positive_fraction:.2f})")

    total_critic_loss = 0.0
    total_actor_loss_total = 0.0
    total_actor_sac_loss = 0.0
    total_actor_caps_loss = 0.0
    total_actor_bias_loss = 0.0
    total_actor_bc_loss = 0.0
    total_actor_aux_loss = 0.0
    total_temp_loss = 0.0
    updates_since_log = 0
    actor_updates_since_log = 0
    last_checkpoint_step = global_step
    reward_window: list[float] = []
    warmup_done = global_step >= cfg.critic_warmup_steps
    last_ingest_total = 0
    # Gate eval window start. time.time() zodat de eerste eval na 5 fresh
    # matches vuurt — geen stale pre-restart data.
    last_dual_kpi_eval_ts = time.time()
    last_match_count_check_ts = 0.0
    cached_matches_done = 0
    consecutive_kpi_fetch_fail = 0
    dual_kpi_cycle = 0

    if warmup_done:
        log_print(logger, f"Warmup already completed (step={global_step} >= {cfg.critic_warmup_steps})")
    else:
        log_print(logger,
            f"Starting critic-only warmup ({cfg.critic_warmup_steps - global_step} steps remaining)")

    log_print(logger, "Entering main joint SAC loop. Waiting for experience...")

    while not _shutdown_requested:
        current_total = batch_provider.total_ingested
        if current_total > last_ingest_total:
            added = current_total - last_ingest_total
            log_print(logger, f"Ingested {added} transitions (buffer size: {batch_provider.buffer_size})")
            last_ingest_total = current_total

        if not batch_provider.has_data:
            time.sleep(2)
            continue

        if baseline_return is None and len(reward_window) == 0:
            s, a, r, ns, d = replay.sample(min(cfg.batch_size, replay.size))
            baseline_return = float(np.mean(r))
            best_exported_return = max(best_exported_return, baseline_return)
            best_observed_return = max(best_observed_return, baseline_return)
            log_print(logger, f"Measured BC baseline return: {baseline_return:.4f}")

        (states, actions, raw_rewards, rewards,
         next_states, dones, extras) = batch_provider.next_batch()

        reward_window.extend(raw_rewards.tolist())
        if len(reward_window) > cfg.recent_reward_window:
            reward_window = reward_window[-cfg.recent_reward_window:]
        mean_ret = np.mean(reward_window) if reward_window else 0.0
        best_observed_return = max(best_observed_return, mean_ret)

        rewards_decomp = extras.get("rewards_decomp")
        target_labels = extras.get("target_labels")
        target_confidences = extras.get("target_confidences")
        teammate_states = extras.get("teammate_states")
        next_teammate_states = extras.get("next_teammate_states")

        # === Critic update ===
        with _autocast_ctx():
            critic_loss = _critic_step(
                actor=actor, q1=q1, q2=q2, target_q1=target_q1, target_q2=target_q2,
                log_std_param=log_std_param, temperature=temperature,
                states=states, actions=actions, rewards=rewards,
                next_states=next_states, dones=dones,
                rewards_decomp=rewards_decomp, cfg=cfg,
                teammate_states=teammate_states,
                next_teammate_states=next_teammate_states,
            )
        critic_optimizer.zero_grad()
        _backward_clip_step(
            critic_loss, critic_optimizer,
            list(q1.parameters()) + list(q2.parameters()),
        )
        total_critic_loss += critic_loss.item()

        # === Actor update (skipped during warmup) ===
        if warmup_done and global_step % cfg.actor_update_period == 0:
            with _autocast_ctx():
                actor_loss, _ = compute_sac_actor_loss(
                    actor, q1, q2, log_std_param, temperature, states, cfg,
                    teammate_states=teammate_states,
                )
                total_actor_sac_loss += float(actor_loss.item())

                # BC MSE anchor. For joint VR+shooting this is intentionally
                # configurable per dim: yaw/pitch need BC stability, while the
                # binary fire heads need room to escape an under-firing BC prior.
                if bc_actor is not None and cfg.bc_alpha > 0:
                    if cfg.bc_alpha_anneal_steps > 0:
                        t = min(1.0, global_step / cfg.bc_alpha_anneal_steps)
                        bc_alpha_current = cfg.bc_alpha + t * (cfg.bc_alpha_min - cfg.bc_alpha)
                    else:
                        bc_alpha_current = cfg.bc_alpha
                    if bc_alpha_current > 0:
                        with torch.no_grad():
                            bc_mean = bc_actor(states)
                        actor_mean = actor(states)
                        if cfg.bc_anchor_dims:
                            dims = list(cfg.bc_anchor_dims)
                            bc_reg = F.mse_loss(actor_mean[:, dims], bc_mean[:, dims])
                        else:
                            bc_reg = F.mse_loss(actor_mean, bc_mean)
                        actor_loss = actor_loss + bc_alpha_current * bc_reg
                        total_actor_bc_loss += float(bc_reg.item())

                # log_std anchor against BC's learned breadth.
                if cfg.bc_log_std_anchor_alpha > 0 and bc_log_std_anchor is not None:
                    log_std_reg = F.mse_loss(
                        log_std_param.clamp(cfg.log_std_min, cfg.log_std_max), bc_log_std_anchor,
                    )
                    actor_loss = actor_loss + cfg.bc_log_std_anchor_alpha * log_std_reg

                # Per-head CAPS smoothness (yaw/pitch only — §4.4).
                if cfg.action_smoothness_alpha > 0 and cfg.action_smoothness_dims:
                    action_t = torch.tanh(actor(states))
                    action_t1 = torch.tanh(actor(next_states))
                    smoothness_loss = _per_dim_smoothness(
                        action_t, action_t1, cfg.action_smoothness_dims,
                    )
                    actor_loss = actor_loss + cfg.action_smoothness_alpha * smoothness_loss
                    total_actor_caps_loss += float(smoothness_loss.item())

                # Per-head action_bias regulariser (yaw/pitch only — §4.4).
                if cfg.action_bias_alpha > 0 and cfg.action_bias_dims:
                    actor_mean = actor(states)
                    if bc_actor is not None:
                        with torch.no_grad():
                            bc_mean = bc_actor(states)
                    else:
                        bc_mean = None
                    bias_reg = _per_dim_action_bias_reg(
                        actor_mean, bc_mean, cfg.action_bias_dims,
                    )
                    actor_loss = actor_loss + cfg.action_bias_alpha * bias_reg
                    total_actor_bias_loss += float(bias_reg.item())

                # Aux target loss — TargetHead anchored to BC labels (replay aux
                # arrays) or BC distribution (fallback KL).
                aux_loss_term, _aux_components = _compute_aux_actor_loss(
                    actor, bc_actor, states,
                    target_labels=target_labels,
                    target_confidences=target_confidences,
                    aux_target_alpha=cfg.aux_target_alpha,
                )
                if cfg.aux_target_alpha > 0:
                    actor_loss = actor_loss + aux_loss_term
                    # The component already includes α; record un-scaled value for log.
                    if cfg.aux_target_alpha != 0:
                        total_actor_aux_loss += float(aux_loss_term.item()) / cfg.aux_target_alpha

            actor_optimizer.zero_grad()
            _backward_clip_step(
                actor_loss, actor_optimizer,
                list(actor.parameters()) + [log_std_param],
            )

            total_actor_loss_total += actor_loss.item()
            actor_updates_since_log += 1

            if cfg.auto_temperature:
                with _autocast_ctx():
                    temp_loss = compute_temperature_loss(
                        actor, log_std_param, temperature, states, cfg,
                    )
                temp_optimizer.zero_grad()
                _backward_clip_step(temp_loss, temp_optimizer, None)
                total_temp_loss += temp_loss.item()

            clamp_temperature(temperature, cfg)

        # === Target EMA ===
        soft_update(target_q1, q1, cfg.tau)
        soft_update(target_q2, q2, cfg.tau)

        global_step += 1
        updates_since_log += 1

        if not warmup_done and global_step >= cfg.critic_warmup_steps:
            warmup_done = True
            log_print(logger,
                f"Critic warmup complete at step={global_step}. Actor training starting.")

        if global_step % cfg.log_interval_steps == 0 and updates_since_log > 0:
            phase = "WARMUP" if not warmup_done else "TRAIN"
            std_str = ",".join(
                f"{v:.2f}" for v in log_std_param.data.clamp(cfg.log_std_min, cfg.log_std_max).exp().tolist()
            )
            actor_n = max(actor_updates_since_log, 1)
            log_print(logger,
                f"[{phase}] step={global_step} "
                f"crit={total_critic_loss / updates_since_log:.4f} "
                f"act={total_actor_loss_total / actor_n:.4f} "
                f"(sac={total_actor_sac_loss / actor_n:.3f} "
                f"caps_yp={total_actor_caps_loss / actor_n:.4f} "
                f"bias_yp={total_actor_bias_loss / actor_n:.4f} "
                f"bc={total_actor_bc_loss / actor_n:.4f} "
                f"aux_tgt={total_actor_aux_loss / actor_n:.4f}) "
                f"temp={total_temp_loss / actor_n:.4f} "
                f"α={temperature.alpha.item():.4f} "
                f"std=[{std_str}] "
                f"ret={mean_ret:.4f} "
                f"best_seen={best_observed_return:.4f} "
                f"best_exported={best_exported_return:.4f} "
                f"buf={replay.size}")
            total_critic_loss = 0.0
            total_actor_loss_total = 0.0
            total_actor_sac_loss = 0.0
            total_actor_caps_loss = 0.0
            total_actor_bias_loss = 0.0
            total_actor_bc_loss = 0.0
            total_actor_aux_loss = 0.0
            total_temp_loss = 0.0
            updates_since_log = 0
            actor_updates_since_log = 0

        # === Dual-KPI DeltaGate eval ===
        # In-flight pattern (matched movement training_loop.py:340-450):
        # Eval vuurt alleen wanneer er een LIVE candidate op de bots draait
        now = time.time()
        maybe_eval_due = (
            warmup_done
            and dual_kpi_cfg.dual_kpi
        )
        if maybe_eval_due and now - last_match_count_check_ts >= _MATCH_COUNT_POLL_INTERVAL_S:
            last_match_count_check_ts = now
            cached_matches_done = count_match_ends_since(last_dual_kpi_eval_ts)
        eval_due = (maybe_eval_due
                    and cached_matches_done >= dual_kpi_cfg.matches_per_eval_cycle)
        if eval_due:
            since_ts = last_dual_kpi_eval_ts
            last_dual_kpi_eval_ts = now
            cached_matches_done = 0
            last_match_count_check_ts = now
            if global_step < dual_kpi_cfg.min_steps_before_eval:
                log_print(logger,
                    f"DUAL_KPI_WAITING_RAMPUP: step={global_step} "
                    f"< min_steps_before_eval={dual_kpi_cfg.min_steps_before_eval} "
                    f"— eval skipped")
            else:
                # Per-weapon mode wanneer baseline.json `per_weapon_baselines`
                # bevat — dan AND-promotion over alle actieve wapens via
                # evaluate_per_weapon. Anders het bestaande aggregate-pad.
                per_weapon_mode = delta_gate.has_per_weapon_baselines()
                current_per_weapon: Optional[Dict[str, Dict[str, float]]] = None
                current_combat: Optional[float] = None
                current_aim: Optional[float] = None
                current_movement: Optional[float] = None

                if per_weapon_mode:
                    current_per_weapon = _read_current_kpis_per_weapon(logger, since_ts)
                    fetch_failed = current_per_weapon is None
                    insufficient = (current_per_weapon is not None
                                    and not current_per_weapon)
                else:
                    current_combat, current_aim, current_movement = \
                        _read_current_kpis(logger, since_ts)
                    fetch_failed = (current_combat is None or current_aim is None
                                    or current_movement is None)
                    insufficient = False

                if fetch_failed:
                    consecutive_kpi_fetch_fail += 1
                    if consecutive_kpi_fetch_fail % 5 == 1:
                        log_print(logger,
                            f"DUAL_KPI_NO_DATA: skipping eval cycle "
                            f"(consecutive misses={consecutive_kpi_fetch_fail})")
                elif insufficient:
                    consecutive_kpi_fetch_fail += 1
                    if consecutive_kpi_fetch_fail % 5 == 1:
                        log_print(logger,
                            f"DUAL_KPI_PER_WEAPON_NO_QUALIFIED: no weapon met "
                            f"_PER_WEAPON_MIN_OBS={_PER_WEAPON_MIN_OBS} this cycle "
                            f"(consecutive misses={consecutive_kpi_fetch_fail})")
                else:
                    consecutive_kpi_fetch_fail = 0
                    dual_kpi_cycle += 1
                    if per_weapon_mode:
                        eval_result = delta_gate.evaluate_per_weapon(current_per_weapon)
                        # Voor downstream code (PROMOTE-snapshot, logging) de
                        # min-ratio×baseline waarden gebruiken — dat zijn de
                        # absolute waarden van het worst-performing wapen, de
                        # gate-blokkerende KPI. Niet hetzelfde wapen voor combat
                        # en aim noodzakelijk; we tonen elk apart.
                        worst_c = min(eval_result.per_weapon.values(),
                                       key=lambda r: r.combat_ratio)
                        worst_a = min(eval_result.per_weapon.values(),
                                       key=lambda r: r.aim_ratio)
                        worst_m = min(eval_result.per_weapon.values(),
                                       key=lambda r: r.movement_ratio)
                        current_combat = worst_c.current_combat
                        current_aim = worst_a.current_aim
                        current_movement = worst_m.current_movement
                    else:
                        eval_result = delta_gate.evaluate(
                            current_combat_score=current_combat,
                            current_aim_rate=current_aim,
                            current_movement_score=current_movement,
                        )
                    placeholder_tag = (
                        " [PLACEHOLDER]" if baselines.source != str(_CONFIG_DIR / "baseline.json")
                        else ""
                    )
                    if per_weapon_mode:
                        active_weapons_str = ",".join(eval_result.active_weapons)
                        log_print(
                            logger,
                            f"RL_DUAL_KPI_{cfg.model_key}: cycle={dual_kpi_cycle}{placeholder_tag} | "
                            f"step={global_step} | "
                            f"mode=per-weapon active={active_weapons_str} | "
                            f"min_combat_ratio={eval_result.min_combat_ratio:.3f} | "
                            f"min_aim_ratio={eval_result.min_aim_ratio:.3f} | "
                            f"min_movement_ratio={eval_result.min_movement_ratio:.3f} | "
                            f"promote_window={eval_result.promote_streak}/"
                            f"{dual_kpi_cfg.promote_window_cycles} | "
                            f"rollback_window=combat:{eval_result.rollback_combat_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles},aim:"
                            f"{eval_result.rollback_aim_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles},movement:"
                            f"{eval_result.rollback_movement_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles} | "
                            f"action={eval_result.decision.lower()}"
                        )
                    else:
                        # Display gebruikt delta_gate.baseline_X (in-memory state)
                        # niet baselines.X (initial JointBaselines uit baseline.json
                        # bij startup) — die laatste wordt nooit ge-update na PROMOTE
                        # en geeft een misleidend display van de eigenlijke baseline
                        # waar de ratio's mee zijn berekend (delta_gate.evaluate()
                        # gebruikt delta_gate.baseline_X intern).
                        log_print(
                            logger,
                            f"RL_DUAL_KPI_{cfg.model_key}: cycle={dual_kpi_cycle}{placeholder_tag} | "
                            f"step={global_step} | "
                            f"combat_score={current_combat:.3f} (ratio={eval_result.combat_ratio:.3f}, "
                            f"baseline={delta_gate.baseline_combat:.3f}) | "
                            f"aim_rate={current_aim:.3f} (ratio={eval_result.aim_ratio:.3f}, "
                            f"baseline={delta_gate.baseline_aim:.3f}) | "
                            f"movement_flag={current_movement:.3f} "
                            f"(ratio={eval_result.movement_ratio:.3f}, "
                            f"baseline={(delta_gate.baseline_movement or 0.0):.3f}) | "
                            f"promote_window={eval_result.promote_streak}/"
                            f"{dual_kpi_cfg.promote_window_cycles} | "
                            f"rollback_window=combat:{eval_result.rollback_combat_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles},aim:"
                            f"{eval_result.rollback_aim_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles},movement:"
                            f"{eval_result.rollback_movement_streak}/"
                            f"{dual_kpi_cfg.rollback_window_cycles} | "
                            f"action={eval_result.decision.lower()}"
                        )
                    placeholder_mode = baselines.source.startswith("placeholder")
                    # Placeholder/null-baseline (bv. na een from-scratch retrain met
                    # baseline.json=null): de gate evalueert tegen 1.0 → elke
                    # voldoende-data cyclus is een PROMOTE. Behandel die EERSTE
                    # PROMOTE als BASELINE_INITIAL_SET (huidige policy = ijkpunt)
                    # i.p.v. te skippen — anders blijft de gate eeuwig inactief
                    # (elke eval skipt, baseline.json wordt nooit gevuld). Niet-
                    # PROMOTE in placeholder (INSUFFICIENT = te weinig matches) wel
                    # skippen: er is dan nog geen valide meting om vast te leggen.
                    is_baseline_initial_set = (
                        placeholder_mode and eval_result.decision == "PROMOTE")
                    if placeholder_mode and not is_baseline_initial_set:
                        log_print(
                            logger,
                            f"DUAL_KPI_PLACEHOLDER_SKIP: decision={eval_result.decision} "
                            f"genegeerd; wacht op eerste valide meting voor auto-init "
                            f"(baseline.json nog ongevuld)."
                        )
                    elif eval_result.decision == "INSUFFICIENT":
                        log_print(logger,
                            f"DUAL_KPI_INSUFFICIENT: step={global_step} "
                            f"reason={eval_result.reason}")
                    elif eval_result.decision == "PROMOTE":
                        if Path(pt_path).exists():
                            shutil.copy2(str(pt_path), str(best_pt_path))
                        if Path(onnx_path).exists():
                            copy_onnx_with_data(onnx_path, best_onnx_path)
                        _publish_promoted_champion(
                            model_key=mk, onnx_path=Path(onnx_path),
                            src_pt_path=pt_path,
                            eval_result=eval_result,
                            current_combat=current_combat,
                            current_aim=current_aim,
                            current_movement=current_movement,
                            logger=logger,
                        )
                        # Safeguard tegen meet-artefacten (zoals 22 mei avond
                        # ratio 4.811 → baseline schoot van 9.4 naar 34.7 in
                        # één cycle, daarna naar 60+). Cap baseline-rise op
                        # 1.5× per single PROMOTE: extreme combinaties van
                        # korte eval window + lucky burst (frags + dmg in 60s
                        # bucket) genereren onmogelijke baselines die elke
                        # toekomstige candidate doen rollbacken. Echte policy-
                        # improvements >50% per cycle zijn zeldzaam; meerdere
                        # opeenvolgende PROMOTEs kunnen wel ratchets vormen.
                        BASELINE_RISE_CAP_PER_PROMOTE = 1.5

                        def _cap_rise(new_val: float, old_val: Optional[float]) -> float:
                            if old_val is None or old_val <= 0:
                                return new_val
                            return min(new_val, old_val * BASELINE_RISE_CAP_PER_PROMOTE)

                        # Bij de initial-set is er geen valide oude baseline
                        # (delta_gate.baseline_* = 1.0 placeholder); de rise-cap zou
                        # de baseline absurd laag forceren (min(9.241, 1.0×1.5)=1.5).
                        # Geef None door zodat _cap_rise de meting ongemoeid als
                        # ijkpunt neemt.
                        _cap_ref_combat = None if is_baseline_initial_set else delta_gate.baseline_combat
                        _cap_ref_aim = None if is_baseline_initial_set else delta_gate.baseline_aim
                        _cap_ref_movement = None if is_baseline_initial_set else delta_gate.baseline_movement
                        capped_combat = _cap_rise(current_combat, _cap_ref_combat)
                        capped_aim = _cap_rise(current_aim, _cap_ref_aim)
                        capped_movement = _cap_rise(current_movement, _cap_ref_movement)
                        if (capped_combat < current_combat
                                or capped_aim < current_aim
                                or capped_movement < current_movement):
                            log_print(logger,
                                f"DUAL_KPI_BASELINE_RISE_CAPPED ({BASELINE_RISE_CAP_PER_PROMOTE}×): "
                                f"combat {current_combat:.3f}→{capped_combat:.3f} "
                                f"aim {current_aim:.3f}→{capped_aim:.3f} "
                                f"movement {current_movement:.3f}→{capped_movement:.3f} "
                                f"(meet-artefact safeguard)")
                        capped_per_weapon = None
                        if per_weapon_mode and current_per_weapon is not None:
                            capped_per_weapon = {}
                            for w, kpis in current_per_weapon.items():
                                bl = delta_gate.baseline_per_weapon.get(w, {})
                                capped_per_weapon[w] = {
                                    "combat_score": _cap_rise(
                                        float(kpis["combat_score"]),
                                        float(bl.get("combat_score") or 0.0) or None),
                                    "aim_rate": _cap_rise(
                                        float(kpis["aim_rate"]),
                                        float(bl.get("aim_rate") or 0.0) or None),
                                    "movement_score": _cap_rise(
                                        float(kpis["movement_score"]),
                                        float(bl.get("movement_score") or 0.0) or None),
                                }

                        # Per-weapon mode: gebruik current_per_weapon als nieuwe
                        # per-weapon baselines. Aggregate scalars worden tegelijk
                        # bijgewerkt zodat fallback consistent blijft (worst
                        # weapon vertegenwoordigt de promotion-blokkerende KPI).
                        delta_gate.update_baselines(
                            baseline_combat_score=capped_combat,
                            baseline_aim_rate=capped_aim,
                            baseline_movement_score=capped_movement,
                            baseline_per_weapon=capped_per_weapon,
                        )
                        _persist_promoted_baselines(
                            logger=logger,
                            combat_score=capped_combat,
                            shots_on_target_rate=capped_aim,
                            movement_flag_score=capped_movement,
                            per_weapon=capped_per_weapon,
                            champion_step=global_step,
                        )
                        if is_baseline_initial_set:
                            # Markeer dat de baseline nu geldig is zodat volgende
                            # cycli normaal evalueren (placeholder_mode wordt False).
                            baselines.source = str(_CONFIG_DIR / "baseline.json")
                            log_print(logger,
                                f"DUAL_KPI_BASELINE_INITIAL_SET: step={global_step} "
                                f"placeholder → baseline geïnitialiseerd op huidige policy "
                                f"(combat={capped_combat:.3f} aim={capped_aim:.3f} "
                                f"movement={capped_movement:.3f}); gating nu actief.")
                        elif per_weapon_mode:
                            baselines_summary = ", ".join(
                                f"{w}: combat={v['combat_score']:.3f}/aim={v['aim_rate']:.3f}/"
                                f"movement={v['movement_score']:.3f}"
                                for w, v in sorted((capped_per_weapon or current_per_weapon).items())
                            )
                            log_print(logger,
                                f"DUAL_KPI_PROMOTED: step={global_step} "
                                f"per-weapon baselines updated [{baselines_summary}]")
                        else:
                            log_print(logger,
                                f"DUAL_KPI_PROMOTED: step={global_step} "
                                f"baselines updated (combat={capped_combat:.3f} "
                                f"aim={capped_aim:.3f} movement={capped_movement:.3f})")
                    elif eval_result.decision == "ROLLBACK":
                        _rollback_joint_to_champion(
                            actor=actor, actor_optimizer=actor_optimizer,
                            log_std_param=log_std_param,
                            local_best_pt_path=best_pt_path,
                            model_key=mk, device=device, logger=logger,
                            reason=f"dual-KPI {eval_result.reason}",
                        )
                        # Adam-wipe escalation parity met DeltaGate (decoupled
                        # SAC). Restore zet alleen de weights terug; zonder
                        # optimizer-state wipe duwt het bewaarde momentum de
                        # policy direct weer naar de net afgekeurde toestand
                        # (ratchet-loop, geobserveerd 2026-05-15 vanaf 00:11).
                        if (delta_gate.consecutive_rollback_count
                                >= dual_kpi_cfg.consecutive_rollback_adam_wipe_threshold):
                            actor_optimizer.state.clear()
                            log_print(logger,
                                f"DUAL_KPI_ADAM_WIPE: "
                                f"{delta_gate.consecutive_rollback_count}× "
                                f"consecutive ROLLBACK — actor optimizer state "
                                f"cleared (threshold="
                                f"{dual_kpi_cfg.consecutive_rollback_adam_wipe_threshold})")
                            delta_gate.consecutive_rollback_count = 0
                    elif eval_result.decision == "NEUTRAL":
                        if eval_result.promote_streak > 0:
                            log_print(logger,
                                f"DUAL_KPI_PROMOTE_BUILDING: "
                                f"step={global_step} "
                                f"streak={eval_result.promote_streak}/"
                                f"{dual_kpi_cfg.promote_window_cycles}")
                        else:
                            log_print(logger,
                                f"DUAL_KPI_NEUTRAL: "
                                f"step={global_step} "
                                f"combat_ratio={eval_result.combat_ratio:.3f} "
                                f"aim_ratio={eval_result.aim_ratio:.3f} "
                                f"movement_ratio={eval_result.movement_ratio:.3f} "
                                f"rb_streaks=combat:{eval_result.rollback_combat_streak}/"
                                f"{dual_kpi_cfg.rollback_window_cycles},"
                                f"aim:{eval_result.rollback_aim_streak}/"
                                f"{dual_kpi_cfg.rollback_window_cycles},"
                                f"movement:{eval_result.rollback_movement_streak}/"
                                f"{dual_kpi_cfg.rollback_window_cycles}")

        # === Probe + ONNX export gate ===
        min_steps = cfg.min_steps_before_export
        if warmup_done and global_step - last_export_step >= cfg.export_interval_steps:
            if global_step < min_steps:
                log_print(logger, f"WAITING: step={global_step} ret={mean_ret:.4f}"
                          f" (min_steps_before_export={min_steps})")
            else:
                replay_pair = sample_replay_pair(
                    replay, cfg, probe_cfg.samples_per_probe,
                    stratify_indices=strata_idx if probe_cfg.stratified else None,
                    logger=logger, global_step=global_step,
                )
                pt_ok, pt_report = validate_actor_sane(
                    actor=actor, bc_actor=bc_actor, cfg=cfg,
                    device=device, logger=logger, global_step=global_step,
                    probe_cfg=probe_cfg, replay_pair=replay_pair, feature_idx=feature_idx,
                )
                pt_decision = pt_tracker.update(pt_report)
                pt_probe_exception = (not pt_ok) and len(pt_report.metrics) == 0
                if pt_probe_exception or pt_decision.rollback:
                    reason = (
                        "PT probe exception" if pt_probe_exception
                        else f"PT probe {pt_decision.reason}"
                    )
                    reverted = _maybe_rollback_to_champion(
                        actor=actor, actor_optimizer=actor_optimizer,
                        log_std_param=log_std_param,
                        champion_pt_path=best_pt_path, device=device, logger=logger,
                        reason=reason,
                    )
                    if not reverted:
                        log_print(logger,
                            f"EXPORT_GATED_NO_CHAMPION: step={global_step} "
                            f"— actor failed PT probe; no PROMOTED champion yet, "
                            f"skipping export (keep training).")
                elif not pt_ok:
                    log_print(logger,
                        f"PROBE_TRANSIENT: step={global_step} "
                        f"failed={pt_report.failed_names()} — 2-cycle filter still "
                        f"under threshold, skip export this cycle (no rollback).")
                else:
                    export_actor_onnx(actor, onnx_path, cfg.seq_len, cfg.input_size, device)
                    onnx_ok, onnx_report = validate_exported_onnx_sane(
                        onnx_path=onnx_path, bc_actor=bc_actor, cfg=cfg,
                        device=device, logger=logger, global_step=global_step,
                        probe_cfg=probe_cfg, replay_pair=replay_pair,
                        feature_idx=feature_idx,
                    )
                    onnx_decision = onnx_tracker.update(onnx_report)
                    onnx_probe_exception = (not onnx_ok) and len(onnx_report.metrics) == 0
                    if onnx_probe_exception or onnx_decision.rollback:
                        reason = (
                            "onnx probe exception" if onnx_probe_exception
                            else (onnx_decision.reason or "probe-fail")
                        )
                        # Prefer the promoted-champion ONNX over BC baseline.
                        # After the first PROMOTE the champion is the genuine
                        # best-known policy; rolling back to BC would push the
                        # bots from current best to a much weaker BC-clone —
                        # equivalent to a large regression purely because the
                        # FP16-quantized export tripped a probe. BC is only
                        # used pre-promotion, where no champion exists yet.
                        if Path(best_onnx_path).exists():
                            fallback = best_onnx_path
                            fallback_kind = "champion"
                        else:
                            fallback = bc_baseline_onnx
                            fallback_kind = "bc-baseline"
                        rolled = rollback_onnx(onnx_path, fallback, logger, global_step)
                        if rolled:
                            sync_onnx_to_servers(onnx_path)
                        log_print(logger,
                            f"EXPORT_GATED_FP16: step={global_step} "
                            f"reason={reason} fallback={fallback_kind} "
                            f"{'rolled-back' if rolled else 'no rollback'}")
                    elif not onnx_ok:
                        log_print(logger,
                            f"PROBE_TRANSIENT_FP16: step={global_step} "
                            f"failed={onnx_report.failed_names()} — 2-cycle filter still "
                            f"under threshold, skip sync/deploy this cycle (no rollback).")
                    else:
                        sync_onnx_to_servers(onnx_path)
                        actor_state_clean = strip_compile_prefix(actor.state_dict())
                        torch.save({"model_state_dict": actor_state_clean,
                                    "global_step": global_step}, str(pt_path))
                        if mean_ret > best_exported_return:
                            best_exported_return = mean_ret
                        log_print(logger,
                            f"DEPLOYED: step={global_step} ret={mean_ret:.4f} "
                            f"best_seen={best_observed_return:.4f} "
                            f"(matches={cached_matches_done}/{dual_kpi_cfg.matches_per_eval_cycle})")
            last_export_step = global_step

        if global_step - last_checkpoint_step >= cfg.checkpoint_interval_steps:
            save_training_state(
                pt_path, sac_ckpt_path, actor, q1, q2, target_q1, target_q2, temperature,
                critic_optimizer, actor_optimizer, temp_optimizer, global_step,
                best_exported_return, best_observed_return, baseline_return,
                log_std_param, last_export_step,
            )
            last_checkpoint_step = global_step

    batch_provider.stop()
    log_print(logger, f"Shutdown requested. Saving at step={global_step}")
    save_training_state(
        pt_path, sac_ckpt_path, actor, q1, q2, target_q1, target_q2, temperature,
        critic_optimizer, actor_optimizer, temp_optimizer, global_step,
        best_exported_return, best_observed_return, baseline_return,
        log_std_param, last_export_step,
    )
    log_print(logger, "Checkpoints saved. Exiting.")
