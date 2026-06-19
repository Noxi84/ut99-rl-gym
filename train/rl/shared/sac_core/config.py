"""SAC trainer configuration — reads model + SAC settings from split config."""
from __future__ import annotations

import os
import torch
from dataclasses import dataclass

from train.common import PropertyReader
from train.common.ModelRoles import PAWN_POLICY, resolve_model_key
from train.rl.shared.bc_config import FeatureGroupConfig, YAW_DELTA_FEATURE, PITCH_DELTA_FEATURE, map_embedding_config

DEFAULT_MODEL_KEY = os.environ.get("UT99_SAC_MODEL_KEY", resolve_model_key(PAWN_POLICY))


def _resolve_device(pref: str) -> torch.device:
    if pref == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(pref)


@dataclass(frozen=True)
class SACConfig:
    model_key: str
    device: torch.device

    # Model architecture
    hidden_size: int
    num_layers: int
    dropout: float

    # SAC hyperparams
    lr_actor: float
    lr_critic: float
    lr_temperature: float
    gamma: float
    tau: float
    batch_size: int
    replay_buffer_capacity: int
    min_buffer_size: int
    actor_update_period: int
    temperature_init: float
    target_entropy: float
    max_grad_norm: float
    bc_alpha: float
    bc_alpha_min: float
    bc_alpha_anneal_steps: int
    bc_log_std_anchor_alpha: float
    action_smoothness_alpha: float
    action_bias_alpha: float
    log_std_init: float
    export_interval_steps: int
    export_min_improvement: float
    export_min_improvement_pct: float
    log_interval_steps: int
    max_file_age_seconds: float
    require_bootstrap_checkpoint: bool
    reward_normalization: bool
    auto_temperature: bool
    temperature_min: float
    temperature_max: float
    min_steps_before_export: int
    critic_warmup_steps: int
    max_degradation_below_baseline: float
    recent_reward_window: int
    checkpoint_interval_steps: int
    prefetch_depth: int
    ingest_interval: float
    max_files_per_ingest: int
    # Whether NPZ rows tagged ``policy_role=1`` (champion-bot rollouts) are
    # accepted into the replay buffer. ``True`` = ingest them (SAC is
    # off-policy, so champion transitions are valid (s,a,r,s') tuples;
    # ``RewardComputer`` produced their rewards from events/features, not
    # from the behavior policy). ``False`` = legacy hygiene filter — drop
    # role=1 rows in ``ingest_npz_files`` AND let Java skip the flush
    # entirely on the bot host.
    champion_experience_enabled: bool

    # Feature dimensions
    input_features: list[str]
    target_features: list[str]
    input_size: int
    output_size: int
    seq_len: int
    action_dim: int

    # Learnable log_std clamp bounds (applied in sample_action and anchor loss)
    log_std_min: float
    log_std_max: float

    # Continuous action indices
    yaw_index: int
    pitch_index: int

    # Per-target semantic type ("continuous" | "binary"). Read by the SAC export
    # sanity probe to skip sat/spread/gain-bias checks on dims where a saturated,
    # low-spread, biased output is correct policy behavior (e.g. bJump usually 0).
    target_feature_types: dict

    feature_groups: list[FeatureGroupConfig]
    player_hidden_dim: int
    player_embed_dim: int
    map_embedding_capacity: int
    map_embedding_dim: int

    # ------------------------------------------------------------------
    # Joint VR+shooting commitment-3 fields (vr-shooting-sac-merge.md §7.5).
    # All optional: missing in older SAC configs means "feature inactive" —
    # the kernel falls back to single-critic /
    # single-α / all-dim CAPS behaviour exactly as before. NEW configs
    # (resources/models/rl_pawn/sac.json) MUST set these explicitly;
    # absence is a backwards-compat path, not a silent production default.
    # ------------------------------------------------------------------
    critic_mode: str = "single"
    target_entropy_per_dim: tuple[float, ...] | None = None
    action_smoothness_dims: tuple[int, ...] | None = None
    action_bias_dims: tuple[int, ...] | None = None
    bc_anchor_dims: tuple[int, ...] | None = None
    aux_target_alpha: float = 0.0
    reward_decomp_keys: tuple[str, ...] | None = None
    reward_decomp_normalization: bool = False
    reward_head_weights: dict[str, float] | None = None
    event_priority_fraction: float = 0.0
    event_priority_positive_fraction: float = 0.0
    event_priority_reward_keys: tuple[str, ...] | None = None
    event_priority_action_indices: tuple[int, ...] | None = None
    event_priority_min_abs_reward: float = 1e-6

    # ------------------------------------------------------------------
    # GPU-throughput optimizations (joint VR+shooting performance pass).
    # Follow the commitment-3 backwards-compat pattern: optional fields,
    # default OFF for older configs. Per-model training loops decide whether
    # to honour these; rl_pawn does.
    # ------------------------------------------------------------------
    use_amp: bool = False
    amp_dtype: str = "bfloat16"
    compile_models: bool = False

    # ------------------------------------------------------------------
    # Fase 2.5 CTDE (Centralized Training Decentralized Execution).
    # When ``ctde_mode != "off"`` the critic consumes a closest-2 teammate
    # slice from the NPZ aux key ``teammate_state`` (concatenated to
    # ``self_state`` before the per-head MLP). Actor is unaffected — pure
    # critic-side change. Older configs without these keys default to
    # ``ctde_mode="off"`` for backwards-compat with the pre-Fase-2.5
    # single-state critic.
    # ------------------------------------------------------------------
    ctde_mode: str = "off"
    teammate_state_dim: int = 0

    @property
    def total_window(self) -> int:
        return max(g.active_len for g in self.feature_groups)

    @property
    def amp_torch_dtype(self) -> "torch.dtype":
        if self.amp_dtype == "bfloat16":
            return torch.bfloat16
        if self.amp_dtype == "float16":
            return torch.float16
        raise ValueError(f"Unknown amp_dtype {self.amp_dtype!r}")


