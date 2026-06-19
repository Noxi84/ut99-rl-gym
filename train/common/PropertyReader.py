# python-trainer/common/PropertyReader.py
from __future__ import annotations

import json
import os
from copy import deepcopy
from typing import Any, Dict, List, Optional

_CACHE: Optional[Dict[str, Any]] = None

_PROJECT_ROOT: str = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _try_load_split() -> Dict[str, Any]:
    """Load from split config structure (resources/config/ + resources/models/).

    Raises RuntimeError if the structure is missing or malformed.

    Model directories are governed by index.json — only models listed there are loaded.
    """
    config_dir = os.path.join(_PROJECT_ROOT, "resources", "config")
    # UT99_MODEL_CONFIG_DIR overrides the models directory for variant config overlays.
    model_config_override = os.environ.get("UT99_MODEL_CONFIG_DIR", "").strip()
    models_dir = model_config_override if model_config_override else os.path.join(_PROJECT_ROOT, "resources", "models")
    gameplay_file = os.path.join(config_dir, "gameplay.json")

    if not os.path.isdir(config_dir) or not os.path.isdir(models_dir) or not os.path.isfile(gameplay_file):
        raise RuntimeError(
            f"Split config structure not found under {os.path.join(_PROJECT_ROOT, 'resources')}. "
            "Ensure resources/config/ and resources/models/ exist.")

    root: Dict[str, Any] = {}

    # 1. gameplay.json → merge at root
    with open(gameplay_file, "r", encoding="utf-8") as f:
        root.update(json.load(f))

    # 2. files.json, runtime.json, roles.json, servers.json → keyed sections
    for section in ("files", "runtime", "roles", "servers"):
        section_file = os.path.join(config_dir, f"{section}.json")
        if os.path.isfile(section_file):
            with open(section_file, "r", encoding="utf-8") as f:
                root[section] = json.load(f)

    # 2b. resources/config/maps/<mapKey>.json → merged under root["maps"][mapKey]
    maps_dir = os.path.join(config_dir, "maps")
    if not os.path.isdir(maps_dir):
        raise RuntimeError(
            f"resources/config/maps directory not found: {maps_dir} "
            "(run scripts/deploy/extract-map-bounds.sh to populate it)")
    maps: Dict[str, Any] = {}
    for entry in sorted(os.listdir(maps_dir)):
        if not entry.endswith(".json"):
            continue
        map_key = entry[:-len(".json")]
        with open(os.path.join(maps_dir, entry), "r", encoding="utf-8") as f:
            maps[map_key] = json.load(f)
    root["maps"] = maps

    # 3. models → governed by index.json
    index_file = os.path.join(models_dir, "index.json")
    if not os.path.isfile(index_file):
        raise RuntimeError("Split config detected but models/index.json not found")
    with open(index_file, "r", encoding="utf-8") as f:
        index = json.load(f)
    if "models" not in index:
        raise RuntimeError("models/index.json: missing required key 'models'")
    model_list = index["models"]
    if not isinstance(model_list, list):
        raise RuntimeError("models/index.json: 'models' must be an array")

    models: Dict[str, Any] = {}
    for entry in model_list:
        if "model_key" not in entry:
            raise RuntimeError("models/index.json: entry missing required key 'model_key'")
        model_key = entry["model_key"]
        model_dir = os.path.join(models_dir, model_key)
        if not os.path.isdir(model_dir):
            raise RuntimeError(
                f"Model directory not found: {model_dir} (listed in index.json)")
        model: Dict[str, Any] = {"model_key": model_key}
        # NB: "probe" en "baseline" zijn joint-specifieke secties uit
        # vr-shooting-sac-merge.md (resources/models/rl_pawn/{probe.json,baseline.json}).
        # De optionele isfile-check hieronder slaat ontbrekende secties stil over.
        for section in ("runtime", "training_csv", "model", "bc", "sac", "rewards", "features", "promotion", "export_gate", "probe", "baseline"):
            section_file = os.path.join(model_dir, f"{section}.json")
            if os.path.isfile(section_file):
                with open(section_file, "r", encoding="utf-8") as f:
                    model[section] = json.load(f)
        extras_file = os.path.join(model_dir, "extras.json")
        if os.path.isfile(extras_file):
            with open(extras_file, "r", encoding="utf-8") as f:
                model.update(json.load(f))
        models[model_key] = model
    root["models"] = models

    return root


