"""Joint VR+shooting SAC config loader.

``rl_pawn`` is geregistreerd in ``resources/models/index.json``;
:func:`train.rl.shared.sac_core.config.load_config('rl_pawn')` bouwt
rechtstreeks een :class:`SACConfig` uit ``resources/models/rl_pawn/``.

Twee artefacten bovenop ``SACConfig`` zijn joint-specifiek:

1. :class:`JointProbeConfig` — leest ``resources/models/rl_pawn/probe.json``
   via :meth:`PropertyReader._get_dict("/models/rl_pawn/probe")` met
   per-head limits (steering / binary / categorical target) en cross-head floors.
2. :class:`DualKPIDeltaGateConfig` — leest het ``export_gate.json`` blok
   (``dual_kpi=true``).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from train.common import PropertyReader
from train.rl.shared.delta_gate import DualKPIDeltaGateConfig
from train.rl.shared.sac_core.config import SACConfig, load_config

JOINT_MODEL_KEY = "rl_pawn"

_REPO_ROOT = Path(__file__).resolve().parents[4]
# Behouden voor baseline.json reads in training_loop._load_baselines —
# PropertyReader leest baseline.json óók, maar de baseline-load wil expliciete
# None-checks doen voordat de DualKPIDeltaGate crasht; daarvoor is een
# directe path-based read pragmatischer dan een dict uit PropertyReader.
_CONFIG_DIR = _REPO_ROOT / "resources" / "models" / JOINT_MODEL_KEY


@dataclass(frozen=True)
class JointProbeConfig:
    """Joint VR+shooting probe schema (commitment 1 — hard-fail v1).

    Different layout than ``train.rl.shared.probe_config.ProbeConfig`` because
    the joint model needs per-head limits (steering vs binary vs categorical
    target) and cross-head floors that aren't in the steering-only schema. See
    ``resources/models/rl_pawn/probe.json``.
    """
    mode: str
    samples_per_probe: int
    stratified: bool
    consecutive_violations_for_rollback: int
    collapse_immediate: bool

    # Steering (yaw/pitch)
    sat_limit: float
    jitter_ratio_vs_bc: float
    spread_floor: float
    yaw_gain_bias_limit: float
    pitch_gain_bias_limit: float
    yaw_gain_bias_delta_limit: float
    pitch_gain_bias_delta_limit: float

    # Binary (fire/altFire) — absolute-rate bounds, no BC comparison.
    rate_floor: float
    rate_ceiling: float
    both_dims_collapse_floor: float

    # Movement pathologies — guard full-joint candidates that stop route-play.
    idle_rate_ceiling: float
    jump_rate_ceiling: float
    duck_rate_ceiling: float

    # Categorical target
    target_entropy_floor_3plus_enemies: float
    target_concentration_ceiling: float

    # Cross-head (CC1, CC2)
    target_yaw_consistency_floor: float
    fire_aim_alignment_floor: float

    @classmethod
    def from_dict(cls, data: dict) -> "JointProbeConfig":
        try:
            steering = data["steering"]
            binary = data["binary"]
            movement = data["movement"]
            cat = data["categorical_target"]
            cross = data["cross_head"]
            return cls(
                mode=str(data["mode"]),
                samples_per_probe=int(data["samples_per_probe"]),
                stratified=bool(data["stratified"]),
                consecutive_violations_for_rollback=int(data["consecutive_violations_for_rollback"]),
                collapse_immediate=bool(data["collapse_immediate"]),
                sat_limit=float(steering["sat_limit"]),
                jitter_ratio_vs_bc=float(steering["jitter_ratio_vs_bc"]),
                spread_floor=float(steering["spread_floor"]),
                yaw_gain_bias_limit=float(steering["yaw_gain_bias_limit"]),
                pitch_gain_bias_limit=float(steering["pitch_gain_bias_limit"]),
                yaw_gain_bias_delta_limit=float(steering["yaw_gain_bias_delta_limit"]),
                pitch_gain_bias_delta_limit=float(steering["pitch_gain_bias_delta_limit"]),
                rate_floor=float(binary["rate_floor"]),
                rate_ceiling=float(binary["rate_ceiling"]),
                both_dims_collapse_floor=float(binary["both_dims_collapse_floor"]),
                idle_rate_ceiling=float(movement["idle_rate_ceiling"]),
                jump_rate_ceiling=float(movement["jump_rate_ceiling"]),
                duck_rate_ceiling=float(movement["duck_rate_ceiling"]),
                target_entropy_floor_3plus_enemies=float(cat["entropy_floor_3plus_enemies"]),
                target_concentration_ceiling=float(cat["concentration_ceiling"]),
                target_yaw_consistency_floor=float(cross["target_yaw_consistency_floor"]),
                fire_aim_alignment_floor=float(cross["fire_aim_alignment_floor"]),
            )
        except KeyError as e:
            raise ValueError(f"JointProbeConfig: missing required key {e}") from e


@dataclass(frozen=True)
class JointSACBundle:
    """Everything ``run_sac`` needs in one container. Keeps the entrypoint
    surface small and avoids passing five sibling objects around."""
    sac_cfg: SACConfig
    probe_cfg: JointProbeConfig
    dual_kpi_cfg: DualKPIDeltaGateConfig


def load_joint_sac_config() -> JointSACBundle:
    """Read every rl_pawn/*.json file relevant to SAC and assemble
    the (sac_cfg, probe_cfg, dual_kpi_cfg) bundle.

    Strict-load via PropertyReader (CLAUDE.md no-fallbacks). ``UT99_DEVICE_OVERRIDE``
    env var overrides ``model.json:device`` (matches BC trainer behaviour) so
    the smoke test can force CPU on a CUDA-less machine.
    """
    # Pre-apply UT99_DEVICE_OVERRIDE op SACConfig.device via load_config's
    # interne _resolve_device. load_config leest model.device direct; we
    # zetten dezelfde override via env-var-precedence binnen load_config
    # niet — die kent UT99_DEVICE_OVERRIDE niet. We patchen post-load.
    sac_cfg = load_config(JOINT_MODEL_KEY)
    device_override = os.environ.get("UT99_DEVICE_OVERRIDE")
    if device_override:
        from dataclasses import replace
        import torch
        sac_cfg = replace(sac_cfg, device=torch.device(device_override))

    probe_data = PropertyReader.get_joint_probe_config(JOINT_MODEL_KEY)
    probe_cfg = JointProbeConfig.from_dict(probe_data)

    export_gate_data = PropertyReader.get_export_gate_config(JOINT_MODEL_KEY)
    dual_kpi_cfg = DualKPIDeltaGateConfig.from_dict(export_gate_data)

    return JointSACBundle(
        sac_cfg=sac_cfg,
        probe_cfg=probe_cfg,
        dual_kpi_cfg=dual_kpi_cfg,
    )
