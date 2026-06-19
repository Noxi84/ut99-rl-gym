"""In-memory circular replay buffer for SAC off-policy training."""
from __future__ import annotations

import glob
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Callable

import numpy as np


# NPZ files are zip+deflate; np.load decompresses single-threaded per file.
# zlib releases the GIL during inflate, so a ThreadPoolExecutor scales near
# linearly on a multi-core trainer. 16 threads keeps CPU busy without
# saturating the page cache on 14-core machines (2× oversubscription).
_INGEST_PARSE_WORKERS = 16


def _resolve_npz_key(keys: set[str], base: str) -> str | None:
    """NPZ files persisted with ``np.savez_compressed`` carry the ``.npy`` suffix
    while in-memory dicts use the bare name. Mirror the bestaande tolerance in
    ``ingest_npz_files`` so commitment-3 optional fields lookup the same way."""
    if base in keys:
        return base
    suffixed = f"{base}.npy"
    if suffixed in keys:
        return suffixed
    return None


class ReplayBuffer:
    """Fixed-capacity circular buffer backed by pre-allocated numpy arrays.

    Joint VR+shooting per-skill decomp + aux target supervision: when
    ``aux_target_enabled`` is set, parallel ``target_labels`` and
    ``target_confidences`` arrays are allocated and tracked alongside every
    transition. When ``reward_decomp_keys`` is set, a dict of parallel
    per-skill ``rewards_decomp[key]`` arrays is allocated. All extras share
    the same write index / wrap-around / sample indices as the base arrays —
    no separate bookkeeping.

    The legacy 5-tuple ``sample()`` remains available for callers that don't
    need the decomp/aux extras; ``sample_with_extras()`` returns them.
    """

    def __init__(
        self,
        capacity: int,
        state_dim: int,
        action_dim: int,
        target_features: list[str] | None = None,
        target_feature_types: dict[str, str] | None = None,
        aux_target_enabled: bool = False,
        reward_decomp_keys: list[str] | tuple[str, ...] | None = None,
        event_priority_reward_keys: list[str] | tuple[str, ...] | None = None,
        event_priority_action_indices: list[int] | tuple[int, ...] | None = None,
        event_priority_min_abs_reward: float = 1e-6,
        teammate_state_dim: int = 0,
    ):
        self.capacity = capacity
        self.states = np.zeros((capacity, state_dim), dtype=np.float32)
        self.actions = np.zeros((capacity, action_dim), dtype=np.float32)
        self.rewards = np.zeros(capacity, dtype=np.float32)
        self.next_states = np.zeros((capacity, state_dim), dtype=np.float32)
        self.dones = np.zeros(capacity, dtype=np.float32)
        self._size = 0
        self._write_idx = 0
        self._action_dim = action_dim
        self._continuous_action_mask = np.ones(action_dim, dtype=bool)
        self._binary_action_mask = np.zeros(action_dim, dtype=bool)

        if target_features is not None:
            if len(target_features) != action_dim:
                raise ValueError(
                    f"target_features has {len(target_features)} entries, expected action_dim={action_dim}"
                )
            target_feature_types = target_feature_types or {}
            self._continuous_action_mask[:] = False
            for idx, feature in enumerate(target_features):
                feature_type = target_feature_types.get(feature, "continuous")
                if feature_type == "binary":
                    self._binary_action_mask[idx] = True
                elif feature_type in ("continuous", "steering"):
                    self._continuous_action_mask[idx] = True
                else:
                    raise ValueError(f"Unsupported SAC target feature type for {feature}: {feature_type}")

        self._aux_target_enabled = bool(aux_target_enabled)
        if self._aux_target_enabled:
            # -1 sentinel = "no event / masked"; SAC trainer treats confidence
            # 0 transitions as no-grad in compute_aux_target_loss.
            self.target_labels = np.full(capacity, -1, dtype=np.int64)
            self.target_confidences = np.zeros(capacity, dtype=np.float32)
        else:
            self.target_labels = None
            self.target_confidences = None

        if reward_decomp_keys is None:
            self._reward_decomp_keys: tuple[str, ...] | None = None
            self.rewards_decomp: dict[str, np.ndarray] | None = None
        else:
            keys = tuple(str(k) for k in reward_decomp_keys)
            if not keys:
                raise ValueError("reward_decomp_keys must be non-empty when provided")
            if len(set(keys)) != len(keys):
                raise ValueError(f"reward_decomp_keys must be unique, got {keys}")
            self._reward_decomp_keys = keys
            self.rewards_decomp = {k: np.zeros(capacity, dtype=np.float32) for k in keys}

        # Fase 2.5 CTDE — closest-2 teammate slice (critic-only input).
        # When dim > 0 the buffer requires teammate_states on add_batch and
        # parses ``teammate_state.npy`` during ingest. dim 0 disables the path.
        teammate_state_dim = int(teammate_state_dim)
        if teammate_state_dim < 0:
            raise ValueError(
                f"teammate_state_dim must be >= 0, got {teammate_state_dim}"
            )
        self._teammate_state_dim = teammate_state_dim
        if teammate_state_dim > 0:
            self.teammate_states = np.zeros(
                (capacity, teammate_state_dim), dtype=np.float32,
            )
            self.next_teammate_states = np.zeros(
                (capacity, teammate_state_dim), dtype=np.float32,
            )
        else:
            self.teammate_states = None
            self.next_teammate_states = None

        self.configure_event_priority(
            reward_keys=event_priority_reward_keys,
            action_indices=event_priority_action_indices,
            min_abs_reward=event_priority_min_abs_reward,
        )

    @property
    def aux_target_enabled(self) -> bool:
        return self._aux_target_enabled

    @property
    def reward_decomp_keys(self) -> tuple[str, ...] | None:
        return self._reward_decomp_keys

    @property
    def teammate_state_dim(self) -> int:
        return self._teammate_state_dim

    @property
    def ctde_enabled(self) -> bool:
        return self._teammate_state_dim > 0

    def _ensure_event_priority_runtime(self) -> None:
        """Backfill fields when older pickled buffers are loaded."""
        if not hasattr(self, "_event_priority_reward_keys"):
            self._event_priority_reward_keys = ()
        if not hasattr(self, "_event_priority_action_indices"):
            self._event_priority_action_indices = ()
        if not hasattr(self, "_event_priority_min_abs_reward"):
            self._event_priority_min_abs_reward = 1e-6
        if (not hasattr(self, "priority_events")
                or self.priority_events.shape != (self.capacity,)):
            self.priority_events = np.zeros(self.capacity, dtype=bool)
        if (not hasattr(self, "priority_positive_events")
                or self.priority_positive_events.shape != (self.capacity,)):
            self.priority_positive_events = np.zeros(self.capacity, dtype=bool)

    def configure_event_priority(
        self,
        reward_keys: list[str] | tuple[str, ...] | None = None,
        action_indices: list[int] | tuple[int, ...] | None = None,
        min_abs_reward: float = 1e-6,
    ) -> None:
        """Configure event masks used by priority sampling.

        The buffer only stores boolean masks; callers choose the actual
        priority fractions when sampling. A positive reward event is tracked
        separately so batches can reserve slots for successful fire feedback.
        """
        reward_key_tuple = tuple(str(k) for k in (reward_keys or ()))
        if reward_key_tuple:
            if self._reward_decomp_keys is None:
                raise ValueError(
                    "event_priority_reward_keys require reward_decomp_keys"
                )
            missing = [k for k in reward_key_tuple if k not in self._reward_decomp_keys]
            if missing:
                raise ValueError(
                    f"event_priority_reward_keys {missing} not present in "
                    f"reward_decomp_keys={list(self._reward_decomp_keys)}"
                )
        action_index_tuple = tuple(int(i) for i in (action_indices or ()))
        invalid = [i for i in action_index_tuple if i < 0 or i >= self._action_dim]
        if invalid:
            raise ValueError(
                f"event_priority_action_indices out of range for action_dim="
                f"{self._action_dim}: {invalid}"
            )
        min_abs_reward = float(min_abs_reward)
        if min_abs_reward <= 0.0:
            raise ValueError("event_priority_min_abs_reward must be > 0")
        self._event_priority_reward_keys = reward_key_tuple
        self._event_priority_action_indices = action_index_tuple
        self._event_priority_min_abs_reward = min_abs_reward
        self._ensure_event_priority_runtime()

    def _compute_priority_masks(
        self,
        actions: np.ndarray,
        rewards_decomp: dict[str, np.ndarray] | None,
    ) -> tuple[np.ndarray, np.ndarray]:
        self._ensure_event_priority_runtime()
        n = len(actions)
        event_mask = np.zeros(n, dtype=bool)
        positive_mask = np.zeros(n, dtype=bool)

        if self._event_priority_reward_keys:
            if rewards_decomp is None:
                raise ValueError(
                    "event priority reward keys configured but rewards_decomp is missing"
                )
            threshold = self._event_priority_min_abs_reward
            for key in self._event_priority_reward_keys:
                values = np.asarray(rewards_decomp[key], dtype=np.float32)
                event_mask |= np.abs(values) >= threshold
                positive_mask |= values > threshold

        if self._event_priority_action_indices:
            idx = np.asarray(self._event_priority_action_indices, dtype=np.int64)
            event_mask |= np.any(actions[:, idx] > 0.0, axis=1)

        event_mask |= positive_mask
        return event_mask, positive_mask

    def _write_priority_masks(
        self,
        idx: int,
        event_mask: np.ndarray,
        positive_mask: np.ndarray,
    ) -> None:
        self._ensure_event_priority_runtime()
        n = len(event_mask)
        if idx + n <= self.capacity:
            self.priority_events[idx:idx + n] = event_mask
            self.priority_positive_events[idx:idx + n] = positive_mask
            return

        first = self.capacity - idx
        self.priority_events[idx:] = event_mask[:first]
        self.priority_positive_events[idx:] = positive_mask[:first]
        rest = n - first
        self.priority_events[:rest] = event_mask[first:]
        self.priority_positive_events[:rest] = positive_mask[first:]

    def add_batch(
        self,
        states: np.ndarray,
        actions: np.ndarray,
        rewards: np.ndarray,
        next_states: np.ndarray,
        dones: np.ndarray,
        target_labels: np.ndarray | None = None,
        target_confidences: np.ndarray | None = None,
        rewards_decomp: dict[str, np.ndarray] | None = None,
        teammate_states: np.ndarray | None = None,
        next_teammate_states: np.ndarray | None = None,
    ):
        """Add a batch of transitions. Wraps around when full (FIFO).

        When the buffer was constructed with ``aux_target_enabled=True``, the
        ``target_labels`` / ``target_confidences`` kwargs are REQUIRED — missing
        values raise (CLAUDE.md: no silent fallbacks). When
        ``reward_decomp_keys`` was set, ``rewards_decomp`` is required and
        must cover every configured key. When ``teammate_state_dim > 0``
        (Fase 2.5 CTDE), the ``teammate_states`` and ``next_teammate_states``
        kwargs are likewise required.
        """
        n = len(states)
        if n == 0:
            return

        if self._aux_target_enabled:
            if target_labels is None or target_confidences is None:
                raise ValueError(
                    "ReplayBuffer was created with aux_target_enabled=True; "
                    "add_batch requires target_labels and target_confidences"
                )
            if len(target_labels) != n or len(target_confidences) != n:
                raise ValueError(
                    f"aux arrays length mismatch: states={n} "
                    f"labels={len(target_labels)} confidences={len(target_confidences)}"
                )
        if self._reward_decomp_keys is not None:
            if rewards_decomp is None:
                raise ValueError(
                    "ReplayBuffer was created with reward_decomp_keys set; "
                    "add_batch requires rewards_decomp dict"
                )
            missing = [k for k in self._reward_decomp_keys if k not in rewards_decomp]
            if missing:
                raise ValueError(f"rewards_decomp missing keys {missing}")
            for k in self._reward_decomp_keys:
                if len(rewards_decomp[k]) != n:
                    raise ValueError(
                        f"rewards_decomp[{k!r}] length {len(rewards_decomp[k])} "
                        f"!= states length {n}"
                    )
        if self._teammate_state_dim > 0:
            if teammate_states is None or next_teammate_states is None:
                raise ValueError(
                    "ReplayBuffer was created with teammate_state_dim > 0; "
                    "add_batch requires teammate_states and next_teammate_states"
                )
            if (
                len(teammate_states) != n
                or len(next_teammate_states) != n
            ):
                raise ValueError(
                    f"teammate arrays length mismatch: states={n} "
                    f"teammate_states={len(teammate_states)} "
                    f"next_teammate_states={len(next_teammate_states)}"
                )
            if (
                teammate_states.shape[1] != self._teammate_state_dim
                or next_teammate_states.shape[1] != self._teammate_state_dim
            ):
                raise ValueError(
                    f"teammate arrays width mismatch: expected "
                    f"{self._teammate_state_dim} got "
                    f"teammate_states={teammate_states.shape[1]} "
                    f"next_teammate_states={next_teammate_states.shape[1]}"
                )

        idx = self._write_idx
        priority_events, priority_positive_events = self._compute_priority_masks(
            actions, rewards_decomp
        )
        if idx + n <= self.capacity:
            self.states[idx:idx + n] = states
            self.actions[idx:idx + n] = actions
            self.rewards[idx:idx + n] = rewards
            self.next_states[idx:idx + n] = next_states
            self.dones[idx:idx + n] = dones
            if self._aux_target_enabled:
                self.target_labels[idx:idx + n] = target_labels
                self.target_confidences[idx:idx + n] = target_confidences
            if self._reward_decomp_keys is not None:
                for k in self._reward_decomp_keys:
                    self.rewards_decomp[k][idx:idx + n] = rewards_decomp[k]
            if self._teammate_state_dim > 0:
                self.teammate_states[idx:idx + n] = teammate_states
                self.next_teammate_states[idx:idx + n] = next_teammate_states
        else:
            # Wrap around
            first = self.capacity - idx
            self.states[idx:] = states[:first]
            self.actions[idx:] = actions[:first]
            self.rewards[idx:] = rewards[:first]
            self.next_states[idx:] = next_states[:first]
            self.dones[idx:] = dones[:first]
            if self._aux_target_enabled:
                self.target_labels[idx:] = target_labels[:first]
                self.target_confidences[idx:] = target_confidences[:first]
            if self._reward_decomp_keys is not None:
                for k in self._reward_decomp_keys:
                    self.rewards_decomp[k][idx:] = rewards_decomp[k][:first]
            if self._teammate_state_dim > 0:
                self.teammate_states[idx:] = teammate_states[:first]
                self.next_teammate_states[idx:] = next_teammate_states[:first]
            rest = n - first
            self.states[:rest] = states[first:]
            self.actions[:rest] = actions[first:]
            self.rewards[:rest] = rewards[first:]
            self.next_states[:rest] = next_states[first:]
            self.dones[:rest] = dones[first:]
            if self._aux_target_enabled:
                self.target_labels[:rest] = target_labels[first:]
                self.target_confidences[:rest] = target_confidences[first:]
            if self._reward_decomp_keys is not None:
                for k in self._reward_decomp_keys:
                    self.rewards_decomp[k][:rest] = rewards_decomp[k][first:]
            if self._teammate_state_dim > 0:
                self.teammate_states[:rest] = teammate_states[first:]
                self.next_teammate_states[:rest] = next_teammate_states[first:]
        self._write_priority_masks(idx, priority_events, priority_positive_events)
        self._write_idx = (idx + n) % self.capacity
        self._size = min(self._size + n, self.capacity)

    def sample(self, batch_size: int):
        """Uniform random sample. Returns (states, actions, rewards, next_states, dones).

        Backwards-compat: the 5-tuple shape is preserved even when the buffer
        carries aux / decomp extras. Use ``sample_with_extras`` to retrieve those.
        """
        indices = np.random.randint(0, self._size, size=batch_size)
        return (
            self.states[indices],
            self.actions[indices],
            self.rewards[indices],
            self.next_states[indices],
            self.dones[indices],
        )

    def _sample_indices(
        self,
        batch_size: int,
        event_fraction: float = 0.0,
        positive_fraction: float = 0.0,
    ) -> np.ndarray:
        if self._size <= 0:
            raise ValueError("Cannot sample from an empty ReplayBuffer")
        event_fraction = min(max(float(event_fraction), 0.0), 1.0)
        positive_fraction = min(max(float(positive_fraction), 0.0), event_fraction)
        if event_fraction <= 0.0:
            return np.random.randint(0, self._size, size=batch_size)

        self._ensure_event_priority_runtime()
        n_priority = min(batch_size, int(round(batch_size * event_fraction)))
        n_positive = min(n_priority, int(round(batch_size * positive_fraction)))
        n_event = n_priority - n_positive
        n_uniform = batch_size - n_positive - n_event

        def from_pool(pool: np.ndarray, count: int) -> np.ndarray:
            if count <= 0:
                return np.empty(0, dtype=np.int64)
            if pool.size == 0:
                return np.random.randint(0, self._size, size=count)
            return pool[np.random.randint(0, pool.size, size=count)]

        valid_slice = slice(0, self._size)
        positive_pool = np.flatnonzero(self.priority_positive_events[valid_slice])
        event_pool = np.flatnonzero(self.priority_events[valid_slice])

        chunks = [
            from_pool(positive_pool, n_positive),
            from_pool(event_pool, n_event),
            np.random.randint(0, self._size, size=n_uniform),
        ]
        indices = np.concatenate(chunks).astype(np.int64, copy=False)
        np.random.shuffle(indices)
        return indices

    def sample_with_extras(
        self,
        batch_size: int,
        event_fraction: float = 0.0,
        positive_fraction: float = 0.0,
    ):
        """Sample and return ((states, actions, rewards, next_states, dones), extras).

        ``extras`` is a dict that includes ``"indices"`` plus any of
        ``"target_labels"``, ``"target_confidences"``, ``"rewards_decomp"``
        depending on which features were enabled at construction. Callers that
        only need the base tuple use ``sample()`` instead.
        """
        indices = self._sample_indices(batch_size, event_fraction, positive_fraction)
        base = (
            self.states[indices],
            self.actions[indices],
            self.rewards[indices],
            self.next_states[indices],
            self.dones[indices],
        )
        extras: dict = {"indices": indices}
        if self._aux_target_enabled:
            extras["target_labels"] = self.target_labels[indices]
            extras["target_confidences"] = self.target_confidences[indices]
        if self._reward_decomp_keys is not None:
            extras["rewards_decomp"] = {
                k: self.rewards_decomp[k][indices] for k in self._reward_decomp_keys
            }
        if self._teammate_state_dim > 0:
            extras["teammate_states"] = self.teammate_states[indices]
            extras["next_teammate_states"] = self.next_teammate_states[indices]
        return base, extras

    @property
    def size(self) -> int:
        return self._size

    def transform_actions_for_sac(self, actions: np.ndarray) -> np.ndarray:
        """Convert Java-recorded actions to the SAC critic/actor action domain.

        Continuous targets are recorded as raw pre-tanh model outputs and map to
        [-1, 1] via tanh. Movement binary targets are recorded after Java
        decoding as 0/1 decisions; SAC's tanh policy uses sign as the matching
        off/on domain, so map them to -1/+1.
        """
        if actions.shape[1] != self._action_dim:
            raise ValueError(f"actions has dim {actions.shape[1]}, expected {self._action_dim}")

        transformed = actions.astype(np.float32, copy=True)

        if np.any(self._continuous_action_mask):
            transformed[:, self._continuous_action_mask] = np.tanh(
                transformed[:, self._continuous_action_mask]
            )

        if np.any(self._binary_action_mask):
            binary_values = transformed[:, self._binary_action_mask]
            decoded_binary = (
                float(binary_values.min(initial=0.0)) >= -1e-6
                and float(binary_values.max(initial=0.0)) <= 1.0 + 1e-6
            )
            threshold = 0.5 if decoded_binary else 0.0
            transformed[:, self._binary_action_mask] = np.where(
                binary_values >= threshold,
                1.0,
                -1.0,
            )

        return transformed.astype(np.float32, copy=False)

    def __len__(self) -> int:
        return self._size


