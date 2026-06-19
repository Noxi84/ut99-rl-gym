"""SAC network components: twin critics and learnable temperature.

The actor is the standard BCSequenceNetwork (LSTM + head → mean).
"""
from __future__ import annotations

import copy

import numpy as np
import torch
import torch.nn as nn

from train.model.bc_sequence_network import BCSequenceNetwork


class SACCritic(nn.Module):
    """Q-network: MLP on flattened state window + action → Q-value.

    Unlike the actor (LSTM-based), the critic uses a plain MLP on the
    flattened state window.  This avoids the LSTM hidden-state problem
    with off-policy replay: every (state_window, action) tuple is fully
    self-contained — no burn-in or hidden-state storage needed.
    """

    def __init__(self, state_dim: int, action_dim: int, hidden_size: int = 256):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(state_dim + action_dim, hidden_size),
            nn.LayerNorm(hidden_size),
            nn.GELU(),
            nn.Linear(hidden_size, hidden_size),
            nn.GELU(),
            nn.Linear(hidden_size, 1),
        )

    def forward(self, state: torch.Tensor, action: torch.Tensor) -> torch.Tensor:
        """Returns Q-value [B]. State: [B, seq_len, input_size] or [B, state_dim]."""
        if state.dim() == 3:
            state = state.reshape(state.size(0), -1)
        x = torch.cat([state, action], dim=-1)
        return self.net(x).squeeze(-1)


class SACTemperature(nn.Module):
    """Learnable entropy temperature α."""

    def __init__(self, init_value: float = 0.01):
        super().__init__()
        self.log_alpha = nn.Parameter(torch.tensor(np.log(init_value), dtype=torch.float32))

    @property
    def alpha(self) -> torch.Tensor:
        return self.log_alpha.exp()


def create_twin_critics(state_dim: int, action_dim: int,
                        hidden_size: int, device: torch.device):
    """Create Q1, Q2, target_Q1, target_Q2 (MLP critics)."""
    q1 = SACCritic(state_dim, action_dim, hidden_size).to(device)
    q2 = SACCritic(state_dim, action_dim, hidden_size).to(device)
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)
    # Freeze target networks
    for p in target_q1.parameters():
        p.requires_grad = False
    for p in target_q2.parameters():
        p.requires_grad = False
    return q1, q2, target_q1, target_q2


class MultiHeadSACCritic(nn.Module):
    """Per-skill Q-network bank (joint VR+shooting commitment-3 prep).

    One MLP per head_key, all sharing the same flat (state, action) input.
    The action input is the *full* flat action vector (e.g. 10-dim
    [moveDir_sin, moveDir_cos, dodge, bJump, bDuck, bIdle, yaw, pitch,
    fire, altFire]) — slicing per head would prevent each head from
    conditioning on the cross-skill action context (whether fire is on
    affects "what's a good yaw" and vice versa).

    Returns ``dict[head_key, Tensor[B]]`` — same flat shape per head as
    single-head ``SACCritic``. Per-head Bellman targets consume the
    matching reward_decomp[head_key] slice in
    ``compute_critic_loss(critic_mode='multi_head', ...)``.

    Fase 2.5 CTDE: when ``teammate_state_dim > 0`` the forward accepts an
    optional ``teammate_state`` tensor that is concatenated to the flat
    self_state before being fed through each head's MLP. The actor stays
    unchanged — this is purely a critic-side input expansion.
    """

    def __init__(self, state_dim: int, action_dim: int,
                 hidden_size: int, head_keys: list[str],
                 teammate_state_dim: int = 0):
        super().__init__()
        if not head_keys:
            raise ValueError("MultiHeadSACCritic requires at least one head_key")
        if len(set(head_keys)) != len(head_keys):
            raise ValueError(f"MultiHeadSACCritic head_keys must be unique, got {head_keys}")
        if teammate_state_dim < 0:
            raise ValueError(f"teammate_state_dim must be >= 0, got {teammate_state_dim}")
        self.head_keys = list(head_keys)
        self.teammate_state_dim = int(teammate_state_dim)
        input_dim = state_dim + teammate_state_dim + action_dim
        self.heads = nn.ModuleDict({
            key: nn.Sequential(
                nn.Linear(input_dim, hidden_size),
                nn.LayerNorm(hidden_size),
                nn.GELU(),
                nn.Linear(hidden_size, hidden_size),
                nn.GELU(),
                nn.Linear(hidden_size, 1),
            ) for key in head_keys
        })

    def forward(
        self,
        state: torch.Tensor,
        action: torch.Tensor,
        teammate_state: torch.Tensor | None = None,
    ) -> dict[str, torch.Tensor]:
        if state.dim() == 3:
            state = state.reshape(state.size(0), -1)
        if self.teammate_state_dim > 0:
            if teammate_state is None:
                raise RuntimeError(
                    "MultiHeadSACCritic was built with teammate_state_dim > 0; "
                    "forward requires teammate_state tensor"
                )
            if teammate_state.dim() == 3:
                teammate_state = teammate_state.reshape(teammate_state.size(0), -1)
            x = torch.cat([state, teammate_state, action], dim=-1)
        else:
            if teammate_state is not None:
                raise RuntimeError(
                    "MultiHeadSACCritic was built with teammate_state_dim == 0 but "
                    "forward received a teammate_state tensor"
                )
            x = torch.cat([state, action], dim=-1)
        return {key: self.heads[key](x).squeeze(-1) for key in self.head_keys}


