"""Joint VR+shooting BC validation — per-head loss breakdown + fire accuracy.

Validates the model on the held-out val set and returns ``(val_loss, fire_acc)``.
Per-head val-loss components (``val_loss_yaw_pitch``, ``val_loss_fire``,
``val_loss_target``) are accumulated and logged separately via the trainer's
logger so the BC fase status-criterium (research-doc §8.4) can be evaluated
against decoupled VR + shooting baselines.

The accuracy metric is ``bFire`` accuracy on masked samples — matches the
existing shooting BC convention so cross-trainer comparison stays direct.
"""
from __future__ import annotations

import logging

import torch
from torch.utils.data import DataLoader

from train.common.TrainerLogger import log_print
from train.rl.shared.bc_config import BCConfig
from train.rl.rl_pawn.trainBC.loss import compute_joint_bc_loss


@torch.no_grad()
def validate_with_breakdown(
    model,
    val_loader: DataLoader,
    device: torch.device,
    use_amp: bool,
    cfg: BCConfig,
    pos_weight_tensor: torch.Tensor,
    *,
    log_std: torch.Tensor,
    w_vr: float,
    w_fire: float,
    w_target: float,
    w_movement: float = 1.0,
    logger: logging.Logger | None = None,
) -> tuple[float, float, dict[str, float]]:
    """Run full val pass with per-head loss accumulation.

    Returns ``(total_val_loss, fire_acc, per_head_dict)`` where per_head_dict
    contains the unweighted mean per-head losses for status-criterium checks.
    """
    model.eval()
    total_loss = 0.0
    sum_movement, sum_yp, sum_fire, sum_altfire, sum_target = 0.0, 0.0, 0.0, 0.0, 0.0
    n_batches = 0
    correct, total_items = 0, 0

    for batch in val_loader:
        x_b, y_b, mask_b = [t.to(device, non_blocking=True) for t in batch]

        with torch.amp.autocast("cuda", enabled=use_amp):
            if getattr(model, "target_head", None) is not None:
                logits, target_logits = model.forward_with_target(x_b)
            else:
                logits = model(x_b)
                target_logits = None
            loss, breakdown = compute_joint_bc_loss(
                logits, y_b, mask_b, cfg, pos_weight_tensor,
                target_logits=target_logits,
                log_std=log_std,
                w_movement=w_movement,
                w_vr=w_vr, w_fire=w_fire, w_target=w_target,
                return_breakdown=True,
            )

        total_loss += loss.item()
        sum_movement += float(breakdown["loss_movement"])
        sum_yp += float(breakdown["loss_yaw_pitch"])
        sum_fire += float(breakdown["loss_fire"])
        sum_altfire += float(breakdown["loss_altFire"])
        sum_target += float(breakdown["loss_target"])
        n_batches += 1

        mask_bool = mask_b > 0.5
        if mask_bool.any():
            fire_idx = cfg.target_features.index("bFire")
            fire_pred = (torch.sigmoid(logits[:, fire_idx]) > 0.5).long()
            fire_target = (y_b[:, fire_idx] > 0.5).long()
            correct += (fire_pred[mask_bool] == fire_target[mask_bool]).sum().item()
            total_items += int(mask_bool.sum().item())

    n = max(n_batches, 1)
    per_head = {
        "val_loss_movement": sum_movement / n,
        "val_loss_yaw_pitch": sum_yp / n,
        "val_loss_fire": sum_fire / n,
        "val_loss_altFire": sum_altfire / n,
        "val_loss_target": sum_target / n,
    }
    fire_acc = correct / max(total_items, 1)
    return total_loss / n, fire_acc, per_head


def validate(
    model,
    val_loader: DataLoader,
    device: torch.device,
    use_amp: bool,
    cfg: BCConfig,
    pos_weight_tensor: torch.Tensor,
) -> tuple[float, float]:
    """Two-value contract for ``bc_training_loop.run_training``.

    The shared loop's ``ValidateFn`` returns ``(loss, head_acc)``. Per-head
    breakdown logging happens inside trainer.py via the richer
    ``validate_with_breakdown`` path; this wrapper preserves the contract.
    Reads ``log_std``, ``w_vr``, ``w_fire``, ``w_target`` from attributes
    that ``trainer.py`` attaches on the model object (``model._joint_log_std``,
    ``model._joint_w_vr`` etc.) — closure-style state-passing without
    touching the shared loop's signature.
    """
    log_std = getattr(model, "_joint_log_std", None)
    if log_std is None:
        raise RuntimeError(
            "joint validate(): model missing `_joint_log_std`; "
            "trainer.py must attach it before calling run_training"
        )
    w_vr = getattr(model, "_joint_w_vr")
    w_movement = getattr(model, "_joint_w_movement", 1.0)
    w_fire = getattr(model, "_joint_w_fire")
    w_target = getattr(model, "_joint_w_target")

    total, acc, per_head = validate_with_breakdown(
        model, val_loader, device, use_amp, cfg, pos_weight_tensor,
        log_std=log_std, w_movement=w_movement,
        w_vr=w_vr, w_fire=w_fire, w_target=w_target,
    )
    logger = getattr(model, "_joint_logger", None)
    if logger is not None:
        log_print(
            logger,
            "  val heads "
            f"movement={per_head['val_loss_movement']:.4f} "
            f"yaw_pitch={per_head['val_loss_yaw_pitch']:.4f} "
            f"fire={per_head['val_loss_fire']:.4f} "
            f"altFire={per_head['val_loss_altFire']:.4f} "
            f"target={per_head['val_loss_target']:.4f}",
        )
    return total, acc
