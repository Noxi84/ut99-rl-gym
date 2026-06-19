"""Pure SAC gradient primitives.

Loss-only functions — no optimizer calls, no logging, no regularizers.
Per-model trainers compose these with their own anchors / smoothness /
masks before calling backward + optimizer.step themselves.
"""
from __future__ import annotations

import numpy as np
import torch
import torch.nn.functional as F

from train.rl.shared.sac_core.config import SACConfig
from train.rl.shared.sac_core.networks import sample_action, sample_action_with_log_prob_per_dim


def _reward_head_weight(cfg: SACConfig, key: str) -> float:
    weights = getattr(cfg, "reward_head_weights", None)
    if not weights:
        return 1.0
    return float(weights.get(key, 1.0))


def compute_critic_loss(
    actor, q1, q2, target_q1, target_q2,
    log_std_param: torch.Tensor,
    temperature,
    states: torch.Tensor,
    actions: torch.Tensor,
    rewards: torch.Tensor,
    next_states: torch.Tensor,
    dones: torch.Tensor,
    cfg: SACConfig,
    rewards_decomp: dict[str, torch.Tensor] | None = None,
    teammate_states: torch.Tensor | None = None,
    next_teammate_states: torch.Tensor | None = None,
) -> torch.Tensor:
    """Twin-critic MSE against the entropy-regularised Bellman target.

    Dispatches on ``cfg.critic_mode``:

    - ``"single"`` (default, backwards-compat): scalar ``rewards`` and single-
      output critics ``q1, q2``. Identical math to pre-commitment-3 code path.
    - ``"multi_head"`` (joint VR+shooting commitment-3): per-skill ``rewards_decomp``
      dict and ``MultiHeadSACCritic`` instances. Each head_k has its own Bellman
      backup ``r_k + γ(1-d)(min(target_q1_k, target_q2_k) − α·log π_next)``;
      total loss = Σ_k MSE(q1_k, target_k) + MSE(q2_k, target_k). Entropy
      bonus is included per-head (matches single-mode handling where one Q
      absorbs the full α·log π term — keeps the per-head Q's interpretable
      as "expected discounted decomposed reward" without a num_heads divisor).

    Fase 2.5 CTDE: when ``cfg.ctde_mode != "off"`` (only with ``multi_head``)
    both ``teammate_states`` and ``next_teammate_states`` are REQUIRED and get
    concatenated to the self_state inside the multi-head MLPs. The actor is
    still self-only (sampled on ``next_states`` without teammate context) —
    decentralized execution stays intact.

    Target Q is clamped to [-50, 50] so a runaway value head cannot drag
    the critic into divergence. Normalised rewards are ~[-3, 3] so this
    bound is far above realistic Q-magnitudes.
    """
    if cfg.critic_mode == "single":
        if cfg.ctde_mode != "off":
            raise RuntimeError(
                "cfg.ctde_mode != 'off' requires cfg.critic_mode='multi_head' "
                "(CTDE critic joint state is only wired through the multi-head path)"
            )
        with torch.no_grad():
            next_action, next_log_prob = sample_action(
                actor, next_states, log_std_param, cfg.log_std_min, cfg.log_std_max,
            )
            target_q1_val = target_q1(next_states, next_action)
            target_q2_val = target_q2(next_states, next_action)
            target_q = torch.min(target_q1_val, target_q2_val) - temperature.alpha * next_log_prob
            target_q = target_q.clamp(-50, 50)
            target_value = rewards + cfg.gamma * (1 - dones) * target_q

        q1_val = q1(states, actions)
        q2_val = q2(states, actions)
        return F.mse_loss(q1_val, target_value) + F.mse_loss(q2_val, target_value)

    if cfg.critic_mode == "multi_head":
        if cfg.reward_decomp_keys is None:
            raise RuntimeError(
                "compute_critic_loss(critic_mode='multi_head') requires cfg.reward_decomp_keys"
            )
        if rewards_decomp is None:
            raise RuntimeError(
                "compute_critic_loss(critic_mode='multi_head') requires rewards_decomp dict; got None"
            )
        head_keys = list(cfg.reward_decomp_keys)
        missing = [k for k in head_keys if k not in rewards_decomp]
        if missing:
            raise RuntimeError(
                f"rewards_decomp missing keys {missing}; expected {head_keys}"
            )

        ctde_active = cfg.ctde_mode != "off"
        if ctde_active and (teammate_states is None or next_teammate_states is None):
            raise RuntimeError(
                f"cfg.ctde_mode={cfg.ctde_mode!r} requires teammate_states and "
                "next_teammate_states tensors; got None"
            )
        if not ctde_active and (
            teammate_states is not None or next_teammate_states is not None
        ):
            raise RuntimeError(
                "cfg.ctde_mode='off' but teammate_states tensors were passed — "
                "wiring mismatch"
            )

        with torch.no_grad():
            next_action, next_log_prob = sample_action(
                actor, next_states, log_std_param, cfg.log_std_min, cfg.log_std_max,
            )
            if ctde_active:
                target_q1_dict = target_q1(next_states, next_action, next_teammate_states)
                target_q2_dict = target_q2(next_states, next_action, next_teammate_states)
            else:
                target_q1_dict = target_q1(next_states, next_action)
                target_q2_dict = target_q2(next_states, next_action)
            target_values = {}
            for k in head_keys:
                tq_min = torch.min(target_q1_dict[k], target_q2_dict[k])
                tq = (tq_min - temperature.alpha * next_log_prob).clamp(-50, 50)
                target_values[k] = rewards_decomp[k] + cfg.gamma * (1 - dones) * tq

        if ctde_active:
            q1_dict = q1(states, actions, teammate_states)
            q2_dict = q2(states, actions, teammate_states)
        else:
            q1_dict = q1(states, actions)
            q2_dict = q2(states, actions)
        total = q1_dict[head_keys[0]].new_zeros(())
        for k in head_keys:
            total = total + F.mse_loss(q1_dict[k], target_values[k])
            total = total + F.mse_loss(q2_dict[k], target_values[k])
        return total

    raise ValueError(f"Unknown cfg.critic_mode={cfg.critic_mode!r}")


