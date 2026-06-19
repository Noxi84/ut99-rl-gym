"""Joint VR+shooting BC loss — multi-head composition (research-doc §8.3).

Loss components for the 10-dim action vector + auxiliary target_index head:

  loss_movement   : Smooth-L1 direction + BCE movement binaries on dims [0:6].
  loss_yaw_pitch  : Gaussian NLL pre-tanh on dims [6:8].
  loss_fire       : BCE-with-logits on dim 8 (bFire), pos_weight per-batch.
  loss_altFire    : BCE-with-logits on dim 9 (bAltFire), pos_weight per-batch.
  loss_target     : Confidence-weighted CE on target_logits[B, 5] vs
                    target_index (categorical 0..4), gewogen door
                    target_index_confidence ∈ [0, 1].

Total = w_vr * loss_yaw_pitch + w_fire * (loss_fire + loss_altFire)
      + w_target * loss_target

CSV column layout is cfg.target_features followed by aux_target_features.
"""
from __future__ import annotations

import torch
import torch.nn.functional as F

from train.rl.shared.bc_config import BCConfig


_MOVE_DIR_DIMS = (0, 1)
_DODGE_DIM = 2
_JUMP_DIM = 3
_DUCK_DIM = 4
_IDLE_DIM = 5
_YAW_PITCH_DIMS = (6, 7)
_FIRE_DIM = 8
_ALTFIRE_DIM = 9

_DODGE_LOSS_WEIGHT = 5.0
_JUMP_LOSS_WEIGHT = 3.0
_DUCK_LOSS_WEIGHT = 2.0
_IDLE_LOSS_WEIGHT = 2.0
_DODGE_POS_WEIGHT = 30.0
_JUMP_POS_WEIGHT = 20.0
_DUCK_POS_WEIGHT = 10.0
_IDLE_POS_WEIGHT = 8.0


