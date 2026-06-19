"""Joint VR+shooting BC config loader.

``rl_pawn`` is geregistreerd in ``resources/models/index.json``;
:func:`train.rl.shared.bc_config.load_config('rl_pawn')` bouwt
rechtstreeks een :class:`BCConfig` uit ``resources/models/rl_pawn/``.

Deze module is een dunne wrapper die naast de gedeelde ``BCConfig`` ook de
joint-specifieke ``JointMultiHeadLossCfg`` uit het ``multi_head_loss`` blok
van ``bc.json`` leest.
"""
from __future__ import annotations

from dataclasses import dataclass

from train.common import PropertyReader
from train.rl.shared.bc_config import BCConfig, load_config

JOINT_MODEL_KEY = "rl_pawn"


@dataclass(frozen=True)
class JointMultiHeadLossCfg:
    """Per-head loss weights from rl_pawn/bc.json multi_head_loss block."""
    w_movement: float
    w_vr: float
    w_fire: float
    w_target: float


def load_joint_bc_config() -> tuple[BCConfig, JointMultiHeadLossCfg]:
    """Read every rl_pawn/*.json file via PropertyReader and produce a ``BCConfig``.

    Reuses :func:`train.rl.shared.bc_config.load_config` for the standard
    ``BCConfig`` fields (model, bc, features, data_loader). Joint-specific
    ``multi_head_loss`` block is read separately because the shared dataclass
    has no slot for it.
    """
    cfg = load_config(JOINT_MODEL_KEY)
    if cfg.task_kind != "joint_pawn":
        raise ValueError(
            f"Joint target_features resolved to task_kind={cfg.task_kind!r}; "
            f"expected 'joint_pawn'. Update _infer_task_kind in "
            f"train/rl/shared/bc_config.py."
        )

    bc_cfg_raw = PropertyReader.get_bc_config(JOINT_MODEL_KEY)
    loss_block = bc_cfg_raw["multi_head_loss"]
    loss_cfg = JointMultiHeadLossCfg(
        w_movement=float(loss_block.get("w_movement", 1.0)),
        w_vr=float(loss_block["w_vr"]),
        w_fire=float(loss_block["w_fire"]),
        w_target=float(loss_block["w_target"]),
    )
    return cfg, loss_cfg
