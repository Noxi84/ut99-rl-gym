"""Per-head + cross-head hard-fail probes for the joint VR+shooting model.

Implements the probe schema documented in
``docs/joint_policy/vr-shooting-sac-merge.md`` §10.1 + ``probe.json``. Distinct
from ``train.rl.shared.probe_config.ProbeConfig`` (steering-only schema for VR
+ movement) because the joint model needs per-head metrics across three action
classes (continuous steering, binary fire, categorical target) plus two
cross-head consistency floors.

Two probe entry points:

- ``evaluate_probes(actor, bc_actor, states, ...)``: pure metric computation on
  a single backend (PyTorch FP32 OR exported ONNX). Returns a ``ProbeReport``
  with per-probe pass/fail booleans + raw metric values. **No state.**
- ``ProbeViolationTracker.update(report)``: 2-cycle filter + collapse-immediate
  override. Returns a ``RollbackDecision`` so the training loop can decide
  whether to revert. **Stateful — one instance per backend per run.**

Sampling: **strata-stratified per Fase 4b Deel D**. Vijf strata (combat_active,
no_combat, carrier_active, post_damage, default) × 64 per stratum = 320 samples
totaal (zie ``strata.py`` voor classification regels en
``docs/joint_policy/probe-design.md`` regels 31-50 voor design). De v1 uniform
stub is verwijderd; ondervolle strata loggen een ``STRATA_UNDERVOL`` warning
en dragen minder samples bij maar blokkeren de probe-eval niet.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import torch



# -----------------------------------------------------------------------------
# Joint action-dim conventions (per features.json target_features order).
# Hard-coded here intentionally: the joint trainer always uses this order. If
# features.json swaps the order, the BC trainer + the SAC bootstrap will both
# break first, so misalignment is caught at startup not at probe time.
# -----------------------------------------------------------------------------
DIM_JUMP = 3
DIM_DUCK = 4
DIM_IDLE = 5
DIM_YAW = 6
DIM_PITCH = 7
DIM_FIRE = 8
DIM_ALTFIRE = 9


@dataclass(frozen=True)
class JointFeatureIndices:
    """Flat indices the cross-head probes need to read from a state window.

    All indices are into the flat per-frame input vector ``cfg.input_features``;
    callers should slice the *last* timestep of a state window to get the
    "current" value the bot saw at decision time. ``-1`` marks a feature that
    isn't present in this config — cross-head probes that depend on it become
    no-ops (logged as "skipped", not "failed") rather than raising.
    """
    enemy_relSin: tuple[int, ...]    # 5 entries
    enemy_relCos: tuple[int, ...]
    enemy_pitchBearing: tuple[int, ...]
    enemy_aim_alignment: tuple[int, ...]
    enemy_isAlive: tuple[int, ...]
    primary_aim_pitch_error: int
    secondary_aim_pitch_error: int
    shoot_intent_pitch_error: int

    @classmethod
    def from_input_features(cls, input_features: list[str]) -> "JointFeatureIndices":
        def idx(name: str) -> int:
            try:
                return input_features.index(name)
            except ValueError:
                return -1

        return cls(
            enemy_relSin=tuple(idx(f"enemy{i}_relSin") for i in range(5)),
            enemy_relCos=tuple(idx(f"enemy{i}_relCos") for i in range(5)),
            enemy_pitchBearing=tuple(
                idx(f"enemy{i}_pitchBearing_norm") for i in range(5)
            ),
            enemy_aim_alignment=tuple(
                idx(f"enemy{i}_aimAlignmentDot_norm") for i in range(5)
            ),
            enemy_isAlive=tuple(idx(f"enemy{i}_isAlive") for i in range(5)),
            primary_aim_pitch_error=idx("primaryAimPitchError_norm"),
            secondary_aim_pitch_error=idx("secondaryAimPitchError_norm"),
            shoot_intent_pitch_error=idx("shootIntentPitchError_norm"),
        )

    def has_bearing(self) -> bool:
        return all(i >= 0 for i in self.enemy_relSin) and all(i >= 0 for i in self.enemy_relCos)

    def has_aim_alignment(self) -> bool:
        return all(i >= 0 for i in self.enemy_aim_alignment)

    def has_enemy_alive(self) -> bool:
        return all(i >= 0 for i in self.enemy_isAlive)


@dataclass
class ProbeMetric:
    """Per-probe outcome — value + pass/fail + the threshold that produced it.

    ``passed`` collapses the full probe result into a single bool for the
    violation tracker. ``skipped`` differentiates "passed because the probe
    couldn't run" (no BC ref / missing features) from "passed because the
    metric is healthy" — only ``passed AND not skipped`` increments a streak.
    """
    name: str
    value: float
    threshold: float
    passed: bool
    skipped: bool = False
    detail: str = ""


@dataclass
class ProbeReport:
    """Outcome of a single probe-cycle on one backend (PyTorch FP32 / ONNX FP16).

    ``collapse_both_dims`` is the immediate-rollback trigger (commitment 1,
    section 10.1: both fire AND altFire collapse → rollback after one cycle,
    not two). All other failures go through the 2-cycle filter.
    """
    metrics: list[ProbeMetric] = field(default_factory=list)
    collapse_both_dims: bool = False

    @property
    def any_failed(self) -> bool:
        return any((not m.passed) and (not m.skipped) for m in self.metrics)

    def failed_names(self) -> list[str]:
        return [m.name for m in self.metrics if (not m.passed) and (not m.skipped)]


@dataclass
class RollbackDecision:
    """ProbeViolationTracker output. ``rollback=True`` means the trainer should
    revert to the previous champion. ``reason`` is a short human-readable
    string; ``immediate=True`` flags the fire+altFire collapse path so the
    training loop can log it differently."""
    rollback: bool
    immediate: bool
    reason: str


class ProbeViolationTracker:
    """2-cycle filter for hard-fail probe outcomes.

    Each probe has its own consecutive-violation streak. A streak ≥
    ``consecutive_violations_for_rollback`` triggers a rollback. Any cycle that
    passes a probe resets that probe's streak (so transient noise can't ratchet
    across far-apart violations).

    Collapse (fire+altFire both below floor) bypasses the streak: one cycle of
    ``collapse_both_dims=True`` immediately requests rollback. Sectie 10.1:
    *"fire+altFire BEIDE collapse → immediate rollback (één-cyclus, geen filter)"*.
    """

    def __init__(self, *, consecutive_violations_for_rollback: int,
                 collapse_immediate: bool):
        self.threshold = int(consecutive_violations_for_rollback)
        self.collapse_immediate = bool(collapse_immediate)
        self._streaks: dict[str, int] = {}

    def update(self, report: ProbeReport) -> RollbackDecision:
        if self.collapse_immediate and report.collapse_both_dims:
            self._streaks.clear()
            return RollbackDecision(
                rollback=True,
                immediate=True,
                reason="fire+altFire collapse (both flip_rate < floor)",
            )

        triggered: list[str] = []
        for m in report.metrics:
            if m.skipped:
                continue
            if m.passed:
                self._streaks.pop(m.name, None)
                continue
            self._streaks[m.name] = self._streaks.get(m.name, 0) + 1
            if self._streaks[m.name] >= self.threshold:
                triggered.append(m.name)

        if triggered:
            for name in triggered:
                self._streaks.pop(name, None)
            return RollbackDecision(
                rollback=True,
                immediate=False,
                reason=f"persistent violation ({self.threshold}× consecutive): "
                       + ", ".join(triggered),
            )
        return RollbackDecision(rollback=False, immediate=False, reason="")


# -----------------------------------------------------------------------------
# Metric computation — pure, no state.
# -----------------------------------------------------------------------------


def _flip_rate(binary_actions: np.ndarray) -> float:
    """Fraction of consecutive sample-pairs where the binary action flipped.

    ``binary_actions`` shape [B] with values in {0, 1} (post-threshold). With a
    uniform random sample, "consecutive" is just index-order over the batch;
    that's fine for a population statistic — flip_rate is invariant under
    sample ordering once B is large enough (256 per the joint probe.json).
    """
    if binary_actions.size < 2:
        return 0.0
    return float(np.mean(binary_actions[1:] != binary_actions[:-1]))


def _categorical_entropy(probs: np.ndarray) -> np.ndarray:
    """Per-sample entropy in nats. ``probs`` shape [B, K]."""
    safe = np.clip(probs, 1e-12, 1.0)
    return -np.sum(probs * np.log(safe), axis=-1)


def _pitch_context_mask(last_frame_states: np.ndarray,
                        feature_idx: JointFeatureIndices) -> np.ndarray:
    """Samples where non-zero pitch is expected from target geometry.

    Pitch deltas are allowed, and required, when a target/weapon hint asks for
    vertical aim: flak/rocket arcs, future height-difference maps, airborne
    enemies, etc. The hard bias gate should only police neutral contexts where
    a constant pitch sign would simply climb into the clamp.
    """
    if last_frame_states.size == 0:
        return np.zeros((0,), dtype=bool)
    n = last_frame_states.shape[0]
    mask = np.zeros(n, dtype=bool)
    threshold = 0.05
    for idx in feature_idx.enemy_pitchBearing:
        if idx >= 0:
            mask |= np.abs(last_frame_states[:, idx]) > threshold
    for idx in (
        feature_idx.primary_aim_pitch_error,
        feature_idx.secondary_aim_pitch_error,
        feature_idx.shoot_intent_pitch_error,
    ):
        if idx >= 0:
            mask |= np.abs(last_frame_states[:, idx]) > threshold
    return mask


def _evaluate_steering(
    actor_actions: np.ndarray,    # tanh-space, [B, action_dim]
    actor_actions_next: np.ndarray,
    bc_actions: Optional[np.ndarray],
    bc_actions_next: Optional[np.ndarray],
    last_frame_states: np.ndarray,
    feature_idx: JointFeatureIndices,
    cfg,
) -> list[ProbeMetric]:
    metrics: list[ProbeMetric] = []
    pitch_context = _pitch_context_mask(last_frame_states, feature_idx)
    for dim, name in [(DIM_YAW, "yaw"), (DIM_PITCH, "pitch")]:
        a = actor_actions[:, dim]
        sat = float(np.mean(np.abs(a) > 0.95))
        metrics.append(ProbeMetric(
            name=f"{name}_sat", value=sat, threshold=cfg.sat_limit,
            passed=sat <= cfg.sat_limit,
            detail=f"sat({name})={sat:.3f} ≤ {cfg.sat_limit}",
        ))

        spread = float(np.std(a))
        metrics.append(ProbeMetric(
            name=f"{name}_spread", value=spread, threshold=cfg.spread_floor,
            passed=spread >= cfg.spread_floor,
            detail=f"spread({name})={spread:.3f} ≥ {cfg.spread_floor}",
        ))

        bias_slice = a
        bc_bias_slice = bc_actions[:, dim] if bc_actions is not None else None
        if name == "pitch":
            neutral_mask = ~pitch_context
            neutral_count = int(np.sum(neutral_mask))
            min_neutral = min(8, max(1, len(a) // 10))
            if neutral_count >= min_neutral:
                bias_slice = a[neutral_mask]
                if bc_actions is not None:
                    bc_bias_slice = bc_actions[neutral_mask, dim]
            else:
                metrics.append(ProbeMetric(
                    name="pitch_gain_bias", value=0.0,
                    threshold=cfg.pitch_gain_bias_limit,
                    passed=True, skipped=True,
                    detail=(
                        "pitch bias skipped — insufficient neutral pitch-context "
                        f"samples ({neutral_count}<{min_neutral})"
                    ),
                ))
                if bc_actions is not None and bc_actions_next is not None:
                    metrics.append(ProbeMetric(
                        name="pitch_gain_bias_delta", value=0.0,
                        threshold=cfg.pitch_gain_bias_delta_limit,
                        passed=True, skipped=True,
                        detail=(
                            "pitch bias_delta skipped — insufficient neutral "
                            f"pitch-context samples ({neutral_count}<{min_neutral})"
                        ),
                    ))
                bias_slice = None

        if bias_slice is not None:
            mean_a = float(np.mean(bias_slice))
            bias = float(abs(mean_a))
            abs_bias_limit = (
                cfg.yaw_gain_bias_limit if name == "yaw" else cfg.pitch_gain_bias_limit
            )
            context_detail = ""
            if name == "pitch":
                context_detail = f" on neutral pitch-context n={len(bias_slice)}"
            metrics.append(ProbeMetric(
                name=f"{name}_gain_bias", value=bias, threshold=abs_bias_limit,
                passed=bias <= abs_bias_limit,
                detail=f"|mean({name})|{context_detail}={bias:.3f} ≤ {abs_bias_limit}",
            ))
        else:
            mean_a = float(np.mean(a))

        a_next = actor_actions_next[:, dim]
        actor_jitter = float(np.sqrt(np.mean((a - a_next) ** 2)))
        if bc_actions is not None and bc_actions_next is not None:
            if bias_slice is not None and bc_bias_slice is not None:
                bc_mean = float(np.mean(bc_bias_slice))
                bias_delta = float(abs(mean_a - bc_mean))
                delta_limit = (
                    cfg.yaw_gain_bias_delta_limit
                    if name == "yaw"
                    else cfg.pitch_gain_bias_delta_limit
                )
                context_detail = ""
                if name == "pitch":
                    context_detail = f" on neutral pitch-context n={len(bias_slice)}"
                metrics.append(ProbeMetric(
                    name=f"{name}_gain_bias_delta", value=bias_delta, threshold=delta_limit,
                    passed=bias_delta <= delta_limit,
                    detail=(
                        f"|mean({name})-mean(bc)|{context_detail}="
                        f"{bias_delta:.3f} ≤ {delta_limit}"
                    ),
                ))

            bc_jitter = float(np.sqrt(np.mean((bc_actions[:, dim] - bc_actions_next[:, dim]) ** 2)))
            bc_floor = max(bc_jitter, 1e-3)
            ratio = actor_jitter / bc_floor
            metrics.append(ProbeMetric(
                name=f"{name}_jitter_ratio", value=ratio, threshold=cfg.jitter_ratio_vs_bc,
                passed=ratio <= cfg.jitter_ratio_vs_bc,
                detail=f"jitter({name})={actor_jitter:.3f}/bc={bc_jitter:.3f} "
                       f"ratio={ratio:.2f} ≤ {cfg.jitter_ratio_vs_bc}×",
            ))
        else:
            metrics.append(ProbeMetric(
                name=f"{name}_gain_bias_delta", value=0.0,
                threshold=0.0,
                passed=True, skipped=True,
                detail=f"bias_delta({name}) skipped — no BC ref",
            ))
            metrics.append(ProbeMetric(
                name=f"{name}_jitter_ratio", value=actor_jitter,
                threshold=cfg.jitter_ratio_vs_bc,
                passed=True, skipped=True,
                detail=f"jitter({name})={actor_jitter:.3f} — no BC ref",
            ))
    return metrics


def _evaluate_binary(
    actor_actions: np.ndarray,
    bc_actions: Optional[np.ndarray],
    cfg,
) -> tuple[list[ProbeMetric], bool]:
    """Binary (fire/altFire) probe metrics.

    Two absolute checks per head plus a both-dims collapse trigger:

    - ``fire_rate`` / ``altFire_rate`` must lie in ``[rate_floor, rate_ceiling]``.
      This is a pure pathology vangnet: a head stuck at 0 (never fires) or 1
      (always fires) is dead. Anything in between is a legitimate emergent
      strategy — RL is free to push altFire-rate to whatever the rewards select
      for, even if BC barely uses it. The previous ``flip_ratio_vs_bc`` check
      anchored the policy to BC's altFire usage, which on weapon-specific
      training (e.g. FlakCannon-only) penalised exactly the kind of tactical
      altFire learning the rewards encourage.
    - ``both_dims_collapse_floor`` triggers immediate rollback when *both*
      fire and altFire flip-rates collapse below the floor — that's a
      NaN/dead-policy signature, not a strategy. Single-dim collapse is fine
      (alt-only or primary-only is a valid emergent stance).

    BC reference is no longer consulted here; it lives in the BC-loss term
    inside the SAC actor update, not in the probe gate.
    """
    metrics: list[ProbeMetric] = []
    # Post-tanh threshold at 0.0 → binary state.
    fire_binary = (actor_actions[:, DIM_FIRE] > 0.0).astype(np.int32)
    alt_binary = (actor_actions[:, DIM_ALTFIRE] > 0.0).astype(np.int32)

    fire_rate = float(np.mean(fire_binary))
    alt_rate = float(np.mean(alt_binary))

    for name, rate in [("fire_rate", fire_rate), ("altFire_rate", alt_rate)]:
        in_range = cfg.rate_floor <= rate <= cfg.rate_ceiling
        metrics.append(ProbeMetric(
            name=name, value=rate,
            threshold=cfg.rate_floor,
            passed=in_range,
            detail=f"{name}={rate:.3f} ∈ [{cfg.rate_floor}, {cfg.rate_ceiling}]",
        ))

    fire_flip = _flip_rate(fire_binary)
    alt_flip = _flip_rate(alt_binary)
    collapse_both = (fire_flip < cfg.both_dims_collapse_floor
                     and alt_flip < cfg.both_dims_collapse_floor)
    # bc_actions is unused in the absolute-rate scheme; argument kept for
    # signature stability with the caller (evaluate_probes_from_raw).
    del bc_actions
    return metrics, collapse_both


def _evaluate_movement(actor_actions: np.ndarray, cfg) -> list[ProbeMetric]:
    """Movement sanity probes for full-joint candidates.

    These are pathology guards, not style constraints. Route quality is still
    measured by DeltaGate's movement KPI, but export should not pass a policy
    that idles most of the batch or holds jump/duck nearly always.
    """
    metrics: list[ProbeMetric] = []
    idle = (actor_actions[:, DIM_IDLE] > 0.0).astype(np.int32)
    jump = (actor_actions[:, DIM_JUMP] > 0.0).astype(np.int32)
    duck = (actor_actions[:, DIM_DUCK] > 0.0).astype(np.int32)

    idle_rate = float(np.mean(idle))
    jump_rate = float(np.mean(jump))
    duck_rate = float(np.mean(duck))

    for name, rate, ceiling in [
        ("idle_rate", idle_rate, cfg.idle_rate_ceiling),
        ("jump_rate", jump_rate, cfg.jump_rate_ceiling),
        ("duck_rate", duck_rate, cfg.duck_rate_ceiling),
    ]:
        metrics.append(ProbeMetric(
            name=name,
            value=rate,
            threshold=ceiling,
            passed=rate <= ceiling,
            detail=f"{name}={rate:.3f} ≤ {ceiling}",
        ))
    return metrics


def _evaluate_target(
    target_logits: np.ndarray,   # [B, 5]
    last_frame_states: np.ndarray,
    feature_idx: JointFeatureIndices,
    cfg,
) -> list[ProbeMetric]:
    metrics: list[ProbeMetric] = []
    if not feature_idx.has_enemy_alive():
        metrics.append(ProbeMetric(
            name="target_entropy", value=0.0,
            threshold=cfg.target_entropy_floor_3plus_enemies,
            passed=True, skipped=True,
            detail="H(target) skipped — enemy*_isAlive features absent",
        ))
        metrics.append(ProbeMetric(
            name="target_concentration", value=0.0,
            threshold=cfg.target_concentration_ceiling,
            passed=True, skipped=True,
            detail="max(p_target) skipped — enemy*_isAlive features absent",
        ))
        return metrics

    alive = np.stack(
        [last_frame_states[:, i] > 0.5 for i in feature_idx.enemy_isAlive],
        axis=-1,
    )
    eligible = np.sum(alive, axis=-1) >= 3
    n_eligible = int(np.sum(eligible))
    if n_eligible == 0:
        metrics.append(ProbeMetric(
            name="target_entropy", value=0.0,
            threshold=cfg.target_entropy_floor_3plus_enemies,
            passed=True, skipped=True,
            detail="H(target) skipped — no samples with >=3 live enemies",
        ))
        metrics.append(ProbeMetric(
            name="target_concentration", value=0.0,
            threshold=cfg.target_concentration_ceiling,
            passed=True, skipped=True,
            detail="max(p_target) skipped — no samples with >=3 live enemies",
        ))
        return metrics

    target_logits = target_logits[eligible]
    # Mask out finfo.min sentinels for absent slots — TargetHead writes them.
    # For the entropy / concentration check we treat sentinel logits as -inf
    # and softmax routes their mass to zero, so probs sum over present slots only.
    probs = _stable_softmax(target_logits)
    entropy = _categorical_entropy(probs)  # [B]
    avg_entropy = float(np.mean(entropy))
    concentration = float(np.mean(np.max(probs, axis=-1)))

    metrics.append(ProbeMetric(
        name="target_entropy", value=avg_entropy,
        threshold=cfg.target_entropy_floor_3plus_enemies,
        passed=avg_entropy >= cfg.target_entropy_floor_3plus_enemies,
        detail=f"H(target)={avg_entropy:.3f} nats on {n_eligible} samples "
               f"with >=3 live enemies ≥ {cfg.target_entropy_floor_3plus_enemies}",
    ))
    metrics.append(ProbeMetric(
        name="target_concentration", value=concentration,
        threshold=cfg.target_concentration_ceiling,
        passed=concentration <= cfg.target_concentration_ceiling,
        detail=f"max(p_target)={concentration:.3f} on {n_eligible} samples "
               f"with >=3 live enemies ≤ {cfg.target_concentration_ceiling}",
    ))
    return metrics


def _stable_softmax(logits: np.ndarray) -> np.ndarray:
    """Softmax that tolerates finfo.min sentinels (absent slots in TargetHead)
    without producing NaN. ``logits`` shape [B, K]."""
    shifted = logits - np.max(logits, axis=-1, keepdims=True)
    exp = np.exp(shifted)
    denom = np.sum(exp, axis=-1, keepdims=True)
    denom = np.where(denom > 0, denom, 1.0)
    return exp / denom


def _evaluate_cross_head(
    actor_actions: np.ndarray,
    target_logits: np.ndarray,
    last_frame_states: np.ndarray,    # [B, F_flat]
    feature_idx: JointFeatureIndices,
    cfg,
) -> list[ProbeMetric]:
    """CC1 + CC2.

    CC1: ``target≈yaw consistency`` — fraction of samples where the yaw output
    points in the same horizontal half-plane as the chosen enemy's bearing. A
    fully-coordinated joint policy almost always agrees on direction; <60%
    suggests the heads have diverged.

    CC2: ``fire⇒aim_aligned`` — among samples where the policy fires, fraction
    where the chosen enemy's ``aimAlignmentDot`` is positive (bot is actually
    looking at the target). Catches the "fire wildly, aim elsewhere" failure
    mode.

    Both checks skip cleanly when the required features are missing from the
    grouping (logged as ``skipped=True``).
    """
    metrics: list[ProbeMetric] = []
    target_idx = np.argmax(target_logits, axis=-1)  # [B]

    if feature_idx.has_bearing():
        rel_sin = np.stack(
            [last_frame_states[:, i] for i in feature_idx.enemy_relSin], axis=-1,
        )  # [B, 5]
        # Pick the chosen target's relSin: sign>0 means enemy is to the bot's
        # right; positive yaw_delta also means turn-right. So the consistency
        # check is sign(target_relSin) == sign(yaw_delta).
        chosen_relSin = rel_sin[np.arange(len(target_idx)), target_idx]
        yaw = actor_actions[:, DIM_YAW]
        # Only count samples where the bearing isn't near zero (target dead
        # ahead — either direction is consistent). Threshold 0.05 ≈ ±3°.
        valid_mask = np.abs(chosen_relSin) > 0.05
        n_valid = int(valid_mask.sum())
        if n_valid > 0:
            consistent = (np.sign(chosen_relSin[valid_mask])
                          == np.sign(yaw[valid_mask]))
            fraction = float(np.mean(consistent))
        else:
            fraction = 1.0  # nothing to disagree on
        metrics.append(ProbeMetric(
            name="cc1_target_yaw_consistency", value=fraction,
            threshold=cfg.target_yaw_consistency_floor,
            passed=fraction >= cfg.target_yaw_consistency_floor,
            detail=f"CC1 sign-match={fraction:.3f} ({n_valid} valid) "
                   f"≥ {cfg.target_yaw_consistency_floor}",
        ))
    else:
        metrics.append(ProbeMetric(
            name="cc1_target_yaw_consistency", value=0.0,
            threshold=cfg.target_yaw_consistency_floor,
            passed=True, skipped=True,
            detail="CC1 — enemy bearing features absent",
        ))

    if feature_idx.has_aim_alignment():
        aim = np.stack(
            [last_frame_states[:, i] for i in feature_idx.enemy_aim_alignment],
            axis=-1,
        )  # [B, 5]
        chosen_aim = aim[np.arange(len(target_idx)), target_idx]
        fire_on = actor_actions[:, DIM_FIRE] > 0.0
        n_fire = int(fire_on.sum())
        if n_fire > 0:
            aligned = chosen_aim[fire_on] > 0.5
            fraction = float(np.mean(aligned))
        else:
            fraction = 1.0  # no fire events ⇒ no misalignment to gate
        metrics.append(ProbeMetric(
            name="cc2_fire_aim_alignment", value=fraction,
            threshold=cfg.fire_aim_alignment_floor,
            passed=fraction >= cfg.fire_aim_alignment_floor,
            detail=f"CC2 fire→aim={fraction:.3f} ({n_fire} fire) "
                   f"≥ {cfg.fire_aim_alignment_floor}",
        ))
    else:
        metrics.append(ProbeMetric(
            name="cc2_fire_aim_alignment", value=0.0,
            threshold=cfg.fire_aim_alignment_floor,
            passed=True, skipped=True,
            detail="CC2 — enemy aim-alignment features absent",
        ))
    return metrics


# -----------------------------------------------------------------------------
# Public entry point — backend-agnostic (PyTorch FP32 or ONNX FP16 raw outputs).
# -----------------------------------------------------------------------------

def evaluate_probes_from_raw(
    *,
    actor_actions: np.ndarray,
    actor_actions_next: np.ndarray,
    target_logits: np.ndarray,
    bc_actions: Optional[np.ndarray],
    bc_actions_next: Optional[np.ndarray],
    last_frame_states: np.ndarray,
    feature_idx: JointFeatureIndices,
    cfg,
) -> ProbeReport:
    """Build a ProbeReport from raw backend outputs.

    Inputs are all numpy so the same function can score PyTorch FP32 outputs
    (run with torch.no_grad → .cpu().numpy()) and ONNX FP16 outputs (run
    through onnxruntime CPUExecutionProvider → already numpy). The training
    loop and the export gate both call this with their respective outputs and
    feed the report to a shared ProbeViolationTracker per backend.
    """
    metrics: list[ProbeMetric] = []
    metrics.extend(_evaluate_steering(
        actor_actions, actor_actions_next, bc_actions, bc_actions_next,
        last_frame_states, feature_idx, cfg,
    ))
    binary_metrics, collapse_both = _evaluate_binary(
        actor_actions, bc_actions, cfg,
    )
    metrics.extend(binary_metrics)
    metrics.extend(_evaluate_movement(actor_actions, cfg))
    metrics.extend(_evaluate_target(target_logits, last_frame_states, feature_idx, cfg))
    metrics.extend(_evaluate_cross_head(
        actor_actions, target_logits, last_frame_states, feature_idx, cfg,
    ))
    return ProbeReport(metrics=metrics, collapse_both_dims=collapse_both)


def evaluate_probes_pytorch(
    actor,
    bc_actor,
    states_t: torch.Tensor,
    next_states_t: torch.Tensor,
    feature_idx: JointFeatureIndices,
    cfg,
) -> ProbeReport:
    """PyTorch-side probe: runs the FP32 actor (and BC actor if provided) and
    feeds raw outputs to ``evaluate_probes_from_raw``.

    ``states_t`` / ``next_states_t``: float tensors [B, T, F]. ``feature_idx``
    is built once at trainer start; reused every cycle.
    """
    was_training = actor.training
    actor.eval()
    if bc_actor is not None:
        bc_actor.eval()
    try:
        with torch.no_grad():
            actor_out = actor.forward_with_target(states_t)
            if not isinstance(actor_out, tuple):
                raise RuntimeError(
                    "joint actor must expose target_head; forward_with_target "
                    "returned a single tensor"
                )
            raw_action, target_logits = actor_out
            actor_action = torch.tanh(raw_action).cpu().numpy()
            target_np = target_logits.cpu().numpy()

            raw_action_next = actor.forward_with_target(next_states_t)[0]
            actor_action_next = torch.tanh(raw_action_next).cpu().numpy()

            bc_action_np = None
            bc_action_next_np = None
            if bc_actor is not None:
                bc_raw = bc_actor(states_t)
                bc_action_np = torch.tanh(bc_raw).cpu().numpy()
                bc_raw_next = bc_actor(next_states_t)
                bc_action_next_np = torch.tanh(bc_raw_next).cpu().numpy()

            last_frame_states = states_t[:, -1, :].cpu().numpy()

        return evaluate_probes_from_raw(
            actor_actions=actor_action,
            actor_actions_next=actor_action_next,
            target_logits=target_np,
            bc_actions=bc_action_np,
            bc_actions_next=bc_action_next_np,
            last_frame_states=last_frame_states,
            feature_idx=feature_idx,
            cfg=cfg,
        )
    finally:
        if was_training:
            actor.train()


def format_report(report: ProbeReport) -> str:
    """One-line summary suitable for log_print."""
    parts: list[str] = []
    for m in report.metrics:
        flag = "OK" if m.passed else "FAIL"
        if m.skipped:
            flag = "SKIP"
        parts.append(f"{m.name}[{flag}]={m.value:.3f}")
    collapse = " COLLAPSE-BOTH" if report.collapse_both_dims else ""
    return " ".join(parts) + collapse
