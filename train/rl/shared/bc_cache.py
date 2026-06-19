"""Preprocessed BC data cache — per-CSV split layout.

Persists (X, y) arrays as one pair of .npy files per source CSV plus a
manifest.json that records the ordering. X is always loaded via mmap at
training time, so the full feature tensor never materialises in RAM — only
the samples pulled by the DataLoader for each batch are paged in.

Cache key hashes the CSV file list (names + content-hash) plus the config
fields that affect the output, so any change — regenerated CSVs, different
features, different filters — forces a rebuild. _CACHE_VERSION also
participates in the key so a layout bump invalidates old dirs.
"""
from __future__ import annotations

import hashlib
import json
import logging
import resource
import shutil
import time
from pathlib import Path
from typing import Any

import numpy as np

from train.common.TrainerLogger import log_print
from train.rl.shared.bc_config import BCConfig

CACHE_PREFIX = "bc_cache_"
MANIFEST_FILENAME = "manifest.json"
HEAD_TAIL_BYTES = 65536

# Bump this whenever the on-disk layout or serialisation semantics change.
# v2: per-CSV X_<stem>.npy / y_<stem>.npy + manifest.json (was single X.npy/y.npy)
# v3: nan_to_num applied at save time so DataLoader hot path can skip it.
_CACHE_VERSION = 3


def _file_content_hash(f: Path) -> str:
    """Hash based on size + head/tail bytes, not mtime.

    Why: prepare-csv.sh republishes CSVs via `cp` which updates mtime even when
    the content is bit-identical (deterministic regeneration). Using mtime would
    force a cache rebuild on every deploy.sh --retrain. Hashing head+tail+size
    survives republication as long as the generator output is deterministic.
    """
    st = f.stat()
    h = hashlib.sha256()
    h.update(f"{st.st_size}".encode())
    with open(f, "rb") as fp:
        h.update(fp.read(HEAD_TAIL_BYTES))
        if st.st_size > HEAD_TAIL_BYTES * 2:
            fp.seek(-HEAD_TAIL_BYTES, 2)
            h.update(fp.read(HEAD_TAIL_BYTES))
    return h.hexdigest()


def _config_fingerprint_parts(cfg: BCConfig) -> list[Any]:
    """Config fields that affect the (X, y) output of load_data."""
    groups = None
    if cfg.feature_groups is not None:
        groups = [(g.features, g.first_frames, g.last_frames) for g in cfg.feature_groups]
    return [
        _CACHE_VERSION,
        cfg.task_kind,
        cfg.input_features,
        cfg.target_features,
        cfg.seq_len,
        groups,
        cfg.recovery_bucket_patterns,
        cfg.recovery_oversample_factor,
    ]


def compute_cache_key(csv_files: list[Path], cfg: BCConfig) -> str:
    h = hashlib.sha256()
    for f in sorted(csv_files):
        h.update(f.name.encode())
        h.update(_file_content_hash(f).encode())
    for part in _config_fingerprint_parts(cfg):
        h.update(repr(part).encode())
    return h.hexdigest()[:16]


def cache_path_for(csv_dir: Path, cache_key: str) -> Path:
    """Return cache directory path in a sibling bc-cache/<model_key>/ dir.

    Cache lives OUTSIDE csv-training-data/ so `deploy.sh --retrain`
    (which wipes csv-training-data via --clean-csv) does not blow it away.
    Content-hash in the cache key ensures we still invalidate correctly when
    CSV content genuinely changes.

    csv_dir layout:   <sessions>/csv-training-data/<model_key>
    cache dir layout: <sessions>/bc-cache/<model_key>/bc_cache_<hash>/
                          manifest.json
                          X_<csv_stem>.npy  (shape [N, T, F], float32, mmap at load)
                          y_<csv_stem>.npy  (shape [N, M],   float32, eager-loaded)
    """
    sessions_dir = csv_dir.parent.parent
    model_key = csv_dir.name
    cache_parent = sessions_dir / "bc-cache" / model_key
    cache_parent.mkdir(parents=True, exist_ok=True)
    return cache_parent / f"{CACHE_PREFIX}{cache_key}"


def _part_paths(cache_dir: Path, stem: str) -> tuple[Path, Path]:
    return cache_dir / f"X_{stem}.npy", cache_dir / f"y_{stem}.npy"