def compute_sac_actor_loss(
    actor, q1, q2,
    log_std_param: torch.Tensor,
    temperature,
    states: torch.Tensor,
    cfg: SACConfig,
    teammate_states: torch.Tensor | None = None,
) -> tuple[torch.Tensor, torch.Tensor]:
    """Standard SAC actor objective: α · log π(a|s) − min(Q1, Q2).

    Dispatches on ``cfg.critic_mode``:

    - ``"single"``: ``q1(s,a), q2(s,a) → Tensor[B]``; loss = E[α·logπ − min(Q1,Q2)].
    - ``"multi_head"``: q's return ``dict[head_key, Tensor[B]]``; loss uses the
      weighted sum over heads of min(Q1_k, Q2_k). α·log π is added once (not
      per head), so the policy's entropy budget remains a single scalar; the
      per-head Q's provide independent gradient paths into the actor for each
      skill's reward.

    Fase 2.5 CTDE: when ``cfg.ctde_mode != "off"`` the critic Q's are evaluated
    on ``(states, action_new, teammate_states)`` so the policy-gradient flows
    through joint-state Q. The actor itself still samples from ``self_state``
    only — decentralized execution at runtime stays intact.

    Returns (loss, log_prob_new) — the log-prob is exposed so per-model
    trainers can reuse it for diagnostics without re-sampling.
    Per-model trainers add their own regularisers (BC anchor, log_std
    anchor, CAPS smoothness, …) on top of this base loss.
    """
    action_new, log_prob_new = sample_action(
        actor, states, log_std_param, cfg.log_std_min, cfg.log_std_max,
    )
    ctde_active = cfg.ctde_mode != "off"
    if ctde_active and teammate_states is None:
        raise RuntimeError(
            f"cfg.ctde_mode={cfg.ctde_mode!r} requires teammate_states; got None"
        )
    if not ctde_active and teammate_states is not None:
        raise RuntimeError(
            "cfg.ctde_mode='off' but teammate_states was passed to actor loss"
        )

    if cfg.critic_mode == "single":
        if ctde_active:
            raise RuntimeError(
                "cfg.ctde_mode != 'off' requires cfg.critic_mode='multi_head'"
            )
        q1_new = q1(states, action_new)
        q2_new = q2(states, action_new)
        q_new = torch.min(q1_new, q2_new)
    elif cfg.critic_mode == "multi_head":
        if cfg.reward_decomp_keys is None:
            raise RuntimeError(
                "compute_sac_actor_loss(critic_mode='multi_head') requires cfg.reward_decomp_keys"
            )
        if ctde_active:
            q1_dict = q1(states, action_new, teammate_states)
            q2_dict = q2(states, action_new, teammate_states)
        else:
            q1_dict = q1(states, action_new)
            q2_dict = q2(states, action_new)
        head_keys = list(cfg.reward_decomp_keys)
        q_new = q1_dict[head_keys[0]].new_zeros(q1_dict[head_keys[0]].shape)
        for k in head_keys:
            q_new = q_new + _reward_head_weight(cfg, k) * torch.min(q1_dict[k], q2_dict[k])
    else:
        raise ValueError(f"Unknown cfg.critic_mode={cfg.critic_mode!r}")
    sac_loss = (temperature.alpha.detach() * log_prob_new - q_new).mean()
    return sac_loss, log_prob_new


