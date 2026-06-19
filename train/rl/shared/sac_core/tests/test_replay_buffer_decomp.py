"""ReplayBuffer — aux_target + reward_decomp parallel-array preservation.

Verifieert:
- Buffer met decomp/aux maakt extra arrays + preserveert ze index-synchroon
  met state/action/reward.
- add_batch zonder de extras (terwijl feature aan staat) crashed met
  duidelijke ValueError (CLAUDE.md: no silent fallbacks).
- sample_with_extras() retourneert dezelfde indices voor alle parallel arrays.
- Wrap-around blijft consistent voor alle parallel arrays.
"""
from __future__ import annotations

import numpy as np
import pytest

from train.rl.shared.sac_core.replay_buffer import ReplayBuffer


DECOMP_KEYS = ["view", "pitch", "fire", "altFire"]


def _make_full_buffer(capacity=64):
    return ReplayBuffer(
        capacity=capacity,
        state_dim=6,
        action_dim=4,
        aux_target_enabled=True,
        reward_decomp_keys=DECOMP_KEYS,
    )


def _make_batch(n, state_dim=6, action_dim=4):
    rng = np.random.default_rng(seed=n)
    s = rng.standard_normal((n, state_dim)).astype(np.float32)
    a = rng.standard_normal((n, action_dim)).astype(np.float32)
    r = rng.standard_normal(n).astype(np.float32)
    ns = rng.standard_normal((n, state_dim)).astype(np.float32)
    d = np.zeros(n, dtype=np.float32)
    labels = rng.integers(0, 5, size=n).astype(np.int64)
    conf = rng.uniform(0.1, 1.0, size=n).astype(np.float32)
    decomp = {k: rng.standard_normal(n).astype(np.float32) for k in DECOMP_KEYS}
    return s, a, r, ns, d, labels, conf, decomp


def test_backwards_compat_buffer_has_no_decomp_arrays():
    """Default constructor (no kwargs) gives the legacy buffer — extras are None."""
    buf = ReplayBuffer(capacity=16, state_dim=4, action_dim=2)
    assert buf.aux_target_enabled is False
    assert buf.reward_decomp_keys is None
    assert buf.target_labels is None
    assert buf.target_confidences is None
    assert buf.rewards_decomp is None

    s = np.zeros((3, 4), dtype=np.float32)
    a = np.zeros((3, 2), dtype=np.float32)
    r = np.zeros(3, dtype=np.float32)
    ns = np.zeros((3, 4), dtype=np.float32)
    d = np.zeros(3, dtype=np.float32)
    buf.add_batch(s, a, r, ns, d)
    assert buf.size == 3
    out = buf.sample(2)
    assert len(out) == 5, "legacy sample() must remain a 5-tuple"


def test_full_buffer_allocates_extra_arrays():
    buf = _make_full_buffer(capacity=32)
    assert buf.aux_target_enabled is True
    assert buf.reward_decomp_keys == tuple(DECOMP_KEYS)
    assert buf.target_labels.shape == (32,)
    assert buf.target_confidences.shape == (32,)
    assert buf.rewards_decomp is not None
    for k in DECOMP_KEYS:
        assert buf.rewards_decomp[k].shape == (32,)


def test_add_batch_requires_aux_when_enabled():
    buf = _make_full_buffer()
    s, a, r, ns, d, _, _, decomp = _make_batch(5)
    with pytest.raises(ValueError, match="aux_target_enabled"):
        buf.add_batch(s, a, r, ns, d, rewards_decomp=decomp)


def test_add_batch_requires_rewards_decomp_when_enabled():
    buf = _make_full_buffer()
    s, a, r, ns, d, labels, conf, _ = _make_batch(5)
    with pytest.raises(ValueError, match="reward_decomp_keys"):
        buf.add_batch(s, a, r, ns, d, target_labels=labels, target_confidences=conf)


def test_add_batch_rejects_decomp_with_missing_key():
    buf = _make_full_buffer()
    s, a, r, ns, d, labels, conf, decomp = _make_batch(5)
    incomplete = {k: decomp[k] for k in DECOMP_KEYS[:2]}
    with pytest.raises(ValueError, match="missing keys"):
        buf.add_batch(s, a, r, ns, d,
                      target_labels=labels, target_confidences=conf,
                      rewards_decomp=incomplete)