def _parse_npz_for_ingest(
    f: str,
    *,
    min_time: float,
    champion_experience_enabled: bool,
    aux_target_enabled: bool,
    reward_decomp_keys: tuple[str, ...] | None,
    teammate_state_dim: int,
    transform_actions: Callable[[np.ndarray], np.ndarray],
) -> dict[str, Any]:
    """Worker: load + parse + filter one NPZ file. Returns a status dict.

    Decompression dominates the wall-clock per file (~400 ms for a 6 MB
    deflate-compressed batch). Running this in a worker thread lets
    ``ingest_npz_files`` parallelise N files at once while the main thread
    still owns ``buffer.add_batch`` (the only mutation point on shared state).
    Action transform is included here because it is pure numpy and would
    otherwise re-serialise the parsed payload on the main thread.

    Statuses:
      "ok"         — parsed + filtered, ready for add_batch.
      "skip_old"   — older than max_age window, drop without ingesting.
      "skip_champ" — all-champion file fast-path (no decompression of large arrays).
      "skip_empty" — became empty after mixed-role filter.
      "error"      — load/parse failure; file should be removed.
    """
    try:
        if os.path.getmtime(f) < min_time:
            return {"status": "skip_old"}
        data = np.load(f)
        try:
            keys = set(data.files)
            has_role = ("policy_role.npy" in keys) or ("policy_role" in keys)

            # Champion fast-path: read ONLY policy_role first. NPZ files
            # from a champion-bot are 100% role=1 — decompressing the (much
            # larger) state/action/next_state arrays just to throw them
            # away is wasted work that pegs the worker thread and starves
            # the main SAC loop of training batches.
            if has_role and not champion_experience_enabled:
                role_key = "policy_role.npy" if "policy_role.npy" in keys else "policy_role"
                role = data[role_key]
                if len(role) > 0 and (role >= 0.5).all():
                    return {"status": "skip_champ", "champ_dropped": int(len(role))}

            s = data["states.npy"] if "states.npy" in keys else data["states"]
            a = data["actions.npy"] if "actions.npy" in keys else data["actions"]
            r = data["rewards.npy"] if "rewards.npy" in keys else data["rewards"]
            ns = data["next_states.npy"] if "next_states.npy" in keys else data["next_states"]
            d = data["dones.npy"] if "dones.npy" in keys else data["dones"]

            # Joint VR+shooting commitment-3 optional fields. Loading is
            # config-driven: when the buffer was constructed with aux/decomp
            # tracking, these NPZ keys are REQUIRED (CLAUDE.md: missing
            # config-driven keys crash, no silent skip).
            aux_labels = None
            aux_confidences = None
            if aux_target_enabled:
                label_key = _resolve_npz_key(keys, "target_label")
                conf_key = _resolve_npz_key(keys, "target_confidence")
                if label_key is None or conf_key is None:
                    raise RuntimeError(
                        f"NPZ file {f} missing aux target keys (need target_label "
                        f"+ target_confidence); buffer has aux_target_enabled=True"
                    )
                aux_labels = data[label_key]
                aux_confidences = data[conf_key]

            decomp = None
            if reward_decomp_keys is not None:
                decomp = {}
                for skill_key in reward_decomp_keys:
                    npz_key = _resolve_npz_key(keys, f"reward_{skill_key}")
                    if npz_key is None:
                        raise RuntimeError(
                            f"NPZ file {f} missing reward_{skill_key} key; "
                            f"buffer has reward_decomp_keys={list(reward_decomp_keys)}"
                        )
                    decomp[skill_key] = data[npz_key]

            tm = None
            ntm = None
            if teammate_state_dim > 0:
                tm_key = _resolve_npz_key(keys, "teammate_state")
                ntm_key = _resolve_npz_key(keys, "next_teammate_state")
                if tm_key is None or ntm_key is None:
                    raise RuntimeError(
                        f"NPZ file {f} missing teammate state keys (need "
                        f"teammate_state + next_teammate_state); buffer has "
                        f"teammate_state_dim={teammate_state_dim}"
                    )
                tm = data[tm_key]
                ntm = data[ntm_key]
                if tm.shape[1] != teammate_state_dim:
                    raise RuntimeError(
                        f"NPZ file {f} teammate_state width {tm.shape[1]} "
                        f"!= buffer.teammate_state_dim {teammate_state_dim}"
                    )
                if ntm.shape[1] != teammate_state_dim:
                    raise RuntimeError(
                        f"NPZ file {f} next_teammate_state width {ntm.shape[1]} "
                        f"!= buffer.teammate_state_dim {teammate_state_dim}"
                    )

            # Mixed-role file (rare): per-row filter when champion experience
            # is disabled. Skipped entirely when champion_experience_enabled=True;
            # champion rows then join current rows in the buffer.
            champ_dropped_rows = 0
            if has_role and not champion_experience_enabled:
                role_key2 = "policy_role.npy" if "policy_role.npy" in keys else "policy_role"
                role = data[role_key2]
                mask = role < 0.5
                if not mask.all():
                    champ_dropped_rows = int((~mask).sum())
                    s = s[mask]; a = a[mask]; r = r[mask]
                    ns = ns[mask]; d = d[mask]
                    if aux_labels is not None:
                        aux_labels = aux_labels[mask]
                        aux_confidences = aux_confidences[mask]
                    if decomp is not None:
                        decomp = {k: v[mask] for k, v in decomp.items()}
                    if tm is not None:
                        tm = tm[mask]
                        ntm = ntm[mask]
        finally:
            data.close()

        if len(s) == 0:
            return {"status": "skip_empty", "champ_dropped": champ_dropped_rows}

        a = transform_actions(a)
        return {
            "status": "ok",
            "champ_dropped": champ_dropped_rows,
            "s": s, "a": a, "r": r, "ns": ns, "d": d,
            "aux_labels": aux_labels,
            "aux_confidences": aux_confidences,
            "decomp": decomp,
            "tm": tm,
            "ntm": ntm,
        }
    except Exception as exc:  # noqa: BLE001 - propagate any parse failure as a tagged result
        return {"status": "error", "exc_repr": f"{type(exc).__name__}: {exc}"}