def compute_aux_target_loss(
    target_logits: torch.Tensor,
    target_labels: torch.Tensor,
    target_confidences: torch.Tensor,
) -> torch.Tensor:
    """Confidence-weighted cross-entropy on the auxiliary target_index head.

    Mirrors the BC-trainer pattern (RLShootingTargetProjector confidence
    labels hit=1.0 / fire_no_hit=0.3 / non_fire=0.1 / masked=0.0). During
    SAC fine-tuning this loss anchors the TargetHead to the BC-learned
    target distribution so it does not drift while the policy learns
    yaw/pitch/fire from RL gradients (vr-shooting-sac-merge.md §4.3 option b).

    Samples with ``target_confidences == 0`` (masked / non-events) contribute
    zero gradient. Mean is normalised by sum of confidences so the loss
    magnitude is invariant to the masked-sample fraction.
    """
    if target_logits.dim() != 2:
        raise ValueError(f"target_logits must be [B, num_slots]; got shape {tuple(target_logits.shape)}")
    if target_labels.dim() != 1 or target_labels.shape[0] != target_logits.shape[0]:
        raise ValueError(
            f"target_labels must be [B] matching target_logits[B,*]; "
            f"got {tuple(target_labels.shape)} vs {tuple(target_logits.shape)}"
        )
    if target_confidences.shape != target_labels.shape:
        raise ValueError(
            f"target_confidences must match target_labels shape; got "
            f"{tuple(target_confidences.shape)} vs {tuple(target_labels.shape)}"
        )
    per_sample = F.cross_entropy(target_logits, target_labels, reduction="none")
    weight_sum = target_confidences.sum().clamp_min(1e-6)
    return (per_sample * target_confidences).sum() / weight_sum


def compute_temperature_loss(
    actor,
    log_std_param: torch.Tensor,
    temperature,
    states: torch.Tensor,
    cfg: SACConfig,
) -> torch.Tensor:
    """Auto-α update objective.

    Legacy models use one scalar target entropy on the summed action log-prob.
    Joint models may provide per-dim targets so steering dims and binary fire
    dims do not share a misleading "-1 per dim" target.
    """
    with torch.no_grad():
        target_entropy_per_dim = getattr(cfg, "target_entropy_per_dim", None)
        if target_entropy_per_dim is not None:
            _, _, log_prob_per_dim = sample_action_with_log_prob_per_dim(
                actor, states, log_std_param, cfg.log_std_min, cfg.log_std_max,
            )
            target = torch.as_tensor(
                target_entropy_per_dim,
                dtype=log_prob_per_dim.dtype,
                device=log_prob_per_dim.device,
            )
            entropy_error = (log_prob_per_dim + target.view(1, -1)).sum(dim=-1)
        else:
            _, log_prob_for_temp = sample_action(
                actor, states, log_std_param, cfg.log_std_min, cfg.log_std_max,
            )
            entropy_error = log_prob_for_temp + cfg.target_entropy
    return -(temperature.log_alpha * entropy_error.detach()).mean()


def clamp_temperature(temperature, cfg: SACConfig) -> None:
    """In-place clamp on log_alpha. Bounds α to [temperature_min, temperature_max]
    every step regardless of auto_temperature — without an upper clamp the
    auto-tuner can run away when log_std is itself clamped (target entropy
    becomes unreachable, log_alpha is pushed up unboundedly, critic explodes).
    """
    with torch.no_grad():
        min_log_alpha = np.log(max(cfg.temperature_min, 1e-6))
        max_log_alpha = np.log(max(cfg.temperature_max, cfg.temperature_min, 1e-6))
        temperature.log_alpha.clamp_(min=min_log_alpha, max=max_log_alpha)
