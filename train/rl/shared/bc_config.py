"""BC trainer configuration — reads all settings from split config.

Shared across BC and SAC trainers. Contains BCConfig, FeatureGroupConfig,
and feature name constants.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

import torch

from train.common import PropertyReader

from train.common.ModelRoles import PAWN_POLICY, resolve_model_key

DEFAULT_MODEL_KEY = os.environ.get("UT99_BC_MODEL_KEY", resolve_model_key(PAWN_POLICY))

LOCOMOTION_TARGETS = [
    "moveIdle",
    "moveForward",
    "moveForwardLeft",
    "moveForwardRight",
    "moveStrafeLeft",
    "moveStrafeRight",
    "moveBack",
    "moveBackLeft",
    "moveBackRight",
    "moveForward_dodgeLeft",
    "moveForward_dodgeRight",
    "moveBack_dodgeLeft",
    "moveBack_dodgeRight",
    "moveStrafeLeft_dodgeForward",
    "moveStrafeLeft_dodgeBack",
    "moveStrafeRight_dodgeForward",
    "moveStrafeRight_dodgeBack",
]

TURN_CLASS_PREFIX = "yawTurn_"
PITCH_TURN_PREFIX = "pitchTurn_"
YAW_DELTA_FEATURE = "yawDelta_norm"
PITCH_DELTA_FEATURE = "pitchDelta_norm"
MOVE_DIR_SIN = "moveDir_sin"
MOVE_DIR_COS = "moveDir_cos"
DODGE_FEATURE = "dodge"
B_JUMP_FEATURE = "bJump"
B_DUCK_FEATURE = "bDuck"
B_IDLE_FEATURE = "bIdle"


def resolve_device(pref: str) -> torch.device:
    if pref == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(pref)


@dataclass(frozen=True)
class FeatureGroupConfig:
    """Represents one temporal feature group from features.json."""
    features: list[str]
    first_frames: int
    last_frames: int

    @property
    def active_len(self) -> int:
        return self.first_frames + self.last_frames


@dataclass(frozen=True)
class DataLoaderConfig:
    """DataLoader runtime settings — read from <model>/bc.json `data_loader`."""
    num_workers: int
    persistent_workers: bool
    prefetch_factor: int
    pin_memory: bool


@dataclass(frozen=True)
class BCConfig:
    model_key: str
    task_kind: str
    hidden_size: int
    num_layers: int
    dropout: float
    device: torch.device
    batch_size: int
    lr: float
    weight_decay: float
    grad_clip_norm: float
    label_smoothing: float
    max_steps: int
    save_every_steps: int
    warmup_steps: int
    early_stop_patience: int
    log_every_steps: int
    seq_len: int
    val_split: float
    recovery_bucket_patterns: list[str]
    recovery_oversample_factor: int
    input_features: list[str]
    target_features: list[str]
    movement_targets: list[str]
    locomotion_indices: list[int]
    aux_indices: list[int]
    yaw_bin_indices: list[int]
    pitch_bin_indices: list[int]
    yaw_index: int
    pitch_index: int
    input_size: int
    output_size: int
    dodge_index: int
    jump_index: int
    duck_index: int
    idle_index: int
    feature_groups: list[FeatureGroupConfig]
    player_hidden_dim: int
    player_embed_dim: int
    map_embedding_capacity: int
    map_embedding_dim: int
    # Phase 2: aux target columns (e.g. target_index + target_index_confidence
    # for shooting). Empty for movement/viewrotation.
    aux_target_features: list[str]
    data_loader: DataLoaderConfig

    @property
    def total_window(self) -> int:
        return max(g.active_len for g in self.feature_groups)


def _infer_task_kind(target_features: list[str]) -> str:
    # Joint VR+shooting policy emits both yaw/pitch deltas AND fire/altFire in
    # one action vector — detect via co-presence of yawDelta_norm + bFire.
    # Must be tested BEFORE the plain `viewrotation_continuous` branch so the
    # joint task isn't mis-classified.
    has_yaw_delta = YAW_DELTA_FEATURE in target_features
    has_fire = ("bFire" in target_features) or ("bAltFire" in target_features)
    if has_yaw_delta and has_fire:
        return "joint_pawn"
    if any(name.startswith(TURN_CLASS_PREFIX) for name in target_features):
        return "viewrotation_discrete"
    if has_yaw_delta or PITCH_DELTA_FEATURE in target_features:
        return "viewrotation_continuous"
    if MOVE_DIR_SIN in target_features or MOVE_DIR_COS in target_features:
        return "movement_continuous"
    return "movement"


def load_config(model_key: str = DEFAULT_MODEL_KEY) -> BCConfig:
    model_cfg = PropertyReader.get_model_config(model_key)
    bc_cfg = PropertyReader.get_bc_config(model_key)
    features_cfg = PropertyReader.get_features(model_key)

    target_features = features_cfg["target_features"]
    task_kind = _infer_task_kind(target_features)

    groups = [
        FeatureGroupConfig(
            features=g["features"],
            first_frames=g["first_frames"],
            last_frames=g["last_frames"],
        )
        for g in features_cfg["feature_groups"]
    ]
    input_features = [f for g in groups for f in g.features]
    seq_len = max(g.active_len for g in groups)
    map_embedding_capacity, map_embedding_dim = map_embedding_config(model_cfg, input_features)

    movement_targets = target_features if task_kind in ("movement", "movement_continuous") else []
    locomotion_indices = [i for i, t in enumerate(movement_targets) if t in LOCOMOTION_TARGETS]
    aux_indices = [i for i, t in enumerate(movement_targets) if t not in LOCOMOTION_TARGETS]
    yaw_bin_indices = [i for i, t in enumerate(target_features) if t.startswith(TURN_CLASS_PREFIX)]
    pitch_bin_indices = [i for i, t in enumerate(target_features) if t.startswith(PITCH_TURN_PREFIX)]
    yaw_index = next((i for i, t in enumerate(target_features) if t == YAW_DELTA_FEATURE), -1)
    pitch_index = next((i for i, t in enumerate(target_features)
                        if t in ("viewRotationY_norm", PITCH_DELTA_FEATURE)), -1)
    dodge_index = next((i for i, t in enumerate(target_features) if t == DODGE_FEATURE), -1)
    jump_index = next((i for i, t in enumerate(target_features) if t == B_JUMP_FEATURE), -1)
    duck_index = next((i for i, t in enumerate(target_features) if t == B_DUCK_FEATURE), -1)
    idle_index = next((i for i, t in enumerate(target_features) if t == B_IDLE_FEATURE), -1)

    dl_cfg = bc_cfg["data_loader"]
    data_loader = DataLoaderConfig(
        num_workers=int(dl_cfg["num_workers"]),
        persistent_workers=bool(dl_cfg["persistent_workers"]),
        prefetch_factor=int(dl_cfg["prefetch_factor"]),
        pin_memory=bool(dl_cfg["pin_memory"]),
    )

    # ``UT99_DEVICE_OVERRIDE`` laat smoke tests op een CUDA-loze dev-machine
    # CPU forceren zonder model.json te muteren (matched aan het oudere joint
    # BC config_loader patroon). Productie laat de env-var leeg en gebruikt
    # ``model.device``.
    device_pref = os.environ.get("UT99_DEVICE_OVERRIDE", str(model_cfg["device"]))

    return BCConfig(
        model_key=model_key,
        task_kind=task_kind,
        hidden_size=int(model_cfg["hidden_size"]),
        num_layers=int(model_cfg["num_layers"]),
        dropout=float(model_cfg["dropout"]),
        device=resolve_device(device_pref),
        batch_size=int(bc_cfg["batch_size"]),
        lr=float(bc_cfg["lr"]),
        weight_decay=float(bc_cfg["weight_decay"]),
        grad_clip_norm=float(bc_cfg["grad_clip_norm"]),
        label_smoothing=float(bc_cfg["label_smoothing"]),
        max_steps=int(bc_cfg["pretrain_steps"]),
        save_every_steps=int(bc_cfg["save_every_steps"]),
        warmup_steps=int(bc_cfg["warmup_steps"]),
        early_stop_patience=int(bc_cfg["early_stop_patience"]),
        log_every_steps=int(bc_cfg["log_every_steps"]),
        seq_len=seq_len,
        val_split=float(bc_cfg["val_split"]),
        recovery_bucket_patterns=[str(x) for x in bc_cfg["recovery_bucket_patterns"]],
        recovery_oversample_factor=int(bc_cfg["recovery_oversample_factor"]),
        input_features=input_features,
        target_features=target_features,
        movement_targets=movement_targets,
        locomotion_indices=locomotion_indices,
        aux_indices=aux_indices,
        yaw_bin_indices=yaw_bin_indices,
        pitch_bin_indices=pitch_bin_indices,
        yaw_index=yaw_index,
        pitch_index=pitch_index,
        input_size=len(input_features),
        output_size=len(target_features),
        dodge_index=dodge_index,
        jump_index=jump_index,
        duck_index=duck_index,
        idle_index=idle_index,
        feature_groups=groups,
        player_hidden_dim=int(model_cfg["player_hidden_dim"]),
        player_embed_dim=int(model_cfg["player_embed_dim"]),
        map_embedding_capacity=map_embedding_capacity,
        map_embedding_dim=map_embedding_dim,
        aux_target_features=list(features_cfg.get("aux_target_features", [])),
        data_loader=data_loader,
    )


def map_embedding_config(model_cfg: dict, input_features: list[str]) -> tuple[int, int]:
    if "map_id" not in input_features:
        return 0, 0
    missing = [
        key for key in ("map_embedding_capacity", "map_embedding_dim")
        if key not in model_cfg
    ]
    if missing:
        raise ValueError(
            "features.json includes map_id, but model.json is missing: "
            + ", ".join(missing)
        )
    capacity = int(model_cfg["map_embedding_capacity"])
    dim = int(model_cfg["map_embedding_dim"])
    if capacity <= 0 or dim <= 0:
        raise ValueError(
            "map_embedding_capacity and map_embedding_dim must be positive "
            "when features.json includes map_id"
        )
    validate_configured_map_ids_within_capacity(capacity)
    return capacity, dim


def validate_configured_map_ids_within_capacity(capacity: int) -> None:
    errors: list[str] = []
    seen: dict[int, str] = {}
    for map_key, cfg in PropertyReader.get_maps_config().items():
        if not isinstance(cfg, dict) or "map_id" not in cfg:
            errors.append(f"{map_key}: missing map_id")
            continue
        map_id = int(cfg["map_id"])
        if map_id < 0 or map_id >= capacity:
            errors.append(
                f"{map_key}: map_id {map_id} outside embedding capacity {capacity}"
            )
        previous = seen.setdefault(map_id, map_key)
        if previous != map_key:
            errors.append(f"{map_key}: duplicate map_id {map_id} also used by {previous}")
    if errors:
        raise ValueError("Invalid map_id config: " + "; ".join(errors))