def create_twin_critics_multi_head(state_dim: int, action_dim: int,
                                    hidden_size: int, head_keys: list[str],
                                    device: torch.device,
                                    teammate_state_dim: int = 0):
    """Create Q1, Q2, target_Q1, target_Q2 (per-head MLP critics).

    Parallel to ``create_twin_critics`` but each critic is a
    ``MultiHeadSACCritic`` returning ``dict[head_key, Tensor[B]]``. Used when
    ``cfg.critic_mode == 'multi_head'`` and reward_decomp_keys is set.

    When ``teammate_state_dim > 0`` (Fase 2.5 CTDE), every head's MLP gets a
    wider input layer accepting ``[self_state, teammate_state, action]``.
    """
    q1 = MultiHeadSACCritic(state_dim, action_dim, hidden_size, head_keys,
                            teammate_state_dim=teammate_state_dim).to(device)
    q2 = MultiHeadSACCritic(state_dim, action_dim, hidden_size, head_keys,
                            teammate_state_dim=teammate_state_dim).to(device)
    target_q1 = copy.deepcopy(q1)
    target_q2 = copy.deepcopy(q2)
    for p in target_q1.parameters():
        p.requires_grad = False
    for p in target_q2.parameters():
        p.requires_grad = False
    return q1, q2, target_q1, target_q2


def soft_update(target: nn.Module, source: nn.Module, tau: float):
    """Exponential moving average update: θ_target ← τ·θ + (1-τ)·θ_target."""
    for tp, sp in zip(target.parameters(), source.parameters()):
        tp.data.mul_(1 - tau).add_(sp.data, alpha=tau)


def sample_action_with_log_prob_per_dim(
    actor: BCSequenceNetwork,
    state: torch.Tensor,
    log_std: torch.Tensor,
    log_std_min: float = -5.0,
    log_std_max: float = 2.0,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    """Sample action from squashed Gaussian policy (tanh) with learnable log_std.

    Args:
        actor: outputs mean [B, action_dim]
        state: [B, seq_len, input_size]
        log_std: learnable [action_dim] parameter (clamped internally for stability)
        log_std_min/max: clamp bounds applied to log_std before exp().

    Returns: (action [B, action_dim], log_prob [B], log_prob_per_dim [B, action_dim])
    """
    mean = actor(state)  # [B, action_dim]
    clamped_log_std = log_std.clamp(log_std_min, log_std_max)
    std = clamped_log_std.exp()
    noise = torch.randn_like(mean)
    u = mean + noise * std
    action = torch.tanh(u)

    # Log-prob with tanh Jacobian correction. Keep the per-dim terms around
    # for joint policies whose entropy target differs between steering and
    # binary fire dimensions.
    log_prob_gauss = -0.5 * (noise ** 2 + 2 * clamped_log_std + np.log(2 * np.pi))
    log_det_jacobian = torch.log(1 - action ** 2 + 1e-6)
    log_prob_per_dim = log_prob_gauss - log_det_jacobian
    log_prob = log_prob_per_dim.sum(dim=-1)

    return action, log_prob, log_prob_per_dim


def sample_action(actor: BCSequenceNetwork, state: torch.Tensor,
                  log_std: torch.Tensor,
                  log_std_min: float = -5.0,
                  log_std_max: float = 2.0,
                  ) -> tuple[torch.Tensor, torch.Tensor]:
    """Sample action from squashed Gaussian policy (tanh) with learnable log_std.

    Returns: (action [B, action_dim], log_prob [B])
    """
    action, log_prob, _ = sample_action_with_log_prob_per_dim(
        actor, state, log_std, log_std_min, log_std_max,
    )
    return action, log_prob