def save_cache_part(cache_dir: Path, stem: str,
                    X: np.ndarray, y: np.ndarray) -> dict[str, Any]:
    """Write one CSV's (X, y) pair to the cache dir. Returns manifest entry.

    Atomic per-file via np.save → rename. Safe to overwrite partial writes
    from a previous aborted run.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    x_path, y_path = _part_paths(cache_dir, stem)
    x_tmp = x_path.with_suffix(x_path.suffix + ".tmp")
    y_tmp = y_path.with_suffix(y_path.suffix + ".tmp")
    # Sanitise once at write time so the DataLoader hot path can skip it.
    # Cache _CACHE_VERSION is bumped (v2 -> v3) to invalidate any pre-v3
    # part files that were written without this guarantee.
    np.nan_to_num(X, copy=False, nan=0.0)
    np.nan_to_num(y, copy=False, nan=0.0)
    # Write via an open file handle so np.save does not auto-append ".npy"
    # to our ".npy.tmp" staging name.
    with open(x_tmp, "wb") as f:
        np.save(f, X, allow_pickle=False)
    with open(y_tmp, "wb") as f:
        np.save(f, y, allow_pickle=False)
    x_tmp.rename(x_path)
    y_tmp.rename(y_path)
    return {
        "stem": stem,
        "n_samples": int(X.shape[0]),
        "x_shape": list(X.shape),
        "y_shape": list(y.shape),
        "x_dtype": str(X.dtype),
        "y_dtype": str(y.dtype),
    }


def write_manifest(cache_dir: Path, parts: list[dict[str, Any]],
                   logger: logging.Logger) -> None:
    """Commit the cache by writing manifest.json last (atomic temp-rename)."""
    manifest = {"version": _CACHE_VERSION, "parts": parts}
    mf_path = cache_dir / MANIFEST_FILENAME
    tmp = mf_path.with_suffix(mf_path.suffix + ".tmp")
    with open(tmp, "w") as f:
        json.dump(manifest, f, indent=2)
    tmp.rename(mf_path)
    size_gb = sum(f.stat().st_size for f in cache_dir.iterdir() if f.is_file()) / (1024 ** 3)
    log_print(logger, f"Cache manifest committed: {cache_dir.name} "
              f"({len(parts)} parts, {size_gb:.2f} GB total on disk)")


def load_manifest(cache_dir: Path) -> list[dict[str, Any]] | None:
    """Load manifest if present and layout is current. Returns parts list or None."""
    mf_path = cache_dir / MANIFEST_FILENAME
    if not mf_path.exists():
        return None
    try:
        with open(mf_path) as f:
            manifest = json.load(f)
    except (OSError, json.JSONDecodeError):
        return None
    if manifest.get("version") != _CACHE_VERSION:
        return None
    parts = manifest.get("parts")
    if not isinstance(parts, list):
        return None
    # Verify every part file still exists with the expected shape; if not, treat
    # cache as invalid so it rebuilds cleanly.
    for entry in parts:
        stem = entry.get("stem")
        if not stem:
            return None
        x_path, y_path = _part_paths(cache_dir, stem)
        if not x_path.exists() or not y_path.exists():
            return None
    return parts


def _ensure_fd_limit_for_parts(n_parts: int, logger: logging.Logger) -> None:
    """Raise RLIMIT_NOFILE soft so we can mmap n_parts X arrays at once.

    Why: each cached CSV part keeps both X_<stem>.npy and y_<stem>.npy mmap'd
    for the entire training run (2 fd's per part). The default Linux soft
    limit (1024) trips 'OSError: [Errno 24] Too many open files' as soon as
    parts > ~500 once DataLoader workers, torch internals, and stdio are also
    accounted for. Sysadmin-set hard limits (1M on the trainers) leave plenty
    of headroom.
    """
    # 2 fd's per part (X + y mmap), plus a comfortable buffer for stdio,
    # torch internals, and DataLoader workers.
    needed = 4 * n_parts + 1024
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft >= needed:
        return
    target = min(needed, hard)
    if target <= soft:
        log_print(logger, f"WARNING: RLIMIT_NOFILE soft={soft} hard={hard} "
                  f"too low for {n_parts} mmap parts (need ~{needed}). "
                  f"Raise hard limit in /etc/security/limits.conf.")
        return
    resource.setrlimit(resource.RLIMIT_NOFILE, (target, hard))
    log_print(logger, f"Raised RLIMIT_NOFILE soft {soft} -> {target} "
              f"(hard={hard}) for {n_parts} mmap parts")


def open_part_arrays(cache_dir: Path, parts: list[dict[str, Any]],
                     logger: logging.Logger
                     ) -> tuple[list[np.ndarray], list[np.ndarray]]:
    """Mmap every X_<stem>.npy and y_<stem>.npy.

    Both X and y stay lazy (page-faulted per sample). Class-weight stats
    iterate over y_parts streaming, never materialising y_all in RAM.
    Returns (X_parts, y_parts) in manifest order.
    """
    _ensure_fd_limit_for_parts(len(parts), logger)
    t0 = time.time()
    X_parts: list[np.ndarray] = []
    y_parts: list[np.ndarray] = []
    for entry in parts:
        stem = entry["stem"]
        x_path, y_path = _part_paths(cache_dir, stem)
        X_parts.append(np.load(x_path, mmap_mode="r"))
        y_parts.append(np.load(y_path, mmap_mode="r"))
    total_samples = sum(int(e["n_samples"]) for e in parts)
    log_print(logger, f"Cache opened: {cache_dir.name} "
              f"({len(parts)} parts, {total_samples} samples, "
              f"X+y mmap'd, {time.time() - t0:.1f}s)")
    return X_parts, y_parts


def cleanup_stale_caches(csv_dir: Path, current_cache_path: Path,
                         logger: logging.Logger) -> None:
    """Remove any other bc_cache_* files/dirs from the cache dir (and any
    legacy cache files left behind in the old csv_dir location)."""
    dirs_to_scan = {current_cache_path.parent, csv_dir}
    for d in dirs_to_scan:
        if not d.exists():
            continue
        for old in d.glob(f"{CACHE_PREFIX}*"):
            if old.resolve() == current_cache_path.resolve():
                continue
            try:
                if old.is_dir():
                    shutil.rmtree(old)
                else:
                    old.unlink()
                log_print(logger, f"Removed stale cache: {old}")
            except OSError as ex:
                log_print(logger, f"Failed to remove stale cache {old}: {ex}")
