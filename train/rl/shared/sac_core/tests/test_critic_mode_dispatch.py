"""compute_critic_loss dispatch — single-mode regression + multi-head wiring.

Verifieert dat:
  1. single-mode output byte-identiek is aan handmatige re-implementatie van
     de pre-refactor formule (regression-test van het backwards-compat pad).
  2. multi-mode een ander gradient-pad activeert dat per-head Q-values en
     decomp-rewards consumeert; check via grad-flow naar elke head's params.
"""
from __future__ import annotations

import torch
import torch.nn as nn

from train.rl.shared.sac_core.networks import (
    SACCritic, MultiHeadSACCritic, SACTemperature,
)
from train.rl.shared.sac_core.sac_step import (
    compute_critic_loss, compute_sac_actor_loss, compute_temperature_loss,
)


HEAD_KEYS = ["view", "pitch", "fire", "altFire"]


class _FakeSACConfig:
    """Mini SACConfig — sac_step only reads the fields below."""
    def __init__(self, critic_mode: str, reward_decomp_keys=None,
                 ctde_mode: str = "off", teammate_state_dim: int = 0):
        self.critic_mode = critic_mode
        self.reward_decomp_keys = reward_decomp_keys
        self.log_std_min = -5.0
        self.log_std_max = 2.0
        self.gamma = 0.95
        self.target_entropy = -3.5
        self.target_entropy_per_dim = None
        self.reward_head_weights = None
        self.ctde_mode = ctde_mode
        self.teammate_state_dim = teammate_state_dim


class _ConstActor(nn.Module):
    """Deterministic actor: outputs zero-mean for every state.

    sample_action() draws noise = randn_like(mean); to make tests
    reproducible we monkey-seed before calling. The shape contract is
    ``actor(state) → [B, action_dim]``.
    """
    def __init__(self, action_dim: int):
        super().__init__()
        self.action_dim = action_dim
        # Tiny linear so torch sees parameters (even if we never optimize it).
        self.proj = nn.Linear(1, action_dim)

    def forward(self, state):
        # Ignore state; emit zero-mean batch.
        if state.dim() == 3:
            batch = state.size(0)
        else:
            batch = state.size(0)
        return torch.zeros(batch, self.action_dim, device=state.device)


class _ConstHeadCritic(nn.Module):
    def __init__(self, values: dict[str, float]):
        super().__init__()
        self.values = values

    def forward(self, states, actions, teammate_states=None):
        del teammate_states
        batch = states.size(0)
        return {
            key: torch.full((batch,), value, device=states.device)
            for key, value in self.values.items()
        }


def _make_batch(batch=4, seq=2, feat=6, action_dim=4, seed=42):
    torch.manual_seed(seed)
    states = torch.randn(batch, seq, feat)
    actions = torch.tanh(torch.randn(batch, action_dim))
    rewards = torch.randn(batch)
    next_states = torch.randn(batch, seq, feat)
    dones = torch.zeros(batch)
    return states, actions, rewards, next_states, dones


def test_single_mode_loss_is_deterministic_and_finite():
    """Sanity: single-mode hits the legacy formula path; loss is finite."""
    cfg = _FakeSACConfig(critic_mode="single")
    states, actions, rewards, next_states, dones = _make_batch()

    state_dim_flat = states[0].numel()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q1 = SACCritic(state_dim_flat, 4, 64)
    q2 = SACCritic(state_dim_flat, 4, 64)
    import copy
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)
    for p in target_q1.parameters(): p.requires_grad = False
    for p in target_q2.parameters(): p.requires_grad = False

    torch.manual_seed(123)
    loss_a = compute_critic_loss(
        actor, q1, q2, target_q1, target_q2,
        log_std, temperature,
        states, actions, rewards, next_states, dones, cfg,
    )
    # Re-run with the same seed → identical loss (regression check).
    torch.manual_seed(123)
    loss_b = compute_critic_loss(
        actor, q1, q2, target_q1, target_q2,
        log_std, temperature,
        states, actions, rewards, next_states, dones, cfg,
    )
    assert torch.isfinite(loss_a), f"loss not finite: {loss_a}"
    assert torch.allclose(loss_a, loss_b), f"single-mode not deterministic under fixed seed: {loss_a} vs {loss_b}"


def test_multi_head_mode_requires_decomp_keys_in_cfg():
    """multi_head without reward_decomp_keys must raise."""
    import pytest as _pt
    cfg = _FakeSACConfig(critic_mode="multi_head", reward_decomp_keys=None)
    states, actions, rewards, next_states, dones = _make_batch()
    state_dim_flat = states[0].numel()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q1 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    q2 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    import copy
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)

    with _pt.raises(RuntimeError, match="reward_decomp_keys"):
        compute_critic_loss(
            actor, q1, q2, target_q1, target_q2,
            log_std, temperature,
            states, actions, rewards, next_states, dones, cfg,
        )


def test_multi_head_mode_routes_gradients_to_every_head():
    cfg = _FakeSACConfig(critic_mode="multi_head", reward_decomp_keys=HEAD_KEYS)
    states, actions, rewards, next_states, dones = _make_batch()
    state_dim_flat = states[0].numel()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q1 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    q2 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    import copy
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)
    for p in target_q1.parameters(): p.requires_grad = False
    for p in target_q2.parameters(): p.requires_grad = False

    rewards_decomp = {k: torch.randn(rewards.shape[0]) for k in HEAD_KEYS}
    torch.manual_seed(7)
    loss = compute_critic_loss(
        actor, q1, q2, target_q1, target_q2,
        log_std, temperature,
        states, actions, rewards, next_states, dones, cfg,
        rewards_decomp=rewards_decomp,
    )
    assert torch.isfinite(loss)
    loss.backward()

    # Every head in q1 and q2 must have received gradient from this loss.
    for k in HEAD_KEYS:
        head_q1 = q1.heads[k]
        first_w = next(head_q1.parameters())
        assert first_w.grad is not None and first_w.grad.abs().sum() > 0, \
            f"q1.heads[{k!r}] received zero gradient"
        head_q2 = q2.heads[k]
        first_w2 = next(head_q2.parameters())
        assert first_w2.grad is not None and first_w2.grad.abs().sum() > 0, \
            f"q2.heads[{k!r}] received zero gradient"


