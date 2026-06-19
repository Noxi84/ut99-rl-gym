"""Joint async batch prefetch with aux + reward-decomp support.

Two background threads: an ingest thread reads NPZ files into the replay
buffer, and a prefetch thread samples batches, applies reward-norm (Welford
online), and transfers pinned-memory tensors to the device. A fine-grained
lock protects only the brief buffer mutations (add_batch / sample_with_extras)
so NPZ decompression never blocks GPU training.
"""
from __future__ import annotations

import glob
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from queue import Empty, Full, Queue

import numpy as np
import torch

from train.rl.shared.sac_core.replay_buffer import (
    ReplayBuffer,
    _INGEST_PARSE_WORKERS,
    _parse_npz_for_ingest,
)


class JointAsyncBatchProvider:
    """Prefetches joint batches with aux + reward-decomp tensors.

    Each ``next_batch()`` returns:

        (states, actions, raw_rewards, rewards, next_states, dones, extras)

    where ``extras`` is a dict with:

    - ``target_labels``       : Tensor[B] int64 (aux head supervision)
    - ``target_confidences``  : Tensor[B] float32 (per-sample CE weight)
    - ``rewards_decomp``      : dict[head_key, Tensor[B] float32] (multi-head)

    ``rewards_decomp`` is only populated when ``replay.reward_decomp_keys`` is
    set; otherwise the key is absent from ``extras``. Same for aux labels.

    Reward normalisation is applied to the scalar ``rewards`` channel. Per-skill
    decomp slices can be normalised independently for multi-head critics so a
    dense high-magnitude head does not drown out sparse fire/altFire heads.
    """

    def __init__(
        self,
        replay: ReplayBuffer,
        buffer_dir: str,
        batch_size: int,
        seq_len: int,
        input_size: int,
        device: torch.device,
        max_file_age_seconds: float,
        reward_normalization: bool,
        reward_decomp_normalization: bool,
        event_priority_fraction: float,
        event_priority_positive_fraction: float,
        prefetch_depth: int,
        ingest_interval: float,
        max_files_per_ingest: int,
        champion_experience_enabled: bool,
        logger,
    ):
        self._replay = replay
        self._buffer_dir = buffer_dir
        self._batch_size = batch_size
        self._seq_len = seq_len
        self._input_size = input_size
        self._device = device
        self._max_file_age = max_file_age_seconds
        self._reward_norm = reward_normalization
        self._decomp_reward_norm = reward_decomp_normalization
        self._event_priority_fraction = float(event_priority_fraction)
        self._event_priority_positive_fraction = float(event_priority_positive_fraction)
        self._ingest_interval = ingest_interval
        self._max_files_per_ingest = max_files_per_ingest
        self._champion_experience_enabled = champion_experience_enabled

        self._queue: Queue = Queue(maxsize=prefetch_depth)
        self._buf_lock = threading.Lock()
        self._loaded_files: set[str] = set()
        self._stop_event = threading.Event()
        self._ingest_thread: threading.Thread | None = None
        self._prefetch_thread: threading.Thread | None = None

        self._reward_mean = 0.0
        self._reward_var = 1.0
        self._reward_count = 0
        self._decomp_reward_mean: dict[str, float] = {}
        self._decomp_reward_var: dict[str, float] = {}
        self._decomp_reward_count: dict[str, int] = {}

        self.total_ingested = 0
        self.buffer_size = 0

    def start(self) -> None:
        self._stop_event.clear()
        self._ingest_thread = threading.Thread(
            target=self._run_ingest, daemon=True, name="joint-ingest",
        )
        self._prefetch_thread = threading.Thread(
            target=self._run_prefetch, daemon=True, name="joint-batch-prefetch",
        )
        self._ingest_thread.start()
        self._prefetch_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._ingest_thread is not None:
            self._ingest_thread.join(timeout=10)
            self._ingest_thread = None
        if self._prefetch_thread is not None:
            self._prefetch_thread.join(timeout=10)
            self._prefetch_thread = None

    def next_batch(self):
        """Returns ``(states, actions, raw_rewards, rewards, next_states, dones, extras)``.

        Blocks until a batch is available. ``raw_rewards`` is the un-normalised
        numpy array (kept for reward-window tracking); ``rewards`` is the GPU
        tensor that goes into ``compute_critic_loss``.
        """
        while not self._stop_event.is_set():
            try:
                return self._queue.get(timeout=5.0)
            except Empty:
                continue
        raise RuntimeError("JointAsyncBatchProvider stopped")

    @property
    def has_data(self) -> bool:
        return self._replay.size >= self._batch_size

    def _normalise_rewards(self, r: np.ndarray) -> np.ndarray:
        n_b = len(r)
        batch_mean = float(r.mean())
        batch_var = float(r.var())
        old_count = self._reward_count
        new_count = old_count + n_b
        if old_count == 0:
            self._reward_mean = batch_mean
            self._reward_var = batch_var
        else:
            delta = batch_mean - self._reward_mean
            self._reward_mean += delta * n_b / new_count
            m2_old = self._reward_var * old_count
            m2_batch = batch_var * n_b
            self._reward_var = (
                m2_old + m2_batch + delta ** 2 * old_count * n_b / new_count
            ) / new_count
        self._reward_count = new_count
        std = max(np.sqrt(self._reward_var), 1e-6)
        return ((r - self._reward_mean) / std).astype(np.float32)

    def _normalise_named_decomp_reward(self, key: str, r: np.ndarray) -> np.ndarray:
        n_b = len(r)
        batch_mean = float(r.mean())
        batch_var = float(r.var())
        old_count = self._decomp_reward_count.get(key, 0)
        new_count = old_count + n_b
        old_mean = self._decomp_reward_mean.get(key, 0.0)
        old_var = self._decomp_reward_var.get(key, 1.0)
        if old_count == 0:
            new_mean = batch_mean
            new_var = batch_var
        else:
            delta = batch_mean - old_mean
            new_mean = old_mean + delta * n_b / new_count
            m2_old = old_var * old_count
            m2_batch = batch_var * n_b
            new_var = (
                m2_old + m2_batch + delta ** 2 * old_count * n_b / new_count
            ) / new_count
        self._decomp_reward_mean[key] = new_mean
        self._decomp_reward_var[key] = new_var
        self._decomp_reward_count[key] = new_count
        std = max(np.sqrt(new_var), 1e-6)
        return ((r - new_mean) / std).astype(np.float32)

    def _normalise_decomp_rewards(
        self,
        rewards_decomp: dict[str, np.ndarray],
    ) -> dict[str, np.ndarray]:
        if not self._decomp_reward_norm:
            return rewards_decomp
        return {
            k: self._normalise_named_decomp_reward(k, arr)
            for k, arr in rewards_decomp.items()
        }

    def _build_extras(self, raw_extras: dict) -> dict:
        """Convert numpy extras → device tensors. Only the aux + decomp keys
        the buffer actually tracks are returned (``indices`` is dropped — not
        needed downstream)."""
        out: dict = {}
        if "target_labels" in raw_extras:
            labels_np = raw_extras["target_labels"].astype(np.int64, copy=False)
            confs_np = raw_extras["target_confidences"].astype(np.float32, copy=False)
            out["target_labels"] = torch.from_numpy(labels_np).pin_memory().to(
                self._device, non_blocking=True,
            )
            out["target_confidences"] = torch.from_numpy(confs_np).pin_memory().to(
                self._device, non_blocking=True,
            )
        if "rewards_decomp" in raw_extras:
            decomp_t = {}
            rewards_decomp = self._normalise_decomp_rewards(raw_extras["rewards_decomp"])
            for k, arr in rewards_decomp.items():
                arr_np = arr.astype(np.float32, copy=False)
                decomp_t[k] = torch.from_numpy(arr_np).pin_memory().to(
                    self._device, non_blocking=True,
                )
            out["rewards_decomp"] = decomp_t
        if "teammate_states" in raw_extras:
            tm_np = raw_extras["teammate_states"].astype(np.float32, copy=False)
            ntm_np = raw_extras["next_teammate_states"].astype(np.float32, copy=False)
            out["teammate_states"] = torch.from_numpy(tm_np).pin_memory().to(
                self._device, non_blocking=True,
            )
            out["next_teammate_states"] = torch.from_numpy(ntm_np).pin_memory().to(
                self._device, non_blocking=True,
            )
        return out

    def _run_ingest(self) -> None:
        """Ingest thread: parse NPZ files (no lock), then add_batch per file
        (brief lock). The prefetch thread can sample between individual
        add_batch calls instead of waiting for the entire ingest cycle."""
        parse_kwargs = {
            "min_time": 0.0,
            "champion_experience_enabled": self._champion_experience_enabled,
            "aux_target_enabled": self._replay.aux_target_enabled,
            "reward_decomp_keys": (
                tuple(self._replay.reward_decomp_keys)
                if self._replay.reward_decomp_keys is not None else None
            ),
            "teammate_state_dim": self._replay.teammate_state_dim,
            "transform_actions": self._replay.transform_actions_for_sac,
        }

        while not self._stop_event.is_set():
            parse_kwargs["min_time"] = time.time() - self._max_file_age

            pattern = os.path.join(self._buffer_dir, "batch_*.npz")
            npz_files = sorted(glob.glob(pattern), key=os.path.getmtime)

            candidates: list[str] = []
            for f in npz_files:
                if self._max_files_per_ingest > 0 and len(candidates) >= self._max_files_per_ingest:
                    break
                if f in self._loaded_files:
                    continue
                candidates.append(f)

            if candidates:
                total_added = 0
                champion_dropped = 0
                champion_files = 0
                workers = min(_INGEST_PARSE_WORKERS, len(candidates))

                with ThreadPoolExecutor(max_workers=workers) as pool:
                    futures = {
                        pool.submit(_parse_npz_for_ingest, f, **parse_kwargs): f
                        for f in candidates
                    }
                    for fut in as_completed(futures):
                        f = futures[fut]
                        try:
                            result = fut.result()
                        except Exception as exc:
                            print(
                                f"SAC_REPLAY_INGEST_SKIP: {os.path.basename(f)} "
                                f"worker died: {type(exc).__name__}: {exc} (removing)",
                                flush=True,
                            )
                            try:
                                os.remove(f)
                            except OSError:
                                pass
                            self._loaded_files.add(f)
                            continue

                        status = result["status"]
                        if status == "skip_old":
                            try:
                                os.remove(f)
                            except OSError:
                                pass
                            continue
                        if status == "skip_champ":
                            champion_dropped += int(result["champ_dropped"])
                            champion_files += 1
                            self._loaded_files.add(f)
                            try:
                                os.remove(f)
                            except OSError:
                                pass
                            continue
                        if status == "skip_empty":
                            champion_dropped += int(result["champ_dropped"])
                            if result["champ_dropped"] > 0:
                                champion_files += 1
                            self._loaded_files.add(f)
                            try:
                                os.remove(f)
                            except OSError:
                                pass
                            continue
                        if status == "error":
                            print(
                                f"SAC_REPLAY_INGEST_SKIP: {os.path.basename(f)} "
                                f"{result['exc_repr']} (removing)",
                                flush=True,
                            )
                            try:
                                os.remove(f)
                            except OSError:
                                pass
                            self._loaded_files.add(f)
                            continue
                        if status != "ok":
                            raise RuntimeError(f"Unknown ingest status: {status}")

                        with self._buf_lock:
                            self._replay.add_batch(
                                result["s"], result["a"], result["r"],
                                result["ns"], result["d"],
                                target_labels=result["aux_labels"],
                                target_confidences=result["aux_confidences"],
                                rewards_decomp=result["decomp"],
                                teammate_states=result["tm"],
                                next_teammate_states=result["ntm"],
                            )
                        self._loaded_files.add(f)
                        total_added += len(result["s"])
                        champ_drops = int(result["champ_dropped"])
                        if champ_drops > 0:
                            champion_dropped += champ_drops
                            champion_files += 1
                        try:
                            os.remove(f)
                        except OSError:
                            pass

                if champion_dropped > 0:
                    print(
                        f"SAC_REPLAY_FILTER: dropped {champion_dropped} champion rows "
                        f"across {champion_files} files (kept {total_added} current rows)",
                        flush=True,
                    )
                if total_added > 0:
                    self.total_ingested += total_added
                self.buffer_size = self._replay.size

            self._stop_event.wait(self._ingest_interval)

    def _run_prefetch(self) -> None:
        while not self._stop_event.is_set():
            if self._replay.size < self._batch_size:
                time.sleep(1.0)
                continue

            with self._buf_lock:
                base, raw_extras = self._replay.sample_with_extras(
                    self._batch_size,
                    event_fraction=self._event_priority_fraction,
                    positive_fraction=self._event_priority_positive_fraction,
                )
            s, a, r, ns, d = base
            raw_rewards = r.copy()

            if self._reward_norm:
                r = self._normalise_rewards(r)

            states = torch.from_numpy(s).pin_memory().to(self._device, non_blocking=True) \
                .view(-1, self._seq_len, self._input_size)
            actions = torch.from_numpy(a).pin_memory().to(self._device, non_blocking=True)
            rewards = torch.from_numpy(r).pin_memory().to(self._device, non_blocking=True)
            next_states = torch.from_numpy(ns).pin_memory().to(self._device, non_blocking=True) \
                .view(-1, self._seq_len, self._input_size)
            dones = torch.from_numpy(d).pin_memory().to(self._device, non_blocking=True)
            extras = self._build_extras(raw_extras)

            batch = (states, actions, raw_rewards, rewards, next_states, dones, extras)
            while not self._stop_event.is_set():
                try:
                    self._queue.put(batch, timeout=1.0)
                    break
                except Full:
                    continue
