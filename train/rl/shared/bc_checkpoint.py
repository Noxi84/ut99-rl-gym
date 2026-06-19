"""Checkpoint save/load for BC training."""
from __future__ import annotations

import logging
from pathlib import Path

import torch

from train.common.TrainerLogger import log_print
from train.model.bc_sequence_network import BCSequenceNetwork, load_compatible_state_dict
from train.rl.shared.sac_core.checkpoint_io import strip_compile_prefix


def save_checkpoint(model: BCSequenceNetwork, optimizer, global_step: int, path: Path) -> None:
    torch.save({
        "model_state_dict": strip_compile_prefix(model.state_dict()),
        "optimizer_state_dict": optimizer.state_dict(),
        "global_step": int(global_step),
    }, str(path))


def load_checkpoint(model: BCSequenceNetwork, optimizer, pt_path: Path,
                    device: torch.device, logger: logging.Logger) -> int:
    """Load checkpoint if it exists. Returns the global_step."""
    if not pt_path.exists():
        return 0

    ckpt = torch.load(pt_path, map_location=device, weights_only=False)
    ckpt_model_state = ckpt["model_state_dict"]
    current_state = model.state_dict()
    shape_mismatches = [
        key for key, value in ckpt_model_state.items()
        if key in current_state and current_state[key].shape != value.shape
    ]

    missing, skipped = load_compatible_state_dict(model, ckpt_model_state)
    load_optimizer = ("optimizer_state_dict" in ckpt) and not shape_mismatches and not skipped and not missing
    if load_optimizer:
        try:
            optimizer.load_state_dict(ckpt["optimizer_state_dict"])
        except Exception as exc:
            load_optimizer = False
            log_print(logger, f"Skipped optimizer state from checkpoint: {exc}")
    elif "optimizer_state_dict" in ckpt:
        log_print(logger, "Skipped optimizer state from checkpoint due to incompatible model tensors")
    global_step = ckpt.get("global_step", 0)
    log_print(logger, f"Resumed BC from checkpoint at step {global_step}")
    if skipped:
        log_print(logger, f"Checkpoint load skipped incompatible tensors: {sorted(skipped)[:8]}")
    if missing:
        log_print(logger, f"Checkpoint load missing current tensors: {sorted(missing)[:8]}")
    if shape_mismatches:
        log_print(logger, f"Checkpoint load detected shape changes: {sorted(shape_mismatches)[:8]}")
    return global_step