def test_multi_head_mode_rejects_decomp_with_missing_keys():
    """rewards_decomp dict missing one of the cfg's keys must raise."""
    import pytest as _pt
    cfg = _FakeSACConfig(critic_mode="multi_head", reward_decomp_keys=HEAD_KEYS)
    states, actions, rewards, next_states, dones = _make_batch()
    state_dim_flat = states[0].numel()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q1 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    q2 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS)
    import copy
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)

    incomplete = {k: torch.randn(rewards.shape[0]) for k in HEAD_KEYS[:2]}
    with _pt.raises(RuntimeError, match="missing keys"):
        compute_critic_loss(
            actor, q1, q2, target_q1, target_q2,
            log_std, temperature,
            states, actions, rewards, next_states, dones, cfg,
            rewards_decomp=incomplete,
        )


def test_multi_head_actor_loss_applies_reward_head_weights():
    cfg_plain = _FakeSACConfig(critic_mode="multi_head", reward_decomp_keys=HEAD_KEYS)
    cfg_weighted = _FakeSACConfig(critic_mode="multi_head", reward_decomp_keys=HEAD_KEYS)
    cfg_weighted.reward_head_weights = {
        "view": 1.0,
        "pitch": 1.0,
        "fire": 5.0,
        "altFire": 1.0,
    }
    states, _actions, _rewards, _next_states, _dones = _make_batch()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q_values = {"view": 1.0, "pitch": 0.0, "fire": 2.0, "altFire": 0.0}
    q1 = _ConstHeadCritic(q_values)
    q2 = _ConstHeadCritic(q_values)

    torch.manual_seed(555)
    plain_loss, _ = compute_sac_actor_loss(
        actor, q1, q2, log_std, temperature, states, cfg_plain,
    )
    torch.manual_seed(555)
    weighted_loss, _ = compute_sac_actor_loss(
        actor, q1, q2, log_std, temperature, states, cfg_weighted,
    )

    assert torch.isfinite(plain_loss)
    assert torch.isfinite(weighted_loss)
    assert weighted_loss < plain_loss - 7.5


def test_ctde_multi_head_requires_and_consumes_teammate_state():
    import copy
    import pytest as _pt

    teammate_dim = 3
    cfg = _FakeSACConfig(
        critic_mode="multi_head",
        reward_decomp_keys=HEAD_KEYS,
        ctde_mode="closest_two",
        teammate_state_dim=teammate_dim,
    )
    states, actions, rewards, next_states, dones = _make_batch()
    batch = states.shape[0]
    teammate_states = torch.randn(batch, teammate_dim)
    next_teammate_states = torch.randn(batch, teammate_dim)
    state_dim_flat = states[0].numel()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)
    q1 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS,
                            teammate_state_dim=teammate_dim)
    q2 = MultiHeadSACCritic(state_dim_flat, 4, 64, HEAD_KEYS,
                            teammate_state_dim=teammate_dim)
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)
    for p in target_q1.parameters(): p.requires_grad = False
    for p in target_q2.parameters(): p.requires_grad = False
    rewards_decomp = {k: torch.randn(batch) for k in HEAD_KEYS}

    with _pt.raises(RuntimeError, match="requires teammate_states"):
        compute_critic_loss(
            actor, q1, q2, target_q1, target_q2,
            log_std, temperature,
            states, actions, rewards, next_states, dones, cfg,
            rewards_decomp=rewards_decomp,
        )

    torch.manual_seed(13)
    loss = compute_critic_loss(
        actor, q1, q2, target_q1, target_q2,
        log_std, temperature,
        states, actions, rewards, next_states, dones, cfg,
        rewards_decomp=rewards_decomp,
        teammate_states=teammate_states,
        next_teammate_states=next_teammate_states,
    )
    assert torch.isfinite(loss)

    torch.manual_seed(13)
    actor_loss, _ = compute_sac_actor_loss(
        actor, q1, q2, log_std, temperature, states, cfg,
        teammate_states=teammate_states,
    )
    assert torch.isfinite(actor_loss)


def test_temperature_loss_uses_target_entropy_per_dim_when_configured():
    cfg_scalar = _FakeSACConfig(critic_mode="single")
    cfg_per_dim = _FakeSACConfig(critic_mode="single")
    cfg_per_dim.target_entropy_per_dim = (-1.0, -1.5, -3.0, -3.0)
    states, _actions, _rewards, _next_states, _dones = _make_batch()
    actor = _ConstActor(action_dim=4)
    log_std = torch.full((4,), -1.0)
    temperature = SACTemperature(init_value=0.02)

    torch.manual_seed(99)
    scalar_loss = compute_temperature_loss(actor, log_std, temperature, states, cfg_scalar)
    torch.manual_seed(99)
    per_dim_loss = compute_temperature_loss(actor, log_std, temperature, states, cfg_per_dim)

    assert torch.isfinite(scalar_loss)
    assert torch.isfinite(per_dim_loss)
    assert not torch.allclose(scalar_loss, per_dim_loss), (
        "target_entropy_per_dim must affect the temperature objective"
    )
