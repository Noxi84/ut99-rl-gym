"""Shared training loop for BC pre-training."""
from __future__ import annotations

import logging
import math
import signal
import sys
import time
import torch
from pathlib import Path
from torch import optim
from torch.nn.utils import clip_grad_norm_
from torch.utils.data import DataLoader
from typing import Callable

from train.common.ModelRoles import resolve_model_key, PAWN_POLICY
from train.common.Promotion import register_candidate, evaluate_and_promote
from train.common.TrainerLogger import log_print
from train.model.bc_sequence_network import BCSequenceNetwork, load_compatible_state_dict
from train.rl.shared.bc_checkpoint import save_checkpoint, load_checkpoint
from train.rl.shared.bc_config import BCConfig

ComputeLossFn = Callable[..., torch.Tensor]  # (logits, y, mask, cfg, weight, *, target_logits=None) -> loss
ValidateFn = Callable[[BCSequenceNetwork, DataLoader, torch.device, bool, BCConfig, torch.Tensor], tuple[float, float]]

# Exit code used when BC training is interrupted by SIGTERM for migration.
# train-bc.sh recognizes this code and treats it as "migrated away" (not a
# failure, not a completion) so the orchestrator-side steal loop can pick up
# where this trainer left off.
MIGRATION_EXIT_CODE = 42

_stop_requested = False


def _handle_sigterm(signum, frame):
    global _stop_requested
    _stop_requested = True


# Register at module import (before data loading starts in the trainer entry
# points), so SIGTERM is caught even if the orchestrator fires during the
# multi-minute CSV parse / cache build phase. The training loop below checks
# _stop_requested at iteration boundaries; if the flag is already set before
# the first iteration (trainer interrupted during load), we still save a
# zero-step checkpoint and exit 42.
signal.signal(signal.SIGTERM, _handle_sigterm)


