"""Shared CSV data loading utilities for BC training."""
from __future__ import annotations

import logging
import multiprocessing as mp
import os
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Callable

import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset, random_split

from train.common.TrainerLogger import log_print
from train.rl.shared.bc_cache import (
    load_manifest, open_part_arrays, save_cache_part, write_manifest,
)
from train.rl.shared.bc_config import BCConfig

# Per-CSV loader signature: (csv_path, cfg, logger) -> (X, y_raw)
PerCsvLoader = Callable[[str, BCConfig, logging.Logger], tuple[np.ndarray, np.ndarray]]

# Per-CSV oversample function: (csv_path, cfg) -> repeat_count
OversampleFn = Callable[[Path, BCConfig], int]

# Cap on concurrent CSV parse workers. The hot loop is CPU-bound (np.loadtxt
# + numpy feature projection); beyond ~16 processes the trainer hosts start
# losing throughput to memory-bandwidth + page-cache contention.
_MAX_PARSE_WORKERS = 16


def build_col_indices(header: list[str]) -> dict[str, list[int]]:
    col_indices: dict[str, list[int]] = {}
    for idx, name in enumerate(header):
        col_indices.setdefault(name, []).append(idx)
    return col_indices


def get_col_index(col_indices: dict[str, list[int]], name: str, prefer: str = "first") -> int | None:
    indices = col_indices.get(name)
    if not indices:
        return None
    return indices[0] if prefer == "first" else indices[-1]



def read_csv_data(csv_path: str) -> tuple[list[str], np.ndarray]:
    """Read a semicolon-delimited CSV file and return (header, data_array).

    Uses ``np.loadtxt`` — ~3-4× faster than the previous ``csv.reader`` +
    Python ``float()`` loop on this project's very wide CSVs (~50k columns
    × ~170 rows). Pandas is *slower* here because its per-column metadata
    bookkeeping scales with column count and dominates on extreme aspect
    ratios. ``np.loadtxt`` has a single C-level parse over the whole body
    and is column-count-insensitive.

    NaN scrubbing happens once at cache-write time
    (``bc_cache._CACHE_VERSION = 3``), so we do not normalise NaN here.
    """
    with open(csv_path, "r") as f:
        header = f.readline().rstrip("\n").split(";")
        data = np.loadtxt(f, delimiter=";", dtype=np.float32)
    if data.size == 0:
        raise ValueError(f"No data rows in {csv_path}")
    if data.ndim == 1:
        # Single-row CSV: np.loadtxt collapses to 1D. Re-expand so downstream
        # code that indexes ``data[:, col]`` still works.
        data = data.reshape(1, -1)
    return header, data


def filter_raw_rows(data: np.ndarray, col_indices: dict[str, list[int]],
                    cfg: BCConfig, logger: logging.Logger, csv_name: str) -> np.ndarray:
    """No-op: filtering is handled upstream in CSV generation. Kept for call-site compatibility."""
    return data


def apply_temporal_mask(X: np.ndarray, cfg: BCConfig) -> np.ndarray:
    """
    Apply gap-based temporal masking to X of shape (n_samples, seq_len, n_features).
    For each group: frames in [first_frames, total_window - last_frames) are zeroed.
    Only applies when cfg.feature_groups is not None.
    """
    if cfg.feature_groups is None:
        return X
    total_window = cfg.total_window
    offset = 0
    for group in cfg.feature_groups:
        n_group = len(group.features)
        gap_start = group.first_frames
        gap_end = total_window - group.last_frames
        if gap_start < gap_end:
            X[:, gap_start:gap_end, offset:offset + n_group] = 0.0
        offset += n_group
    return X


def extract_features(data: np.ndarray, col_indices: dict[str, list[int]],
                     cfg: BCConfig) -> np.ndarray:
    """Extract feature matrix X with shape (n_samples, seq_len, n_features)."""
    n_features = len(cfg.input_features)
    n_samples = len(data)
    X = np.zeros((n_samples, cfg.seq_len, n_features), dtype=np.float32)

    for fi, feat in enumerate(cfg.input_features):
        for t in range(cfg.seq_len):
            suffix = "" if t == cfg.seq_len - 1 else f"_F{t + 1}"
            col_name = feat + suffix
            prefer = "first" if not suffix else "last"
            idx = get_col_index(col_indices, col_name, prefer=prefer)
            if idx is not None:
                X[:, t, fi] = data[:, idx]

    # Apply temporal masking when feature groups are configured
    X = apply_temporal_mask(X, cfg)

    return X


