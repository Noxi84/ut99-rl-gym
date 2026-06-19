"""Joint VR+shooting CSV data loading for BC training.

Layout for ``y`` per sample:
    cfg.target_features (10 full-joint action columns) followed by
    [target_index, target_index_confidence].

The shared ``MmapBCDataset`` is used with ``mask_mode="all"`` — joint trainer
expects every frame to carry meaningful labels (continuous yaw/pitch + binary
fire/altFire + soft target_index attribution with confidence=0 as the
implicit mask for samples without enemies).
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from train.common.TrainerLogger import log_print
from train.rl.shared.bc_cache import (
    cache_path_for, cleanup_stale_caches, compute_cache_key,
)
from train.rl.shared.bc_config import BCConfig
from train.rl.shared.bc_data_loading import (
    MmapBCDataset, build_col_indices, extract_features, filter_raw_rows,
    find_csv_files, get_col_index, load_or_build_cache, read_csv_data,
)


_FIRE_TARGET_NAMES = ("bFire", "bAltFire")


def _load_joint_csv(csv_path: str, cfg: BCConfig, logger: logging.Logger
                     ) -> tuple[np.ndarray, np.ndarray]:
    """Read one CSV file and produce (X, y).

    ``y`` shape: ``[N, output_size + n_aux]`` (10 main + 2 aux). Missing aux
    columns (older CSVs) fill with zeros — target_index_confidence=0 is the
    documented mask sentinel and propagates correctly through the joint loss.
    """
    header, data = read_csv_data(csv_path)
    col_indices = build_col_indices(header)
    data = filter_raw_rows(data, col_indices, cfg, logger, Path(csv_path).name)
    if len(data) == 0:
        raise ValueError(f"No rows left after raw filtering in {csv_path}")

    X = extract_features(data, col_indices, cfg)

    n_samples = len(data)
    n_aux = len(cfg.aux_target_features)
    y = np.zeros((n_samples, cfg.output_size + n_aux), dtype=np.float32)

    for ti, target in enumerate(cfg.target_features):
        idx = get_col_index(col_indices, target, prefer="last")
        if idx is not None:
            y[:, ti] = data[:, idx]

    for ai, aux_name in enumerate(cfg.aux_target_features):
        idx = get_col_index(col_indices, aux_name, prefer="last")
        if idx is not None:
            y[:, cfg.output_size + ai] = data[:, idx]

    return X, y


def load_data(cfg: BCConfig, csv_dir: Path, logger: logging.Logger
              ) -> MmapBCDataset | None:
    """Load joint CSV training data. Returns ``None`` when no CSVs exist."""
    csv_files = find_csv_files(csv_dir)
    if not csv_files:
        log_print(logger, "No CSV training data found for joint VR+shooting. "
                  "Cannot run BC pre-training.")
        return None

    cache_key = compute_cache_key(csv_files, cfg)
    cp = cache_path_for(csv_dir, cache_key)

    parts = load_or_build_cache(
        csv_dir=csv_dir,
        csv_files=csv_files,
        cache_dir=cp,
        cfg=cfg,
        logger=logger,
        per_csv_loader=_load_joint_csv,
    )
    if parts is None:
        return None
    X_parts, y_parts, oversamples = parts
    cleanup_stale_caches(csv_dir, cp, logger)

    return MmapBCDataset(X_parts, y_parts, oversamples, mask_mode="all")


def compute_pos_weight(dataset: MmapBCDataset, cfg: BCConfig,
                        logger: logging.Logger) -> list[float]:
    """Compute ``[pos_weight_fire, pos_weight_altFire]`` from the dataset.

    Slices only the fire columns of the full-joint ``y`` tensor.
    """
    if not dataset.y_parts:
        return [1.0, 1.0]

    fire_dim = cfg.target_features.index("bFire")
    alt_dim = cfg.target_features.index("bAltFire")
    n_pos = np.zeros(2, dtype=np.int64)
    n_neg = np.zeros(2, dtype=np.int64)
    for y_active, os_ in dataset.iter_active_parts():
        if len(y_active) == 0:
            continue
        y_fire = y_active[:, [fire_dim, alt_dim]]
        n_pos += (y_fire > 0.5).sum(axis=0).astype(np.int64) * os_
        n_neg += (y_fire <= 0.5).sum(axis=0).astype(np.int64) * os_

    weights: list[float] = []
    for i, name in enumerate(_FIRE_TARGET_NAMES):
        total = int(n_pos[i] + n_neg[i])
        pct = 100 * n_pos[i] / max(total, 1)
        log_print(logger, f"  {name}=1: {int(n_pos[i])} ({pct:.1f}%), =0: {int(n_neg[i])}")
        if n_pos[i] == 0:
            log_print(logger, f"  WARNING: zero positive {name} samples — weight=1.0")
            weights.append(1.0)
        else:
            w = float(n_neg[i]) / float(n_pos[i])
            log_print(logger, f"  {name} pos_weight: {w:.2f}")
            weights.append(w)
    return weights
