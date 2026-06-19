from __future__ import annotations

import torch

from train.rl.rl_pawn.trainSAC.training_loop import _compute_aux_actor_loss


class _TargetOnlyActor(torch.nn.Module):
    def __init__(self, logits: list[float], *, trainable: bool = True):
        super().__init__()
        value = torch.tensor(logits, dtype=torch.float32)
        if trainable:
            self.logits = torch.nn.Parameter(value)
        else:
            self.register_buffer("logits", value)

    def forward_with_target(self, states: torch.Tensor):
        batch = states.shape[0]
        action = states.new_zeros((batch, 10))
        return action, self.logits.unsqueeze(0).expand(batch, -1)


def test_aux_target_ignores_low_confidence_fallback_labels_and_uses_bc_kl() -> None:
    actor = _TargetOnlyActor([4.0, -1.0, -1.0, -1.0, -1.0])
    bc_actor = _TargetOnlyActor([0.0, 1.0, 1.0, 0.0, 0.0], trainable=False)
    states = torch.zeros((8, 3))
    labels = torch.zeros(8, dtype=torch.long)
    confidences = torch.full((8,), 0.1)

    loss, components = _compute_aux_actor_loss(
        actor, bc_actor, states,
        target_labels=labels,
        target_confidences=confidences,
        aux_target_alpha=1.0,
    )

    assert "aux_target_ce" not in components
    assert components["aux_target_ce_skipped_low_conf"] == 1.0
    assert components["aux_target_kl_to_bc"] > 0.0
    assert loss.item() > 0.0

    loss.backward()
    assert actor.logits.grad is not None
    assert torch.isfinite(actor.logits.grad).all()


def test_aux_target_combines_high_confidence_ce_with_bc_kl() -> None:
    actor = _TargetOnlyActor([0.0, 0.0, 0.0, 0.0, 0.0])
    bc_actor = _TargetOnlyActor([0.0, 0.0, 0.0, 0.0, 0.0], trainable=False)
    states = torch.zeros((4, 3))
    labels = torch.tensor([2, 2, 0, 1], dtype=torch.long)
    confidences = torch.tensor([1.0, 0.3, 0.1, 0.0], dtype=torch.float32)

    loss, components = _compute_aux_actor_loss(
        actor, bc_actor, states,
        target_labels=labels,
        target_confidences=confidences,
        aux_target_alpha=1.0,
    )

    assert components["aux_target_ce"] > 0.0
    assert "aux_target_kl_to_bc" in components
    assert loss.item() > 0.0