def run_training(
    cfg: BCConfig,
    model: BCSequenceNetwork,
    optimizer: optim.Optimizer,
    train_loader: DataLoader,
    val_loader: DataLoader,
    weight_tensor: torch.Tensor,
    compute_loss: ComputeLossFn,
    validate_fn: ValidateFn,
    acc_name: str,
    pt_path: Path,
    onnx_path: str,
    logger: logging.Logger,
) -> None:
    best_pt_path = pt_path.parent / f"{cfg.model_key}_best.pt"
    global_step = load_checkpoint(model, optimizer, pt_path, cfg.device, logger)
    start_step = global_step

    def lr_lambda(step):
        abs_step = start_step + step
        if abs_step < cfg.warmup_steps:
            return max(abs_step / max(cfg.warmup_steps, 1), 0.01)
        progress = (abs_step - cfg.warmup_steps) / max(cfg.max_steps - cfg.warmup_steps, 1)
        return max(0.5 * (1.0 + math.cos(math.pi * progress)), 0.01)

    scheduler = optim.lr_scheduler.LambdaLR(optimizer, lr_lambda)

    best_val_loss = float("inf")
    best_step = 0
    no_improve_count = 0
    stop_training = False

    model.train()
    if cfg.device.type == "cuda":
        torch.backends.cudnn.benchmark = True
    use_amp = cfg.device.type == "cuda"
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)
    t0 = time.time()

    while global_step < cfg.max_steps and not stop_training and not _stop_requested:
        for batch in train_loader:
            if global_step >= cfg.max_steps or stop_training or _stop_requested:
                break

            x_b, y_b, mask_b = [t.to(cfg.device, non_blocking=True) for t in batch]

            with torch.amp.autocast("cuda", enabled=use_amp):
                # Phase 2: when target_head is present, also produce target_logits
                # for the aux CE loss (consumed by shooting compute_loss). Other
                # tasks ignore the target_logits argument.
                if getattr(model, "target_head", None) is not None:
                    logits, target_logits = model.forward_with_target(x_b)
                else:
                    logits = model(x_b)
                    target_logits = None
                loss = compute_loss(logits, y_b, mask_b, cfg, weight_tensor,
                                    target_logits=target_logits)

            optimizer.zero_grad()
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            clip_grad_norm_(model.parameters(), cfg.grad_clip_norm)
            scaler.step(optimizer)
            scaler.update()

            global_step += 1
            scheduler.step()

            if global_step % cfg.log_every_steps == 0:
                elapsed = time.time() - t0
                sps = global_step / max(elapsed, 1)
                lr_now = optimizer.param_groups[0]["lr"]
                log_print(logger, f"step={global_step} loss={loss.item():.4f} "
                          f"lr={lr_now:.6f} sps={sps:.0f}")

            if global_step % cfg.save_every_steps == 0:
                val_loss, head_acc = validate_fn(
                    model, val_loader, cfg.device, use_amp, cfg, weight_tensor,
                )
                log_print(logger, f"  val loss={val_loss:.4f} {acc_name}={head_acc:.1%}")

                save_checkpoint(model, optimizer, global_step, pt_path)

                if val_loss < best_val_loss:
                    best_val_loss = val_loss
                    best_step = global_step
                    no_improve_count = 0
                    save_checkpoint(model, optimizer, global_step, best_pt_path)
                    _promote_and_sync(model, onnx_path, cfg, logger, global_step, val_loss,
                                      f"  NEW BEST val_loss={val_loss:.4f} at step {global_step}")
                else:
                    no_improve_count += 1
                    log_print(logger, f"  no improvement x{no_improve_count} "
                              f"(best={best_val_loss:.4f} at step {best_step})")

                if no_improve_count >= cfg.early_stop_patience:
                    log_print(logger, f"  EARLY STOP at step {global_step}: "
                              f"no improvement for {cfg.early_stop_patience} validations")
                    stop_training = True
                    break

                model.train()

            if stop_training or _stop_requested:
                break

    if _stop_requested:
        # Migration path: save current state and exit with a distinct code so
        # the orchestrator knows this wasn't a normal completion. The RUNNING
        # checkpoint (not _best) is what resumes on the new machine.
        save_checkpoint(model, optimizer, global_step, pt_path)
        log_print(logger, f"SIGTERM received at step={global_step} — saved checkpoint, "
                  f"exiting with code {MIGRATION_EXIT_CODE} for migration")
        sys.exit(MIGRATION_EXIT_CODE)

    log_print(logger, f"BC training complete. Final step={global_step}, "
              f"best_val_loss={best_val_loss:.4f} at step {best_step}")

    if best_pt_path.exists():
        best_ckpt = torch.load(best_pt_path, map_location=cfg.device, weights_only=False)
        best_step = best_ckpt.get("global_step", 0)
        load_compatible_state_dict(model, best_ckpt["model_state_dict"])
        _promote_and_sync(model, onnx_path, cfg, logger, best_step, best_val_loss,
                          f"Final BEST model from step {best_step} val_loss={best_val_loss:.4f}")
    else:
        _promote_and_sync(model, onnx_path, cfg, logger, global_step, best_val_loss,
                          f"No best checkpoint, final model from step {global_step}")


def _infer_role(model_key: str) -> str:
    """Infer the model role from the model key via role bindings."""
    if resolve_model_key(PAWN_POLICY) == model_key:
        return PAWN_POLICY
    return "unknown"


def _promote_and_sync(model: BCSequenceNetwork, onnx_path: str, cfg: BCConfig,
                      logger: logging.Logger, step: int, val_loss: float, msg: str) -> bool:
    """Register candidate, run gates, export and sync only if promoted.
    Returns True if promoted."""
    from train.model.bc_sequence_network import export_actor_onnx
    export_actor_onnx(model, onnx_path, cfg.seq_len, cfg.input_size, cfg.device)

    role = _infer_role(cfg.model_key)
    candidate = register_candidate(cfg.model_key, role, step, val_loss)
    promoted = evaluate_and_promote(candidate, onnx_path)

    if promoted:
        from train.common.ModelSync import sync_onnx_to_servers, sync_pt_to_sac_trainers
        sync_onnx_to_servers(onnx_path)
        pt_path = Path(onnx_path).with_suffix(".pt")
        best_pt_path = pt_path.parent / f"{cfg.model_key}_best.pt"
        for p in [pt_path, best_pt_path]:
            if p.exists():
                sync_pt_to_sac_trainers(str(p))
        log_print(logger, f"{msg} -> PROMOTED & synced (ONNX + PT)")
    else:
        log_print(logger, f"{msg} -> REJECTED (not synced)")
    return promoted