def _root() -> Dict[str, Any]:
    global _CACHE
    if _CACHE is not None:
        return _CACHE

    _CACHE = _try_load_split()
    print(f"Loaded split config from: {os.path.join(_PROJECT_ROOT, 'resources')}")
    return _CACHE


def _get_by_path(path: str) -> Any:
    if not path:
        raise ValueError("Path must not be empty")
    if not path.startswith("/"):
        path = "/" + path

    node: Any = _root()
    parts: List[str] = [p for p in path.split("/") if p]
    for p in parts:
        if not isinstance(node, dict) or p not in node:
            raise ValueError(f"Missing key in constants at path: {path} (failed at: {p})")
        node = node[p]
    return node


def _get_dict(path: str) -> Dict[str, Any]:
    v = _get_by_path(path)
    if not isinstance(v, dict):
        raise ValueError(f"Expected dict at {path}, got {type(v).__name__}")
    return dict(v)


# ==== Trainer config API ====
def get_sessions_dir() -> str:
    env = os.environ.get("UT99_SESSIONS_DIR")
    if env:
        return str(env)
    files_cfg = _get_dict("/files")
    if "sessions_dir" not in files_cfg:
        raise ValueError("resources/config/files.json: missing required key 'sessions_dir'")
    return str(files_cfg["sessions_dir"])


def get_maps_config() -> Dict[str, Any]:
    """Per-map configs keyed by map name."""
    return _get_dict("/maps")


def get_model_config(model_key: str) -> Dict[str, Any]:
    """Shared model architecture params (hidden_size, num_layers, dropout, device)."""
    return _get_dict(f"/models/{model_key}/model")


def get_bc_config(model_key: str) -> Dict[str, Any]:
    """BC trainer settings."""
    return _get_dict(f"/models/{model_key}/bc")


def get_sac_config(model_key: str) -> Dict[str, Any]:
    """SAC trainer settings."""
    return _get_dict(f"/models/{model_key}/sac")


def get_joint_probe_config(model_key: str) -> Dict[str, Any]:
    """Joint VR+shooting probe schema: per-head (steering / binary /
    categorical_target) limits + cross-head consistency floors. Top-level
    ``probe.json`` in ``resources/models/<model_key>/``.
    """
    return _get_dict(f"/models/{model_key}/probe")


def get_export_gate_config(model_key: str) -> Dict[str, Any]:
    """Top-level ``export_gate.json`` content — voor het joint rl_pawn model
    een :class:`DualKPIDeltaGateConfig` schema (dual_kpi=true).
    """
    return _get_dict(f"/models/{model_key}/export_gate")


def get_rewards_config(model_key: str) -> Dict[str, Any]:
    """Reward settings (consumed by SAC and BC)."""
    return _get_dict(f"/models/{model_key}/rewards")


_JOINT_REWARDGROUP_PLACEHOLDERS = (
    "rewardgroup0", "rewardgroup1", "rewardgroup2", "rewardgroup3",
)


def _rewardgroup_feature_names(model_key: str) -> List[str]:
    rewards_cfg = get_rewards_config(model_key)
    rewardgroups = rewards_cfg.get("rewardgroups")
    if isinstance(rewardgroups, dict):
        names: List[str] = []
        for key, group in rewardgroups.items():
            if key == "default":
                continue
            if not isinstance(group, dict) or not str(group.get("name", "")).strip():
                raise ValueError(
                    f"{model_key}/rewards.json: rewardgroups.{key}.name must be a non-empty string"
                )
            names.append(str(key))
        if not names:
            raise ValueError(
                f"{model_key}/rewards.json: rewardgroups must contain at least one non-default group"
            )
        return names

    # Backwards compatibility for old role-keyed configs.
    rewards = rewards_cfg.get("rewards")
    if isinstance(rewards, dict):
        return [f"bot_role_{str(key).lower()}" for key in rewards.keys()]

    # Joint VR+shooting (vr-shooting-sac-merge.md): rewards.json gebruikt
    # weights-only schema (geen rewardgroups, geen role-keyed rewards) omdat
    # de joint reward-pipeline een eenvoudige scalar-weights-decomp draait.
    # features.json verwijst nog wel naar 4 rewardgroup placeholder kanalen
    # via features_from=rewardgroups (matched aan VR/movement/shooting
    # convention). Materialiseer ze hier expliciet zodat de feature-expansion
    # blijft werken zonder de hele rewardgroups blueprint te dupliceren.
    if "weights" in rewards_cfg and isinstance(rewards_cfg["weights"], dict):
        return list(_JOINT_REWARDGROUP_PLACEHOLDERS)

    raise ValueError(f"{model_key}/rewards.json: missing rewardgroups object")