def find_csv_files(data_dir: Path) -> list[Path]:
    parts = sorted(data_dir.glob("data_part*.csv"))
    if parts:
        return parts
    single = data_dir / "data.csv"
    if single.exists():
        return [single]
    return []


class MmapBCDataset(Dataset):
    """BC dataset backed by memory-mapped per-CSV X and y arrays.

    Both X and y parts stay on disk (mmap_mode='r'); only the samples pulled
    by the DataLoader for each batch get paged into RAM. The mask is computed
    on-the-fly per sample from y, controlled by mask_mode:
      - "all":     mask is always 1.0 (every sample is active)
      - "nonzero": mask = 1.0 iff any |y[i]| > 0 in the row
                   (used for class-conditional targets where empty labels
                   indicate the row should be skipped during loss).

    Oversampling is handled by index-mapping, so a recovery bucket replicated
    Nx shows up Nx in __len__ and batch sampling without any disk duplication.
    """

    _MASK_MODES = ("all", "nonzero")

    def __init__(self,
                 X_parts: list[np.ndarray],
                 y_parts: list[np.ndarray],
                 oversamples: list[int],
                 mask_mode: str) -> None:
        if len(X_parts) != len(y_parts):
            raise ValueError(f"X_parts/y_parts length mismatch: "
                             f"{len(X_parts)} vs {len(y_parts)}")
        if len(X_parts) != len(oversamples):
            raise ValueError(f"X_parts/oversamples length mismatch: "
                             f"{len(X_parts)} vs {len(oversamples)}")
        if mask_mode not in self._MASK_MODES:
            raise ValueError(f"mask_mode must be one of {self._MASK_MODES}, "
                             f"got {mask_mode!r}")

        self._X_parts = X_parts
        self._y_parts = y_parts
        self._oversamples = [int(o) for o in oversamples]
        self._mask_mode = mask_mode

        # Cumulative end index of each part (in the oversampled global index space).
        # part_starts[i] = start of part i; part_starts[-1] = total length.
        part_starts = np.zeros(len(X_parts) + 1, dtype=np.int64)
        for i, (Xp, os_) in enumerate(zip(X_parts, self._oversamples)):
            if len(Xp) != len(y_parts[i]):
                raise ValueError(f"X_parts[{i}]/y_parts[{i}] length mismatch: "
                                 f"{len(Xp)} vs {len(y_parts[i])}")
            part_starts[i + 1] = part_starts[i] + len(Xp) * os_
        self._part_starts = part_starts
        self._total = int(part_starts[-1])
        self._part_lens = np.array([len(Xp) for Xp in X_parts], dtype=np.int64)

        # Precompute the per-row active mask once per part. For "all" mode it's
        # implicitly 1.0 everywhere (we skip the array). For "nonzero" we
        # materialise a float32 vector per part so __getitems__ does a lookup
        # instead of recomputing |y|.sum(axis=1) > 0 every batch — and so
        # iter_active_parts stays cheap.
        if mask_mode == "nonzero":
            self._active_per_part: list[np.ndarray] | None = [
                (np.abs(yp).sum(axis=1) > 0).astype(np.float32)
                for yp in y_parts
            ]
        else:
            self._active_per_part = None

    def __len__(self) -> int:
        return self._total

    def __getitem__(self, idx: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        # Locate the part that owns this global idx, then resolve to a local
        # row index with modulo so oversampled CSVs reuse the underlying
        # mmap rows instead of holding duplicates on disk.
        pi = int(np.searchsorted(self._part_starts, idx, side="right") - 1)
        part_start = int(self._part_starts[pi])
        part_len = int(self._part_lens[pi])
        local_idx = (idx - part_start) % part_len

        # np.array(view) always copies the single sample out of the read-only
        # mmap into a normal writable RAM buffer (torch.from_numpy refuses
        # read-only arrays). One row is small — cheap. NaNs are scrubbed at
        # cache write time (bc_cache._CACHE_VERSION = 3) so no runtime fixup.
        x_row = np.array(self._X_parts[pi][local_idx], dtype=np.float32)
        y_row = np.array(self._y_parts[pi][local_idx], dtype=np.float32)

        if self._active_per_part is None:
            m = 1.0
        else:
            m = float(self._active_per_part[pi][local_idx])

        return (torch.from_numpy(x_row),
                torch.from_numpy(y_row),
                torch.tensor(m, dtype=torch.float32))

    def __getitems__(self, indices) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Vectorized batch fetch — one fancy-index per part instead of B
        per-sample __getitem__ calls.

        PyTorch's _MapDatasetFetcher detects __getitems__ and calls it with
        the batch index list; the result is fed to collate_fn unchanged. We
        return a pre-stacked (X, y, mask) tuple, so build_data_loaders pairs
        this with passthrough_collate to skip the default per-sample stack.

        Sample order is preserved via boolean-mask writes into a pre-allocated
        batch buffer, so reproducibility (random_split / RandomSampler seed)
        is unaffected.
        """
        idx_arr = np.asarray(indices, dtype=np.int64)
        b = idx_arr.shape[0]
        x_shape = self._X_parts[0].shape[1:]
        y_shape = self._y_parts[0].shape[1:]
        x_batch = np.empty((b,) + x_shape, dtype=np.float32)
        y_batch = np.empty((b,) + y_shape, dtype=np.float32)
        if self._active_per_part is None:
            m_batch = np.ones(b, dtype=np.float32)
        else:
            m_batch = np.empty(b, dtype=np.float32)

        pi_all = np.searchsorted(self._part_starts, idx_arr, side="right") - 1
        for p in np.unique(pi_all):
            sel = (pi_all == p)
            global_ids = idx_arr[sel]
            part_start = int(self._part_starts[p])
            part_len = int(self._part_lens[p])
            local_ids = (global_ids - part_start) % part_len
            # mmap fancy-index → one contiguous read per part. NaNs are
            # scrubbed at cache write time (bc_cache._CACHE_VERSION = 3),
            # so no runtime nan_to_num. Mask is precomputed per part in
            # __init__ — lookup, no |y|.sum(axis=1) per batch.
            x_batch[sel] = self._X_parts[p][local_ids]
            y_batch[sel] = self._y_parts[p][local_ids]
            if self._active_per_part is not None:
                m_batch[sel] = self._active_per_part[p][local_ids]

        return (torch.from_numpy(x_batch),
                torch.from_numpy(y_batch),
                torch.from_numpy(m_batch))

    @property
    def y_parts(self) -> list[np.ndarray]:
        return self._y_parts

    @property
    def oversamples(self) -> list[int]:
        return self._oversamples

    @property
    def mask_mode(self) -> str:
        return self._mask_mode

    def iter_active_parts(self):
        """Yield (y_active_view, oversample) per part for streaming stats.

        For mask_mode="all" each yield is the full y_part; for "nonzero" it
        uses the per-part active mask precomputed in __init__ to slice
        y_part — no recompute of |y|.sum(axis=1) > 0.
        """
        for i, (y_part, os_) in enumerate(zip(self._y_parts, self._oversamples)):
            if self._active_per_part is None:
                yield y_part, os_
            else:
                m = self._active_per_part[i] > 0  # float32 mask -> bool
                yield y_part[m], os_


def load_or_build_cache(
    csv_dir: Path,
    csv_files: list[Path],
    cache_dir: Path,
    cfg: BCConfig,
    logger: logging.Logger,
    per_csv_loader: PerCsvLoader,
    oversample_fn: OversampleFn = lambda _p, _c: 1,
) -> tuple[list[np.ndarray], list[np.ndarray], list[int]] | None:
    """Ensure every CSV has its (X, y) pair cached on disk, then mmap them back.

    Workflow:
      1. If manifest.json is present and valid, skip CSV parsing entirely.
      2. Otherwise, parse each CSV in isolation, write its part files, and
         commit the manifest last. Peak RAM per CSV is one CSV's X tensor
         (never the sum across all CSVs).

    Returns (X_parts [mmap], y_parts [in-RAM], oversamples) or None if
    there was no data to load successfully.
    """
    parts_meta = load_manifest(cache_dir)
    if parts_meta is None:
        parts_meta = _build_cache(
            csv_dir=csv_dir,
            csv_files=csv_files,
            cache_dir=cache_dir,
            cfg=cfg,
            logger=logger,
            per_csv_loader=per_csv_loader,
            oversample_fn=oversample_fn,
        )
        if parts_meta is None:
            return None

    X_parts, y_parts = open_part_arrays(cache_dir, parts_meta, logger)
    # Oversamples are stored OUTSIDE the .npy files so we can tweak the
    # oversample factor via config without rebuilding the CSV parse cache.
    # Re-derive them from the current csv_files + cfg now.
    stem_to_csv = {p.stem: p for p in csv_files}
    oversamples: list[int] = []
    for entry in parts_meta:
        stem = entry["stem"]
        csv_path = stem_to_csv.get(stem)
        if csv_path is None:
            # Cache is authoritative (CSV list must match cache_key), but if
            # we somehow get here the safest fallback is 1x.
            oversamples.append(1)
        else:
            oversamples.append(int(oversample_fn(csv_path, cfg)))
    return X_parts, y_parts, oversamples


def _process_csv_worker(
    args: tuple[Path, BCConfig, Path, PerCsvLoader, int]
) -> tuple:
    """ProcessPoolExecutor worker: parse one CSV and write its cache part.

    Runs in a child process. Each worker parses one CSV via the supplied
    per-CSV loader and writes the X/y ``.npy`` pair directly to ``cache_dir``
    — the multi-MB feature tensors never traverse the IPC pipe, only the
    manifest entry comes back. Top-level function so ProcessPoolExecutor can
    pickle it under the spawn start-method.

    Returns:
        ("ok", csv_path, entry_dict, n_samples) on success
        ("error", csv_path, error_repr)         on per-CSV failure
    """
    csv_path, cfg, cache_dir, per_csv_loader, oversample_repeat = args
    worker_logger = logging.getLogger("ut99.bc_cache.worker")
    try:
        X, y = per_csv_loader(str(csv_path), cfg, worker_logger)
    except Exception as ex:  # noqa: BLE001 - propagate any parse failure as a tagged result
        return ("error", csv_path, repr(ex))
    entry = save_cache_part(cache_dir, csv_path.stem, X, y)
    entry["oversample_at_write"] = oversample_repeat  # informational only
    return ("ok", csv_path, entry, int(X.shape[0]))


def _build_cache(
    csv_dir: Path,
    csv_files: list[Path],
    cache_dir: Path,
    cfg: BCConfig,
    logger: logging.Logger,
    per_csv_loader: PerCsvLoader,
    oversample_fn: OversampleFn,
) -> list[dict[str, Any]] | None:
    """Parse every CSV in parallel, write per-CSV parts, commit manifest."""
    t0 = time.time()
    cache_dir.mkdir(parents=True, exist_ok=True)
    log_print(logger, f"CSVs: {len(csv_files)} files in {csv_dir}")

    num_workers = max(1, min(os.cpu_count() or 1, _MAX_PARSE_WORKERS))
    log_print(logger, f"Parallel parse pool: {num_workers} workers")

    # Bind tasks (csv_path, cfg, cache_dir, loader, oversample). The oversample
    # factor is computed in the parent — keeps the worker payload trivial and
    # avoids pickling the oversample lambda (often a closure / lambda which is
    # not picklable under spawn).
    tasks: list[tuple[Path, BCConfig, Path, PerCsvLoader, int]] = [
        (csv_path, cfg, cache_dir, per_csv_loader, int(oversample_fn(csv_path, cfg)))
        for csv_path in csv_files
    ]

    # Spawn (not fork) so child interpreters start clean: no inherited torch /
    # CUDA / open-fd state from the trainer process that would cause weird
    # post-fork breakage on hosts where CUDA was already touched.
    mp_ctx = mp.get_context("spawn")
    parts_meta: list[dict[str, Any]] = []
    n_done = 0
    n_failed = 0
    n_total = len(tasks)

    with ProcessPoolExecutor(max_workers=num_workers, mp_context=mp_ctx) as exe:
        futures = {exe.submit(_process_csv_worker, task): task[0] for task in tasks}
        for fut in as_completed(futures):
            csv_path = futures[fut]
            try:
                result = fut.result()
            except Exception as ex:  # noqa: BLE001 - top-level worker death
                n_failed += 1
                log_print(logger, f"Worker crashed on {csv_path}: {ex!r}")
                continue
            status = result[0]
            if status == "error":
                n_failed += 1
                _, _, err = result
                log_print(logger, f"Failed to load {csv_path}: {err}")
                continue
            _, _, entry, n_samples = result
            parts_meta.append(entry)
            n_done += 1
            os_repeat = int(entry.get("oversample_at_write", 1))
            label = "recovery " if os_repeat > 1 else ""
            suffix = f" x{os_repeat} oversample" if os_repeat > 1 else ""
            log_print(logger, f"Loaded {label}CSV [{n_done}/{n_total}]: "
                      f"{csv_path.name} ({n_samples} samples{suffix})")

    if not parts_meta:
        log_print(logger, "No data loaded successfully. Aborting.")
        return None

    # Sort manifest entries by stem so the on-disk manifest is deterministic
    # regardless of completion order. Order does not affect dataset semantics
    # — MmapBCDataset treats parts as a flat union — but stable order makes
    # diffs and debug log scans easier.
    parts_meta.sort(key=lambda e: e["stem"])

    elapsed = max(time.time() - t0, 1e-9)
    write_manifest(cache_dir, parts_meta, logger)
    log_print(logger, f"CSV parse + feature extract: {elapsed:.1f}s "
              f"({n_total / elapsed:.1f} CSV/s, {n_failed} failed)")
    return parts_meta


def passthrough_collate(batch):
    """Pair with MmapBCDataset.__getitems__ — that method already returns a
    pre-stacked (X, y, mask) tuple, so we skip the default collate's
    per-sample stack. Module-level for worker pickling."""
    return batch


def _dataloader_worker_init(worker_id: int) -> None:
    """Cap CPU threads to 1 per DataLoader worker.

    Without this each worker process spawns torch.get_num_threads() OMP/MKL
    threads (defaults to the physical-core count, e.g. 14 on the i9-10940X
    trainer). With num_workers=4 that's 56 worker threads + the main
    trainer's threads competing for the same cores — context-switch storms
    tanked train sps from ~0.6 s/batch to ~20 s/batch in the 4090 BC run
    (GPU sat at 0% waiting for batches). Workers only do numpy fancy-index
    on mmap'd parts, which is single-thread-friendly.

    Module-level so DataLoader can pickle it under the spawn start method.
    """
    import os
    os.environ["OMP_NUM_THREADS"] = "1"
    os.environ["MKL_NUM_THREADS"] = "1"
    os.environ["NUMEXPR_NUM_THREADS"] = "1"
    os.environ["OPENBLAS_NUM_THREADS"] = "1"
    torch.set_num_threads(1)


def build_data_loaders(dataset: Dataset, cfg: BCConfig,
                       logger: logging.Logger) -> tuple[DataLoader, DataLoader]:
    """Split a pre-built dataset into train/val DataLoaders.

    DataLoader runtime (num_workers, persistent_workers, prefetch_factor,
    pin_memory) comes from cfg.data_loader (loaded from <model>/bc.json).
    """
    dl = cfg.data_loader
    # PyTorch refuses persistent_workers / prefetch_factor when num_workers == 0.
    common_kwargs: dict = {
        "pin_memory": dl.pin_memory,
        "num_workers": dl.num_workers,
        "collate_fn": passthrough_collate,
    }
    if dl.num_workers > 0:
        common_kwargs["persistent_workers"] = dl.persistent_workers
        common_kwargs["prefetch_factor"] = dl.prefetch_factor
        common_kwargs["worker_init_fn"] = _dataloader_worker_init

    val_size = int(len(dataset) * cfg.val_split)
    val_size = max(val_size, min(cfg.batch_size, len(dataset) // 3))
    train_size = len(dataset) - val_size

    train_ds, val_ds = random_split(dataset, [train_size, val_size],
                                     generator=torch.Generator().manual_seed(42))

    val_loader = DataLoader(val_ds, batch_size=cfg.batch_size, shuffle=False,
                             **common_kwargs)
    log_print(logger, f"Train/val split: {train_size}/{val_size}")
    log_print(logger, f"DataLoader: num_workers={dl.num_workers} "
              f"persistent_workers={dl.persistent_workers if dl.num_workers > 0 else False} "
              f"prefetch_factor={dl.prefetch_factor if dl.num_workers > 0 else 'n/a'} "
              f"pin_memory={dl.pin_memory}")

    train_loader = DataLoader(train_ds, batch_size=cfg.batch_size, shuffle=True,
                               drop_last=True, **common_kwargs)

    return train_loader, val_loader