def test_sample_with_extras_preserves_parallel_arrays():
    """All parallel arrays must reflect the same sample-indices."""
    buf = _make_full_buffer(capacity=64)
    s, a, r, ns, d, labels, conf, decomp = _make_batch(20)
    buf.add_batch(s, a, r, ns, d,
                  target_labels=labels, target_confidences=conf,
                  rewards_decomp=decomp)
    assert buf.size == 20

    base, extras = buf.sample_with_extras(batch_size=10)
    states, actions, rewards, next_states, dones = base
    indices = extras["indices"]

    # Index-synchronous: extras[idx] == original[indices[idx]] for every parallel array.
    np.testing.assert_array_equal(extras["target_labels"], labels[indices])
    np.testing.assert_array_equal(extras["target_confidences"], conf[indices])
    for k in DECOMP_KEYS:
        np.testing.assert_array_equal(extras["rewards_decomp"][k], decomp[k][indices])

    # Sample is consistent with stored rewards.
    np.testing.assert_array_equal(rewards, r[indices])


def test_event_priority_sampling_enriches_fire_feedback_rows():
    """Priority sampling reserves batch slots for sparse fire/altFire feedback."""
    buf = ReplayBuffer(
        capacity=128,
        state_dim=6,
        action_dim=4,
        aux_target_enabled=True,
        reward_decomp_keys=DECOMP_KEYS,
        event_priority_reward_keys=["fire", "altFire"],
        event_priority_action_indices=[3],
    )
    s, a, r, ns, d, labels, conf, decomp = _make_batch(100)
    a[:] = -1.0
    for key in DECOMP_KEYS:
        decomp[key][:] = 0.0

    decomp["fire"][0:5] = 1.0
    decomp["altFire"][5:10] = -1.0
    a[10:20, 3] = 1.0

    buf.add_batch(
        s, a, r, ns, d,
        target_labels=labels,
        target_confidences=conf,
        rewards_decomp=decomp,
    )
    assert int(buf.priority_positive_events[:buf.size].sum()) == 5
    assert int(buf.priority_events[:buf.size].sum()) == 20

    np.random.seed(123)
    _base, extras = buf.sample_with_extras(
        batch_size=64,
        event_fraction=0.75,
        positive_fraction=0.25,
    )
    indices = extras["indices"]

    assert int(buf.priority_positive_events[indices].sum()) >= 16
    assert int(buf.priority_events[indices].sum()) >= 48


def test_wrap_around_preserves_extras():
    """When add_batch wraps the circular buffer, extras wrap in lock-step."""
    capacity = 10
    buf = _make_full_buffer(capacity=capacity)

    # First add: 7 transitions — no wrap.
    s1, a1, r1, ns1, d1, l1, c1, dec1 = _make_batch(7)
    buf.add_batch(s1, a1, r1, ns1, d1,
                  target_labels=l1, target_confidences=c1, rewards_decomp=dec1)

    # Second add: 6 transitions. write_idx=7, capacity=10, so wrap:
    #   first = 10 - 7 = 3 → slots 7,8,9 receive batch2[0..2]
    #   rest  = 6 - 3 = 3 → slots 0,1,2 receive batch2[3..5]
    # Slots 3..6 keep batch1[3..6]; slots 0..2 from batch1 are overwritten.
    s2, a2, r2, ns2, d2, l2, c2, dec2 = _make_batch(6)
    buf.add_batch(s2, a2, r2, ns2, d2,
                  target_labels=l2, target_confidences=c2, rewards_decomp=dec2)
    assert buf.size == capacity

    np.testing.assert_array_equal(buf.target_labels[0:3], l2[3:6])
    np.testing.assert_array_equal(buf.target_labels[3:7], l1[3:7])
    np.testing.assert_array_equal(buf.target_labels[7:10], l2[0:3])
    np.testing.assert_array_equal(buf.target_confidences[0:3], c2[3:6])
    np.testing.assert_array_equal(buf.target_confidences[3:7], c1[3:7])
    np.testing.assert_array_equal(buf.target_confidences[7:10], c2[0:3])
    for k in DECOMP_KEYS:
        np.testing.assert_array_equal(buf.rewards_decomp[k][0:3], dec2[k][3:6])
        np.testing.assert_array_equal(buf.rewards_decomp[k][3:7], dec1[k][3:7])
        np.testing.assert_array_equal(buf.rewards_decomp[k][7:10], dec2[k][0:3])


def test_reward_decomp_keys_uniqueness_enforced():
    with pytest.raises(ValueError, match="unique"):
        ReplayBuffer(
            capacity=16, state_dim=4, action_dim=2,
            reward_decomp_keys=["view", "view"],
        )


def test_reward_decomp_keys_non_empty_when_provided():
    with pytest.raises(ValueError, match="non-empty"):
        ReplayBuffer(
            capacity=16, state_dim=4, action_dim=2,
            reward_decomp_keys=[],
        )