def compute_joint_bc_loss(
    logits: torch.Tensor,
    y: torch.Tensor,
    mask: torch.Tensor,
    cfg: BCConfig,
    pos_weight_tensor: torch.Tensor,
    *,
    target_logits: torch.Tensor | None,
    log_std: torch.Tensor,
    w_vr: float,
    w_fire: float,
    w_target: float,
    w_movement: float = 1.0,
    return_breakdown: bool = False,
):
    """Return scalar loss; optionally also a per-head breakdown dict for logging.

    Signature is compatible with ``bc_training_loop.run_training``'s
    ``compute_loss(logits, y, mask, cfg, weight_tensor, *, target_logits=None)``
    contract. Extra args (``log_std``, ``w_vr``, ``w_fire``, ``w_target``) are
    supplied by ``trainer.py`` via a closure so the shared loop stays generic
    (no ``if model_key == ...`` branches — per CLAUDE.md working preferences).

    ``pos_weight_tensor`` shape: ``[2]`` — ``[pos_weight_fire, pos_weight_altFire]``.
    """
    n_main = cfg.output_size  # 10 for full-joint
    if n_main != 10:
        raise ValueError(
            f"compute_joint_bc_loss expects output_size=10 (movement+yaw/pitch/fire/altFire), got {n_main}"
        )
    if pos_weight_tensor.dim() == 0 or pos_weight_tensor.shape[0] < 2:
        raise ValueError(
            f"pos_weight_tensor must be shape [2] for [fire, altFire]; got "
            f"shape={tuple(pos_weight_tensor.shape)}"
        )

    # ---- 1. Movement loss (dims 0:6) ----
    dir_pred = torch.tanh(logits[:, list(_MOVE_DIR_DIMS)])
    dir_target = y[:, list(_MOVE_DIR_DIMS)].clamp(-1.0, 1.0)
    dir_loss = F.smooth_l1_loss(dir_pred, dir_target, reduction="none").sum(dim=-1) * mask
    loss_move_dir = dir_loss.sum() / (mask.sum() + 1e-8)

    def movement_bce(dim: int, pos_weight: float) -> torch.Tensor:
        target = y[:, dim]
        target_smoothed = target * (1.0 - cfg.label_smoothing) + 0.5 * cfg.label_smoothing
        pw = torch.tensor(pos_weight, dtype=logits.dtype, device=logits.device)
        per_sample = F.binary_cross_entropy_with_logits(
            logits[:, dim], target_smoothed, pos_weight=pw, reduction="none",
        ) * mask
        return per_sample.sum() / (mask.sum() + 1e-8)

    loss_dodge = movement_bce(_DODGE_DIM, _DODGE_POS_WEIGHT)
    loss_jump = movement_bce(_JUMP_DIM, _JUMP_POS_WEIGHT)
    loss_duck = movement_bce(_DUCK_DIM, _DUCK_POS_WEIGHT)
    loss_idle = movement_bce(_IDLE_DIM, _IDLE_POS_WEIGHT)
    loss_movement = (
        loss_move_dir
        + _DODGE_LOSS_WEIGHT * loss_dodge
        + _JUMP_LOSS_WEIGHT * loss_jump
        + _DUCK_LOSS_WEIGHT * loss_duck
        + _IDLE_LOSS_WEIGHT * loss_idle
    )

    # ---- 2. Gaussian NLL on yaw/pitch (dims 6:8) ----
    clamped_log_std = log_std.clamp(-5.0, 2.0)  # [output_size]
    yp_mean = logits[:, list(_YAW_PITCH_DIMS)]
    yp_log_std = clamped_log_std[list(_YAW_PITCH_DIMS)]
    yp_target = y[:, list(_YAW_PITCH_DIMS)].clamp(-1.0 + 1e-4, 1.0 - 1e-4)
    yp_u_target = torch.atanh(yp_target)
    yp_inv_var = torch.exp(-2.0 * yp_log_std)
    yp_sq_error = (yp_u_target - yp_mean).pow(2) * yp_inv_var
    yp_nll_per_sample = (0.5 * yp_sq_error + yp_log_std).sum(dim=-1) * mask
    loss_yaw_pitch = yp_nll_per_sample.sum() / (mask.sum() + 1e-8)

    # ---- 3. BCE-with-logits on fire / altFire ----
    fire_logit = logits[:, _FIRE_DIM]
    fire_target = y[:, _FIRE_DIM]
    fire_target_smoothed = fire_target * (1.0 - cfg.label_smoothing) + 0.5 * cfg.label_smoothing
    fire_pw = pos_weight_tensor[0]
    fire_loss_per_sample = F.binary_cross_entropy_with_logits(
        fire_logit, fire_target_smoothed,
        pos_weight=fire_pw, reduction="none",
    ) * mask
    loss_fire = fire_loss_per_sample.sum() / (mask.sum() + 1e-8)

    alt_logit = logits[:, _ALTFIRE_DIM]
    alt_target = y[:, _ALTFIRE_DIM]
    alt_target_smoothed = alt_target * (1.0 - cfg.label_smoothing) + 0.5 * cfg.label_smoothing
    alt_pw = pos_weight_tensor[1]
    alt_loss_per_sample = F.binary_cross_entropy_with_logits(
        alt_logit, alt_target_smoothed,
        pos_weight=alt_pw, reduction="none",
    ) * mask
    loss_altFire = alt_loss_per_sample.sum() / (mask.sum() + 1e-8)

    # ---- 4. Confidence-weighted CE on target_logits ----
    # Only when both the head is present (target_logits provided) and the
    # aux columns are wired through (cfg.aux_target_features non-empty).
    loss_target = torch.zeros((), dtype=loss_fire.dtype, device=loss_fire.device)
    if target_logits is not None and len(cfg.aux_target_features) >= 2:
        target_index_col = cfg.output_size
        target_conf_col = cfg.output_size + 1
        target_idx = (y[:, target_index_col]
                      .long()
                      .clamp(min=0, max=target_logits.shape[-1] - 1))
        confidence = y[:, target_conf_col].clamp(min=0.0, max=1.0)
        # TargetHead emits finfo.min/2 for absent enemy slots so softmax routes
        # mass away. F.cross_entropy on a label that points at a masked slot
        # then yields +inf via log(softmax) = -inf. Clamp the floor to a
        # finite large-negative — confidence=0 still zeros these samples out,
        # but the clamp prevents NaN/Inf from poisoning the gradient before
        # the weighting kicks in.
        safe_logits = target_logits.clamp_min(-1e4)
        ce_per_sample = F.cross_entropy(
            safe_logits, target_idx, reduction="none",
            label_smoothing=cfg.label_smoothing,
        )
        weighted = ce_per_sample * confidence * mask
        denom = (confidence * mask).sum().clamp(min=1.0)
        loss_target = weighted.sum() / denom

    total = (
        w_movement * loss_movement
        + w_vr * loss_yaw_pitch
        + w_fire * (loss_fire + loss_altFire)
        + w_target * loss_target
    )

    if return_breakdown:
        breakdown = {
            "loss_movement": loss_movement.detach(),
            "loss_move_dir": loss_move_dir.detach(),
            "loss_dodge": loss_dodge.detach(),
            "loss_jump": loss_jump.detach(),
            "loss_duck": loss_duck.detach(),
            "loss_idle": loss_idle.detach(),
            "loss_yaw_pitch": loss_yaw_pitch.detach(),
            "loss_fire": loss_fire.detach(),
            "loss_altFire": loss_altFire.detach(),
            "loss_target": loss_target.detach(),
            "mask_active_count": mask.sum().detach(),
        }
        return total, breakdown
    return total
