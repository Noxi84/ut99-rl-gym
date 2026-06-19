"""Bootstrap joint VR+shooting SAC actor + Q-critics + temperature from a BC
checkpoint produced by Fase 2 (``train.rl.rl_pawn.trainBC``).

Owns the joint SAC checkpoint fallback ladder, BC baseline snapshot, and
in-flight DeltaGate metadata. Builds the joint actor with
``expose_target_index=True`` so the
auxiliary TargetHead is part of the bootstrap from the first step, and
dispatches the critic build between ``single`` and ``multi_head`` modes
(commitment 3 — pre-wired multi-head infra) based on ``cfg.critic_mode``.

CLAUDE.md preference: per-model SAC orchestration lives here (not in shared
kernel). Joint-specific bookkeeping like the TargetHead state-dict round-trip
or the multi-head critic init stays local; nothing in this module touches
``train/rl/shared/sac_core/``.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

from train.common.TrainerLogger import log_print
from train.model.bc_sequence_network import (
    BCSequenceNetwork, export_actor_onnx, load_compatible_state_dict,
)
from train.rl.shared.sac_core.checkpoint_io import strip_compile_prefix
from train.rl.shared.sac_core.networks import (
    SACTemperature, create_twin_critics, create_twin_critics_multi_head,
)


@dataclass
class JointBootstrapResult:
    """All optimisable state + run-resume metadata the training loop needs.

    Mirrors VR's ``BootstrapResult`` plus the joint extras:

    - ``q1, q2, target_q1, target_q2``: either ``SACCritic`` or
      ``MultiHeadSACCritic`` instances depending on ``cfg.critic_mode``.
    - ``critic_mode``: echoed back so the training loop can branch without
      re-reading config.
    - ``actor``: built with ``expose_target_index=True`` so
      ``forward_with_target()`` is available without re-instantiating.
    """
    actor: BCSequenceNetwork
    log_std_param: nn.Parameter
    q1: nn.Module
    q2: nn.Module
    target_q1: nn.Module
    target_q2: nn.Module
    temperature: SACTemperature
    actor_optimizer: torch.optim.Optimizer
    critic_optimizer: torch.optim.Optimizer
    temp_optimizer: torch.optim.Optimizer
    bc_actor: BCSequenceNetwork | None
    bc_log_std_anchor: torch.Tensor | None
    critic_mode: str

    global_step: int
    best_exported_return: float
    best_observed_return: float
    baseline_return: float | None
    last_export_step: int
    bootstrap_loaded: bool
    bootstrap_label: str
    metadata_loaded: bool


def _build_actor(cfg, device) -> BCSequenceNetwork:
    """BCSequenceNetwork with ``expose_target_index=True``. The aux TargetHead
    needs enemy slots in the input grouping — the joint features.json puts 5
    enemy slots in the union, so the head is always built (not gated by
    runtime config). The actor's forward(x) still returns just action_mean
    [B, action_dim]; forward_with_target(x) returns (action_mean, target_logits).
    """
    actor = BCSequenceNetwork(
        cfg.input_features, cfg.output_size, cfg.hidden_size, cfg.num_layers, cfg.dropout,
        player_hidden_dim=cfg.player_hidden_dim, player_embed_dim=cfg.player_embed_dim,
        map_embedding_capacity=cfg.map_embedding_capacity,
        map_embedding_dim=cfg.map_embedding_dim,
        expose_target_index=True,
    ).to(device)
    if actor.target_head is None:
        raise RuntimeError(
            "Joint actor was constructed with expose_target_index=True but the "
            "grouping yielded no enemy slots; BCSequenceNetwork suppressed the "
            "TargetHead. The joint features.json must keep 5 enemy slots in the "
            "union (categorie B.1 in feature-overlap-inventory.md)."
        )
    return actor


def _build_critics(cfg, device):
    """Dispatch on ``cfg.critic_mode`` (commitment 3 — pre-wired path).

    Fase 2.5 CTDE: when ``cfg.ctde_mode != "off"``, the multi-head critic is
    constructed with a wider input layer that consumes
    ``[self_state, teammate_state, action]``. The ``ctde_mode`` flag itself is
    validated by ``config_loader``; here we only forward the resulting
    ``teammate_state_dim``.
    """
    state_dim = cfg.seq_len * cfg.input_size
    if cfg.critic_mode == "single":
        if cfg.ctde_mode != "off":
            raise RuntimeError(
                "cfg.ctde_mode != 'off' requires cfg.critic_mode='multi_head'"
            )
        return create_twin_critics(state_dim, cfg.action_dim, cfg.hidden_size, device)
    if cfg.critic_mode == "multi_head":
        head_keys = list(cfg.reward_decomp_keys or ())
        if not head_keys:
            raise RuntimeError(
                "critic_mode='multi_head' requires reward_decomp_keys; "
                "config_loader.load_joint_sac_config should have raised earlier"
            )
        teammate_dim = cfg.teammate_state_dim if cfg.ctde_mode != "off" else 0
        return create_twin_critics_multi_head(
            state_dim, cfg.action_dim, cfg.hidden_size, head_keys, device,
            teammate_state_dim=teammate_dim,
        )
    raise ValueError(f"Unknown critic_mode={cfg.critic_mode!r}")


def _read_bc_log_std(bc_pt_path: Path, bc_baseline_pt: Path,
                     expected_shape, device, logger) -> torch.Tensor | None:
    """Read learned BC log_std from BC trainer's .pt or the SAC-side baseline.

    The joint BC trainer saves with ``gaussian_head=True`` so the BC ``.pt``
    carries ``log_std`` [10]. Without this anchor SAC starts from
    ``cfg.log_std_init`` (-1.0) on all dims regardless of what BC learned.
    """
    for candidate in [bc_pt_path, bc_baseline_pt]:
        if not candidate.exists():
            continue
        try:
            c = torch.load(candidate, map_location=device, weights_only=False)
            s = c.get("model_state_dict", {})
            if "log_std" in s and s["log_std"].shape == expected_shape:
                t = s["log_std"].to(device).clone().detach()
                log_print(logger, f"Read BC log_std from {candidate.name}: {t.tolist()}")
                return t
        except Exception as e:
            log_print(logger, f"Failed to read log_std from {candidate}: {e}")
    return None


def _load_bc_anchor(cfg, device, bc_baseline_pt: Path, logger):
    """Frozen BC actor used for MSE regularisation + action_bias anchor +
    aux-target distribution prior. None when bc_alpha == 0 OR the baseline is
    missing — every regularizer that references bc_actor must guard for None.
    """
    if cfg.bc_alpha <= 0 or not bc_baseline_pt.exists():
        return None
    bc_actor = BCSequenceNetwork(
        cfg.input_features, cfg.output_size, cfg.hidden_size, cfg.num_layers, cfg.dropout,
        player_hidden_dim=cfg.player_hidden_dim, player_embed_dim=cfg.player_embed_dim,
        map_embedding_capacity=cfg.map_embedding_capacity,
        map_embedding_dim=cfg.map_embedding_dim,
        expose_target_index=True,
    ).to(device)
    bc_ckpt = torch.load(bc_baseline_pt, map_location=device, weights_only=False)
    load_compatible_state_dict(bc_actor, bc_ckpt["model_state_dict"])
    bc_actor.eval()
    for p in bc_actor.parameters():
        p.requires_grad = False
    log_print(logger, f"BC anchor loaded (bc_alpha={cfg.bc_alpha})")
    return bc_actor


def bootstrap(
    cfg,
    device,
    mk: str,
    model_output_dir: Path,
    sac_ckpt_path: Path,
    bc_baseline_pt: Path,
    bc_baseline_onnx: str,
    logger,
) -> JointBootstrapResult:
    """Construct all SAC components and walk the bootstrap fallback ladder.

    Differs from VR's bootstrap in three ways:

    1. Actor uses ``expose_target_index=True`` so the BC TargetHead weights
       round-trip through the state-dict load (strict=True for SAC-best /
       SAC-current — they were saved by this trainer; relaxed for BC because
       it lacks the SAC-specific log_std parameter).
    2. Critic build dispatches on ``cfg.critic_mode``.
    3. Aux target alpha is exposed to the training loop via the result (no
       extra fields needed; the loop reads ``cfg.aux_target_alpha`` directly).

    Returns ``JointBootstrapResult``. Side-effects: writes
    ``bc_baseline.pt`` + ``bc_baseline.onnx`` on the first BC bootstrap, clamps
    loaded α inside [temperature_min, temperature_max].
    """
    actor = _build_actor(cfg, device)
    log_std_param = nn.Parameter(torch.full((cfg.action_dim,), cfg.log_std_init, device=device))
    q1, q2, target_q1, target_q2 = _build_critics(cfg, device)
    temperature = SACTemperature(cfg.temperature_init).to(device)

    actor_optimizer = torch.optim.AdamW(
        list(actor.parameters()) + [log_std_param], lr=cfg.lr_actor, weight_decay=1e-5,
    )
    critic_optimizer = torch.optim.AdamW(
        list(q1.parameters()) + list(q2.parameters()), lr=cfg.lr_critic, weight_decay=1e-5,
    )
    temp_optimizer = torch.optim.Adam([temperature.log_alpha], lr=cfg.lr_temperature)

    global_step = 0
    best_exported_return = -1e9
    best_observed_return = -1e9
    baseline_return = None
    bootstrap_loaded = False
    bootstrap_label = ""

    for ckpt_name, label, allow_missing in [
        (f"{mk}_sac_best.pt", "SAC best", False),
        (f"{mk}_sac.pt", "SAC current", False),
        (f"{mk}.pt", "BC", True),
    ]:
        ckpt_file = model_output_dir / ckpt_name
        if not ckpt_file.exists():
            continue
        try:
            ckpt = torch.load(ckpt_file, map_location=device, weights_only=False)
            ckpt_state = strip_compile_prefix(ckpt["model_state_dict"])
            missing, skipped = load_compatible_state_dict(actor, ckpt_state)
            if allow_missing:
                # BC saves include log_std (gaussian_head=True) — that's the
                # bootstrap source for log_std_param, not part of the actor.
                # `_joint_log_std` is a sidecar alias the joint BC trainer
                # attaches (trainer.py: model._joint_log_std = model.log_std)
                # for validation hooks; it serialises as a separate state-dict
                # key but points to the same tensor as `log_std`.
                missing = {k for k in missing if not k.startswith("value_head.")}
                skipped = [k for k in skipped if k not in ("log_std", "_joint_log_std")]
            if not missing and not skipped:
                global_step = ckpt.get("global_step", 0)
                log_print(logger, f"Bootstrapped actor from {label} ({ckpt_name}) step={global_step}")
                bootstrap_loaded = True
                bootstrap_label = label
                if "log_std" in ckpt_state and ckpt_state["log_std"].shape == log_std_param.shape:
                    log_std_param.data.copy_(ckpt_state["log_std"].to(device))
                    log_print(logger, f"Bootstrapped log_std from {label}: {log_std_param.data.tolist()}")
                if not bootstrap_label.startswith("SAC"):
                    log_print(logger,
                        f"Non-SAC bootstrap: resetting global_step from {global_step} to 0")
                    global_step = 0
                break
            else:
                log_print(logger,
                    f"State-dict mismatch for {label} ({ckpt_name}): "
                    f"missing={sorted(missing)[:10]}{'...' if len(missing) > 10 else ''} "
                    f"skipped={skipped[:10]}{'...' if len(skipped) > 10 else ''}")
        except Exception as e:
            log_print(logger, f"Failed to load {ckpt_name}: {e}")

    bootstrapped_from_bc = (bootstrap_label == "BC")

    if cfg.require_bootstrap_checkpoint and not bootstrap_loaded:
        log_print(logger,
            "No bootstrap checkpoint found and require_bootstrap_checkpoint=true. Exiting.")
        raise SystemExit(0)

    bc_log_std_from_ckpt = _read_bc_log_std(
        model_output_dir / f"{mk}.pt", bc_baseline_pt, log_std_param.shape, device, logger,
    )

    # Immutable BC baseline snapshot — only written on first BC bootstrap so
    # the anchor regularisation never anchors against the drifted SAC actor.
    if bc_baseline_pt.exists():
        pass
    elif bootstrapped_from_bc:
        baseline_state = {"model_state_dict": strip_compile_prefix(actor.state_dict()),
                          "global_step": global_step}
        if bc_log_std_from_ckpt is not None:
            baseline_state["model_state_dict"]["log_std"] = bc_log_std_from_ckpt.cpu().clone()
        torch.save(baseline_state, str(bc_baseline_pt))
        export_actor_onnx(actor, bc_baseline_onnx, cfg.seq_len, cfg.input_size, device)
        log_print(logger, f"Saved immutable BC baseline from BC bootstrap: {bc_baseline_pt.name}")
    else:
        log_print(logger,
            f"BC baseline missing AND bootstrap source was {bootstrap_label!r} (not BC) —"
            f" refusing to snapshot current actor as BC baseline."
            f" BC anchor regularisation will be DISABLED for this run.")

    bc_log_std_anchor = bc_log_std_from_ckpt
    if bc_log_std_anchor is None:
        log_print(logger,
            "BC log_std anchor: no BC log_std available (bc_baseline.pt and"
            f" {mk}.pt both lack one) — log_std anchor DISABLED for this run.")

    # Seed log_std_param from BC only when the anchor is going to compare
    # against it. Otherwise start from cfg.log_std_init so a known-good
    # exploration range is in place from step 0.
    if not sac_ckpt_path.exists():
        if cfg.bc_log_std_anchor_alpha > 0 and bc_log_std_from_ckpt is not None:
            log_std_param.data.copy_(bc_log_std_from_ckpt)
            log_print(logger, f"Seeded log_std_param from BC (anchor active):"
                              f" {log_std_param.data.tolist()}")
        else:
            log_print(logger, f"Skipped BC log_std seed (anchor inactive) —"
                              f" using cfg.log_std_init={cfg.log_std_init}:"
                              f" {log_std_param.data.tolist()}")

    if bc_log_std_anchor is not None:
        log_print(logger, f"BC log_std anchor snapshot: {bc_log_std_anchor.tolist()}"
                  f" (alpha={cfg.bc_log_std_anchor_alpha})")

    # Critic / temperature / optimizer metadata resume.
    metadata_loaded = False
    last_export_step = 0
    if sac_ckpt_path.exists():
        try:
            meta = torch.load(sac_ckpt_path, map_location=device, weights_only=False)
            if "q1_state" in meta:
                q1.load_state_dict(strip_compile_prefix(meta["q1_state"]))
                q2.load_state_dict(strip_compile_prefix(meta["q2_state"]))
                target_q1.load_state_dict(strip_compile_prefix(meta["target_q1_state"]))
                target_q2.load_state_dict(strip_compile_prefix(meta["target_q2_state"]))
                temperature.load_state_dict(meta["temperature_state"])
                with torch.no_grad():
                    loaded_alpha = float(temperature.alpha.item())
                    capped_max = max(cfg.temperature_max, cfg.temperature_min, 1e-6)
                    capped_min = max(cfg.temperature_min, 1e-6)
                    if loaded_alpha > capped_max or loaded_alpha < capped_min:
                        log_print(logger, f"Loaded α={loaded_alpha:.4f} outside"
                                  f" [{capped_min:.4f}, {capped_max:.4f}] — clamping.")
                        temperature.log_alpha.clamp_(
                            min=np.log(capped_min), max=np.log(capped_max),
                        )
                critic_optimizer.load_state_dict(meta["critic_optimizer_state"])
                try:
                    actor_optimizer.load_state_dict(meta["actor_optimizer_state"])
                except (ValueError, KeyError):
                    log_print(logger,
                        "Actor optimizer state incompatible — using fresh optimizer")
                temp_optimizer.load_state_dict(meta["temp_optimizer_state"])
                if "log_std_param" in meta:
                    log_std_param.data.copy_(meta["log_std_param"])
                best_exported_return = meta.get("best_mean_return", -1e9)
                best_observed_return = meta.get("best_observed_return", best_exported_return)
                baseline_return = meta.get("baseline_return", None)
                global_step = meta.get("global_step", global_step)
                last_export_step = int(meta.get("last_export_step",
                                                max(0, global_step - cfg.export_interval_steps)))
                metadata_loaded = True
                log_print(logger, f"Loaded SAC metadata: step={global_step}"
                          f" best_exported={best_exported_return:.2f}"
                          f" best_seen={best_observed_return:.2f}"
                          f" baseline_ret={baseline_return}"
                          f" last_export_step={last_export_step}"
                          f" log_std={log_std_param.data.tolist()}")
        except Exception as e:
            log_print(logger, f"Failed to load SAC metadata: {e}")

    # Clean up legacy sentinel if present.
    flag_path = model_output_dir / "_reset_inflight_clock.flag"
    if flag_path.exists():
        flag_path.unlink(missing_ok=True)

    # Random-init critics MUST warm up regardless of actor source.
    if not metadata_loaded and global_step >= cfg.critic_warmup_steps:
        log_print(logger,
            f"No critic metadata: resetting global_step from {global_step} to 0 for warmup")
        global_step = 0

    bc_actor = _load_bc_anchor(cfg, device, bc_baseline_pt, logger)

    return JointBootstrapResult(
        actor=actor,
        log_std_param=log_std_param,
        q1=q1, q2=q2,
        target_q1=target_q1, target_q2=target_q2,
        temperature=temperature,
        actor_optimizer=actor_optimizer,
        critic_optimizer=critic_optimizer,
        temp_optimizer=temp_optimizer,
        bc_actor=bc_actor,
        bc_log_std_anchor=bc_log_std_anchor,
        critic_mode=cfg.critic_mode,
        global_step=global_step,
        best_exported_return=best_exported_return,
        best_observed_return=best_observed_return,
        baseline_return=baseline_return,
        last_export_step=last_export_step,
        bootstrap_loaded=bootstrap_loaded,
        bootstrap_label=bootstrap_label,
        metadata_loaded=metadata_loaded,
    )