def load_config(model_key: str = DEFAULT_MODEL_KEY) -> SACConfig:
    model_cfg = PropertyReader.get_model_config(model_key)
    sac_cfg = PropertyReader.get_sac_config(model_key)
    features_cfg = PropertyReader.get_features(model_key)

    # PropertyReader.get_features normalizes target_features (objects in
    # features.json → list of names here) and adds target_feature_types
    # (name → "steering" | "continuous" | "binary"), with full validation.
    target_features = features_cfg["target_features"]
    target_feature_types = features_cfg["target_feature_types"]

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

    yaw_index = next((i for i, t in enumerate(target_features) if t == YAW_DELTA_FEATURE), -1)
    pitch_index = next((i for i, t in enumerate(target_features) if t == PITCH_DELTA_FEATURE), -1)
    action_dim = len(target_features)

    # Joint VR+shooting commitment-3 fields — pre-wired feature flags.
    # Older SAC configs may omit these keys; absence means kernel falls back to
    # single-critic, single-α, all-dim CAPS. Explicit "single"/None values in
    # rl_pawn/sac.json activate the existing (pre-merge) behaviour
    # without ambiguity. This is NOT a silent default for production keys;
    # see SACConfig docstring.
    critic_mode = str(sac_cfg.get("critic_mode", "single"))
    if critic_mode not in ("single", "multi_head"):
        raise ValueError(
            f"sac.json critic_mode must be 'single' or 'multi_head', got {critic_mode!r}"
        )
    raw_per_dim = sac_cfg.get("target_entropy_per_dim", None)
    target_entropy_per_dim = (
        tuple(float(x) for x in raw_per_dim) if raw_per_dim is not None else None
    )
    if target_entropy_per_dim is not None and len(target_entropy_per_dim) != action_dim:
        raise ValueError(
            "sac.json target_entropy_per_dim length must match action_dim "
            f"({action_dim}); got {len(target_entropy_per_dim)}"
        )
    raw_smooth_dims = sac_cfg.get("action_smoothness_dims", None)
    action_smoothness_dims = (
        tuple(int(x) for x in raw_smooth_dims) if raw_smooth_dims is not None else None
    )
    raw_bias_dims = sac_cfg.get("action_bias_dims", None)
    action_bias_dims = (
        tuple(int(x) for x in raw_bias_dims) if raw_bias_dims is not None else None
    )
    raw_bc_anchor_dims = sac_cfg.get("bc_anchor_dims", None)
    bc_anchor_dims = (
        tuple(int(x) for x in raw_bc_anchor_dims) if raw_bc_anchor_dims is not None else None
    )
    aux_target_alpha = float(sac_cfg.get("aux_target_alpha", 0.0))
    raw_decomp_keys = sac_cfg.get("reward_decomp_keys", None)
    reward_decomp_keys = (
        tuple(str(x) for x in raw_decomp_keys) if raw_decomp_keys is not None else None
    )

    if critic_mode == "multi_head" and reward_decomp_keys is None:
        raise ValueError(
            "sac.json critic_mode='multi_head' requires reward_decomp_keys to be set "
            "(list of per-skill reward keys to feed per-head Bellman targets)."
        )

    reward_decomp_normalization = bool(sac_cfg.get("reward_decomp_normalization", False))
    raw_head_weights = sac_cfg.get("reward_head_weights", None)
    reward_head_weights = None
    if raw_head_weights is not None:
        if not isinstance(raw_head_weights, dict):
            raise ValueError("sac.json reward_head_weights must be an object/dict")
        reward_head_weights = {str(k): float(v) for k, v in raw_head_weights.items()}
        non_positive = [k for k, v in reward_head_weights.items() if v <= 0.0]
        if non_positive:
            raise ValueError(
                f"sac.json reward_head_weights values must be > 0; got {non_positive}"
            )
        if reward_decomp_keys is not None:
            unknown = [k for k in reward_head_weights if k not in reward_decomp_keys]
            if unknown:
                raise ValueError(
                    f"sac.json reward_head_weights has keys {unknown}; "
                    f"expected subset of reward_decomp_keys={list(reward_decomp_keys)}"
                )

    event_priority_fraction = float(sac_cfg.get("event_priority_fraction", 0.0))
    event_priority_positive_fraction = float(
        sac_cfg.get("event_priority_positive_fraction", 0.0)
    )
    if not (0.0 <= event_priority_fraction <= 1.0):
        raise ValueError(
            f"sac.json event_priority_fraction must be in [0, 1], got {event_priority_fraction}"
        )
    if not (0.0 <= event_priority_positive_fraction <= event_priority_fraction):
        raise ValueError(
            "sac.json event_priority_positive_fraction must be in "
            f"[0, event_priority_fraction], got {event_priority_positive_fraction}"
        )
    raw_event_reward_keys = sac_cfg.get("event_priority_reward_keys", None)
    event_priority_reward_keys = (
        tuple(str(x) for x in raw_event_reward_keys)
        if raw_event_reward_keys is not None
        else None
    )
    if event_priority_reward_keys is not None and reward_decomp_keys is not None:
        unknown = [k for k in event_priority_reward_keys if k not in reward_decomp_keys]
        if unknown:
            raise ValueError(
                f"sac.json event_priority_reward_keys has keys {unknown}; "
                f"expected subset of reward_decomp_keys={list(reward_decomp_keys)}"
            )
    raw_event_action_indices = sac_cfg.get("event_priority_action_indices", None)
    event_priority_action_indices = (
        tuple(int(x) for x in raw_event_action_indices)
        if raw_event_action_indices is not None
        else None
    )
    if event_priority_action_indices is not None:
        invalid = [i for i in event_priority_action_indices if i < 0 or i >= action_dim]
        if invalid:
            raise ValueError(
                f"sac.json event_priority_action_indices out of range for "
                f"action_dim={action_dim}: {invalid}"
            )
    event_priority_min_abs_reward = float(
        sac_cfg.get("event_priority_min_abs_reward", 1e-6)
    )
    if event_priority_min_abs_reward <= 0.0:
        raise ValueError(
            "sac.json event_priority_min_abs_reward must be > 0, "
            f"got {event_priority_min_abs_reward}"
        )
    if event_priority_fraction > 0.0:
        has_reward_keys = bool(event_priority_reward_keys)
        has_action_indices = bool(event_priority_action_indices)
        if not has_reward_keys and not has_action_indices:
            raise ValueError(
                "sac.json event_priority_fraction > 0 requires "
                "event_priority_reward_keys or event_priority_action_indices"
            )

    # Fase 2.5 CTDE — closest-2 teammate slice consumed by critic only.
    ctde_mode = str(sac_cfg.get("ctde_mode", "off"))
    if ctde_mode not in ("off", "closest_two"):
        raise ValueError(
            "sac.json ctde_mode must be 'off' or 'closest_two', got "
            f"{ctde_mode!r}"
        )
    teammate_state_dim = int(sac_cfg.get("teammate_state_dim", 0))
    if ctde_mode != "off" and teammate_state_dim <= 0:
        raise ValueError(
            f"sac.json ctde_mode={ctde_mode!r} requires teammate_state_dim > 0, "
            f"got {teammate_state_dim}"
        )
    if ctde_mode == "off" and teammate_state_dim != 0:
        raise ValueError(
            "sac.json ctde_mode='off' requires teammate_state_dim == 0, "
            f"got {teammate_state_dim}"
        )

    use_amp = bool(sac_cfg.get("use_amp", False))
    amp_dtype = str(sac_cfg.get("amp_dtype", "bfloat16"))
    if amp_dtype not in ("bfloat16", "float16"):
        raise ValueError(
            f"sac.json amp_dtype must be 'bfloat16' or 'float16', got {amp_dtype!r}"
        )
    compile_models = bool(sac_cfg.get("compile_models", False))

    return SACConfig(
        model_key=model_key,
        device=_resolve_device(str(model_cfg["device"])),
        hidden_size=int(model_cfg["hidden_size"]),
        num_layers=int(model_cfg["num_layers"]),
        dropout=float(model_cfg["dropout"]),
        lr_actor=float(sac_cfg["lr_actor"]),
        lr_critic=float(sac_cfg["lr_critic"]),
        lr_temperature=float(sac_cfg["lr_temperature"]),
        gamma=float(sac_cfg["gamma"]),
        tau=float(sac_cfg["tau"]),
        batch_size=int(sac_cfg["batch_size"]),
        replay_buffer_capacity=int(sac_cfg["replay_buffer_capacity"]),
        min_buffer_size=int(sac_cfg["min_buffer_size"]),
        actor_update_period=int(sac_cfg["actor_update_period"]),
        temperature_init=float(sac_cfg["temperature_init"]),
        target_entropy=float(sac_cfg["target_entropy"]),
        max_grad_norm=float(sac_cfg["max_grad_norm"]),
        bc_alpha=float(sac_cfg["bc_alpha"]),
        bc_alpha_min=float(sac_cfg["bc_alpha_min"]),
        bc_alpha_anneal_steps=int(sac_cfg["bc_alpha_anneal_steps"]),
        bc_log_std_anchor_alpha=float(sac_cfg["bc_log_std_anchor_alpha"]),
        action_smoothness_alpha=float(sac_cfg["action_smoothness_alpha"]),
        action_bias_alpha=float(sac_cfg["action_bias_alpha"]),
        log_std_init=float(sac_cfg["log_std_init"]),
        export_interval_steps=int(sac_cfg["export_interval_steps"]),
        export_min_improvement=float(sac_cfg["export_min_improvement"]),
        export_min_improvement_pct=float(sac_cfg["export_min_improvement_pct"]),
        log_interval_steps=int(sac_cfg["log_interval_steps"]),
        max_file_age_seconds=float(sac_cfg["max_file_age_seconds"]),
        require_bootstrap_checkpoint=bool(sac_cfg["require_bootstrap_checkpoint"]),
        reward_normalization=bool(sac_cfg["reward_normalization"]),
        auto_temperature=bool(sac_cfg["auto_temperature"]),
        temperature_min=float(sac_cfg["temperature_min"]),
        temperature_max=float(sac_cfg["temperature_max"]),
        min_steps_before_export=int(sac_cfg["min_steps_before_export"]),
        critic_warmup_steps=int(sac_cfg["critic_warmup_steps"]),
        max_degradation_below_baseline=float(sac_cfg["max_degradation_below_baseline"]),
        recent_reward_window=int(sac_cfg["recent_reward_window"]),
        checkpoint_interval_steps=int(sac_cfg["checkpoint_interval_steps"]),
        prefetch_depth=int(sac_cfg["prefetch_depth"]),
        ingest_interval=float(sac_cfg["ingest_interval"]),
        max_files_per_ingest=int(sac_cfg["max_files_per_ingest"]),
        champion_experience_enabled=bool(sac_cfg["champion_experience_enabled"]),
        input_features=input_features,
        target_features=target_features,
        input_size=len(input_features),
        output_size=len(target_features),
        seq_len=seq_len,
        action_dim=action_dim,
        log_std_min=float(sac_cfg["log_std_min"]),
        log_std_max=float(sac_cfg["log_std_max"]),
        yaw_index=yaw_index,
        pitch_index=pitch_index,
        target_feature_types=dict(target_feature_types),
        feature_groups=groups,
        player_hidden_dim=int(model_cfg["player_hidden_dim"]),
        player_embed_dim=int(model_cfg["player_embed_dim"]),
        map_embedding_capacity=map_embedding_capacity,
        map_embedding_dim=map_embedding_dim,
        critic_mode=critic_mode,
        target_entropy_per_dim=target_entropy_per_dim,
        action_smoothness_dims=action_smoothness_dims,
        action_bias_dims=action_bias_dims,
        bc_anchor_dims=bc_anchor_dims,
        aux_target_alpha=aux_target_alpha,
        reward_decomp_keys=reward_decomp_keys,
        reward_decomp_normalization=reward_decomp_normalization,
        reward_head_weights=reward_head_weights,
        event_priority_fraction=event_priority_fraction,
        event_priority_positive_fraction=event_priority_positive_fraction,
        event_priority_reward_keys=event_priority_reward_keys,
        event_priority_action_indices=event_priority_action_indices,
        event_priority_min_abs_reward=event_priority_min_abs_reward,
        use_amp=use_amp,
        amp_dtype=amp_dtype,
        compile_models=compile_models,
        ctde_mode=ctde_mode,
        teammate_state_dim=teammate_state_dim,
    )


