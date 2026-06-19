"""MultiHeadSACCritic — instantiation + per-head output shape."""
from __future__ import annotations

import torch

from train.rl.shared.sac_core.networks import (
    MultiHeadSACCritic,
    create_twin_critics_multi_head,
)


HEAD_KEYS = ["view", "pitch", "fire", "altFire"]


def test_multi_head_critic_returns_dict_with_all_keys():
    state_dim, action_dim, hidden = 32, 4, 64
    critic = MultiHeadSACCritic(state_dim, action_dim, hidden, HEAD_KEYS)
    batch = 5
    state = torch.randn(batch, state_dim)
    action = torch.randn(batch, action_dim)

    out = critic(state, action)

    assert isinstance(out, dict)
    assert set(out.keys()) == set(HEAD_KEYS), f"Missing/extra keys: {set(out) ^ set(HEAD_KEYS)}"
    for k in HEAD_KEYS:
        assert out[k].shape == (batch,), f"head {k} shape={tuple(out[k].shape)} != ({batch},)"


def test_multi_head_critic_accepts_seq_state():
    """State [B, T, F] gets flattened identically to single-head SACCritic."""
    state_dim_flat, action_dim, hidden = 24 * 10, 4, 64
    critic = MultiHeadSACCritic(state_dim_flat, action_dim, hidden, HEAD_KEYS)
    state_seq = torch.randn(3, 24, 10)
    action = torch.randn(3, action_dim)

    out = critic(state_seq, action)
    for k in HEAD_KEYS:
        assert out[k].shape == (3,)


def test_create_twin_critics_multi_head_freezes_target_params():
    q1, q2, target_q1, target_q2 = create_twin_critics_multi_head(
        state_dim=32, action_dim=4, hidden_size=64,
        head_keys=HEAD_KEYS, device=torch.device("cpu"),
    )
    for p in q1.parameters():
        assert p.requires_grad, "q1 params must be trainable"
    for p in q2.parameters():
        assert p.requires_grad, "q2 params must be trainable"
    for p in target_q1.parameters():
        assert not p.requires_grad, "target_q1 params must be frozen"
    for p in target_q2.parameters():
        assert not p.requires_grad, "target_q2 params must be frozen"


def test_multi_head_critic_rejects_empty_or_duplicate_keys():
    import pytest as _pt
    with _pt.raises(ValueError):
        MultiHeadSACCritic(32, 4, 64, [])
    with _pt.raises(ValueError):
        MultiHeadSACCritic(32, 4, 64, ["view", "view", "fire"])
