"""Pure SAC checkpoint I/O.

Save helpers only. Loading is per-model because the fallback ladder
(SAC-best → SAC-current → BC) and the compatibility shim for
missing/extra state dict keys can legitimately differ per task.
"""
from __future__ import annotations

import shutil
from pathlib import Path

import torch


_COMPILE_PREFIX = "_orig_mod."


def strip_compile_prefix(state_dict: dict) -> dict:
    """Return a state_dict without the ``_orig_mod.`` prefix that
    ``torch.compile`` adds to every key of an ``OptimizedModule``. Canonical
    checkpoints save the unwrapped keys so they reload into either a
    compiled or uncompiled model — bootstrap unconditionally builds the raw
    nn.Module before compile, and only wraps after weight load."""
    if not any(k.startswith(_COMPILE_PREFIX) for k in state_dict.keys()):
        return state_dict
    return {
        (k[len(_COMPILE_PREFIX):] if k.startswith(_COMPILE_PREFIX) else k): v
        for k, v in state_dict.items()
    }


def save_training_state(
    pt_path, metadata_path,
    actor, q1, q2, target_q1, target_q2, temperature,
    critic_optimizer, actor_optimizer, temp_optimizer,
    global_step: int,
    best_exported_return: float,
    best_observed_return: float,
    baseline_return,
    log_std_param: torch.Tensor,
    last_export_step: int,
    extra_metadata: dict | None = None,
) -> None:
    """Atomic-ish save of actor .pt + sidecar SAC metadata."""
    torch.save(
        {"model_state_dict": strip_compile_prefix(actor.state_dict()),
         "global_step": global_step},
        str(pt_path),
    )
    save_metadata(
        metadata_path, q1, q2, target_q1, target_q2, temperature,
        critic_optimizer, actor_optimizer, temp_optimizer, global_step,
        best_exported_return, best_observed_return, baseline_return,
        log_std_param, last_export_step, extra_metadata=extra_metadata,
    )


def save_metadata(
    path,
    q1, q2, target_q1, target_q2, temperature,
    critic_optimizer, actor_optimizer, temp_optimizer,
    global_step: int,
    best_exported_return: float,
    best_observed_return: float,
    baseline_return,
    log_std_param: torch.Tensor,
    last_export_step: int,
    extra_metadata: dict | None = None,
) -> None:
    payload = {
        "q1_state": strip_compile_prefix(q1.state_dict()),
        "q2_state": strip_compile_prefix(q2.state_dict()),
        "target_q1_state": strip_compile_prefix(target_q1.state_dict()),
        "target_q2_state": strip_compile_prefix(target_q2.state_dict()),
        "temperature_state": temperature.state_dict(),
        "critic_optimizer_state": critic_optimizer.state_dict(),
        "actor_optimizer_state": actor_optimizer.state_dict(),
        "temp_optimizer_state": temp_optimizer.state_dict(),
        "log_std_param": log_std_param.data,
        "global_step": global_step,
        "best_mean_return": best_exported_return,
        "best_observed_return": best_observed_return,
        "baseline_return": baseline_return,
        "last_export_step": last_export_step,
    }
    if extra_metadata:
        payload.update(extra_metadata)
    torch.save(payload, str(path))


def copy_onnx_with_data(src_onnx: str | Path, dst_onnx: str | Path) -> None:
    """Copy an ONNX file and its external data sidecar when present."""
    src = Path(src_onnx)
    dst = Path(dst_onnx)
    shutil.copy2(str(src), str(dst))
    src_data = Path(str(src) + ".data")
    dst_data = Path(str(dst) + ".data")
    if src_data.exists():
        shutil.copy2(str(src_data), str(dst_data))
    elif dst_data.exists():
        dst_data.unlink()