_ALLOWED_TARGET_FEATURE_TYPES = {"steering", "continuous", "binary"}

# Phase 2: aux target columns are written to CSV but NOT part of model.output_size.
# Consumed by aux losses in Python BC training (e.g. target_index categorical CE).
# - categorical_N: integer label in [0, N) — used for target_index over enemy slots
# - weight: float in [0.0, 1.0] — per-sample loss confidence weight
_ALLOWED_AUX_TARGET_FEATURE_TYPES = {"categorical_5", "weight"}


def get_features(model_key: str) -> Dict[str, Any]:
    """Feature lists (timeline_features, single_features, target_features).

    target_features is normalized at read-time:
    - features.json source-of-truth is a list of {name, type} objects.
    - Returned dict has target_features = List[str] (backwards-compat for the
      30+ readers across BC/SAC/Java) and an additional
      target_feature_types = Dict[str,str] mapping each name to its semantic
      type (steering | continuous | binary). The probe consumes the types map.
    """
    cfg = deepcopy(_get_dict(f"/models/{model_key}/features"))
    raw_groups = cfg.get("feature_groups")
    if isinstance(raw_groups, list):
        expanded_groups = []
        for group in raw_groups:
            if isinstance(group, dict) and group.get("features_from") == "rewardgroups":
                group = dict(group)
                group["features"] = _rewardgroup_feature_names(model_key)
            elif isinstance(group, dict) and group.get("features_from"):
                raise ValueError(
                    f"{model_key}/features.json: unsupported features_from={group.get('features_from')!r}"
                )
            expanded_groups.append(group)
        cfg["feature_groups"] = expanded_groups
    raw_targets = cfg.get("target_features")
    if not isinstance(raw_targets, list) or len(raw_targets) == 0:
        raise ValueError(
            f"{model_key}/features.json: target_features must be a non-empty list"
        )
    if not all(isinstance(t, dict) for t in raw_targets):
        raise ValueError(
            f"{model_key}/features.json: target_features must be a list of"
            f" {{\"name\": ..., \"type\": ...}} objects."
            f" Allowed types: {sorted(_ALLOWED_TARGET_FEATURE_TYPES)}."
        )
    names: List[str] = []
    types: Dict[str, str] = {}
    for entry in raw_targets:
        if "name" not in entry or "type" not in entry:
            raise ValueError(
                f"{model_key}/features.json: target_features entry {entry!r}"
                f" missing required key 'name' or 'type'"
            )
        name = str(entry["name"])
        ttype = str(entry["type"])
        if ttype not in _ALLOWED_TARGET_FEATURE_TYPES:
            raise ValueError(
                f"{model_key}/features.json: target_features entry '{name}' has"
                f" type={ttype!r}; allowed: {sorted(_ALLOWED_TARGET_FEATURE_TYPES)}"
            )
        names.append(name)
        types[name] = ttype
    cfg["target_features"] = names
    cfg["target_feature_types"] = types

    # Phase 2: optional aux_target_features (e.g. target_index, target_index_confidence
    # for the shooting model's attention head). Empty list when missing.
    raw_aux = cfg.get("aux_target_features", [])
    aux_names: List[str] = []
    if isinstance(raw_aux, list):
        for entry in raw_aux:
            if not isinstance(entry, dict) or "name" not in entry or "type" not in entry:
                raise ValueError(
                    f"{model_key}/features.json: aux_target_features entry {entry!r}"
                    f" must be a {{\"name\": ..., \"type\": ...}} object."
                    f" Allowed types: {sorted(_ALLOWED_AUX_TARGET_FEATURE_TYPES)}."
                )
            name = str(entry["name"])
            ttype = str(entry["type"])
            if ttype not in _ALLOWED_AUX_TARGET_FEATURE_TYPES:
                raise ValueError(
                    f"{model_key}/features.json: aux_target_features entry '{name}' has"
                    f" type={ttype!r}; allowed: {sorted(_ALLOWED_AUX_TARGET_FEATURE_TYPES)}"
                )
            aux_names.append(name)
    cfg["aux_target_features"] = aux_names
    return cfg
