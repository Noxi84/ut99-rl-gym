"""Joint VR+shooting BC trainer.

Multi-head loss: Gaussian NLL yaw/pitch + BCE fire/altFire + confidence-weighted
CE target_index. Hergebruikt ``bc_training_loop.run_training`` voor LR-schedule,
checkpointing, SIGTERM-migration en ONNX promotion — geen duplicate orchestratie.

Config-source: ``train.rl.rl_pawn.trainBC.config_loader`` wikkelt de
gedeelde :func:`train.rl.shared.bc_config.load_config` plus de joint-specifieke
``multi_head_loss`` reader. Configs leven in
``resources/models/rl_pawn/`` (geregistreerd via index.json).
"""
from __future__ import annotations

import torch
from torch import optim

from train.common.SessionPaths import get_session_paths
from train.common.TrainerLogger import log_print, log_kv_block, setup_trainer_logger
from train.model.bc_sequence_network import BCSequenceNetwork
from train.rl.shared.bc_data_loading import build_data_loaders
from train.rl.shared.bc_training_loop import run_training
from train.rl.rl_pawn.trainBC.config_loader import (
    JOINT_MODEL_KEY, load_joint_bc_config,
)
from train.rl.rl_pawn.trainBC.data_loading import compute_pos_weight, load_data
from train.rl.rl_pawn.trainBC.loss import compute_joint_bc_loss
from train.rl.rl_pawn.trainBC.validation import validate


_BC_BASELINE_GATE_NOTE = (
    "BC fase status-criterium: val_loss_yaw_pitch / val_loss_fire / "
    "val_loss_target worden door de gebruiker achteraf beoordeeld. Trainer "
    "stopt op cfg.early_stop_patience (bc.json)."
)


def main():
    cfg, loss_cfg = load_joint_bc_config()
    SP = get_session_paths(create_dirs=True, model_key=JOINT_MODEL_KEY)
    logger = setup_trainer_logger(session_dir=SP.sessions_base_dir, model_key=JOINT_MODEL_KEY)

    log_print(logger, "Joint VR+shooting BC pre-trainer starting")
    log_print(logger, _BC_BASELINE_GATE_NOTE)

    log_kv_block(logger, "BC Config", {
        "model_key": cfg.model_key,
        "task_kind": cfg.task_kind,
        "hidden_size": cfg.hidden_size,
        "num_layers": cfg.num_layers,
        "input_size": cfg.input_size,
        "output_size": cfg.output_size,
        "aux_target_features": cfg.aux_target_features,
        "batch_size": cfg.batch_size,
        "lr": cfg.lr,
        "max_steps": cfg.max_steps,
        "device": cfg.device,
        "csv_dir": SP.trainingdata_dir,
    })
    log_kv_block(logger, "Multi-head loss weights", {
        "w_movement": loss_cfg.w_movement,
        "w_vr": loss_cfg.w_vr,
        "w_fire": loss_cfg.w_fire,
        "w_target": loss_cfg.w_target,
    })

    dataset = load_data(cfg, SP.trainingdata_dir, logger)
    if dataset is None:
        return
    log_print(logger, f"Total samples: {len(dataset)}")

    pos_weight = compute_pos_weight(dataset, cfg, logger)
    train_loader, val_loader = build_data_loaders(dataset, cfg, logger)

    model = BCSequenceNetwork(
        input_features=cfg.input_features,
        output_size=cfg.output_size,
        hidden_size=cfg.hidden_size,
        num_layers=cfg.num_layers,
        dropout=cfg.dropout,
        player_hidden_dim=cfg.player_hidden_dim,
        player_embed_dim=cfg.player_embed_dim,
        map_embedding_capacity=cfg.map_embedding_capacity,
        map_embedding_dim=cfg.map_embedding_dim,
        gaussian_head=True,           # learnable log_std for yaw/pitch SAC bootstrap
        log_std_init=-1.0,
        expose_target_index=True,     # joint target_index aux head
    ).to(cfg.device)

    # Sidecar attributes the validate() wrapper reads. Keeps the shared
    # bc_training_loop signature stable (5-arg compute_loss / 6-arg validate)
    # while still passing joint-specific state without globals.
    model._joint_log_std = model.log_std
    model._joint_w_movement = loss_cfg.w_movement
    model._joint_w_vr = loss_cfg.w_vr
    model._joint_w_fire = loss_cfg.w_fire
    model._joint_w_target = loss_cfg.w_target
    model._joint_logger = logger

    optimizer = optim.AdamW(model.parameters(), lr=cfg.lr, weight_decay=cfg.weight_decay)

    model_output_dir = SP.trainingmodel_dir
    model_output_dir.mkdir(parents=True, exist_ok=True)
    pt_path = model_output_dir / f"{cfg.model_key}.pt"
    onnx_path = str(model_output_dir / f"{cfg.model_key}.onnx")

    weight_tensor = torch.tensor(pos_weight, dtype=torch.float32, device=cfg.device)

    def loss_fn(logits, y, mask, cfg_, weights, *, target_logits=None):
        return compute_joint_bc_loss(
            logits, y, mask, cfg_, weights,
            target_logits=target_logits,
            log_std=model.log_std,
            w_movement=loss_cfg.w_movement,
            w_vr=loss_cfg.w_vr,
            w_fire=loss_cfg.w_fire,
            w_target=loss_cfg.w_target,
        )

    run_training(
        cfg=cfg,
        model=model,
        optimizer=optimizer,
        train_loader=train_loader,
        val_loader=val_loader,
        weight_tensor=weight_tensor,
        compute_loss=loss_fn,
        validate_fn=validate,
        acc_name="fire_acc",
        pt_path=pt_path,
        onnx_path=onnx_path,
        logger=logger,
    )
