"""Load deploy.json and emit bash declarations for sourcing.

Usage:
    python3 load_deploy_config.py <deploy.json> <index.json>

Stdout: bash declarations (eval-able)
Stderr: validation errors and INFO messages
Exit code: 0 on success, 1 on validation failure.
"""
import json
import sys
from pathlib import Path

GLOBAL_BOOL_FIELDS = [
    "restart-bots", "clean-logs", "extract-map-bounds",
]
MODEL_BOOL_FIELDS = [
    "clean-experience",
    "prepare-training-csv",
    "keep-existing-model",
    "train-bc",
    "train-sac",
    "reset-sac-baseline",
    "reset-sac-to-bc-baseline",
    "reset-current-to-last-champion",
    "reset-champions",
    "convert-from-jsons",
    "replay-export",
]
FLAG_TO_ARRAY = {
    "clean-experience": "MODELS_CLEAN_EXPERIENCE",
    "prepare-training-csv": "MODELS_PREPARE_CSV",
    "keep-existing-model": "MODELS_KEEP_EXISTING",
    "train-bc": "MODELS_TRAIN_BC",
    "train-sac": "MODELS_TRAIN_SAC",
    "reset-sac-baseline": "MODELS_RESET_SAC_BASELINE",
    "reset-sac-to-bc-baseline": "MODELS_RESET_SAC_TO_BC_BASELINE",
    "reset-current-to-last-champion": "MODELS_RESET_CURRENT_TO_CHAMPION",
    "reset-champions": "MODELS_RESET_CHAMPIONS",
    "convert-from-jsons": "MODELS_CONVERT_FROM_JSONS",
    "replay-export": "MODELS_REPLAY_EXPORT",
}


def fail(msg: str) -> None:
    print(msg, file=sys.stderr)
    sys.exit(1)


def info(msg: str) -> None:
    print(msg, file=sys.stderr)


def shell_quote(s: str) -> str:
    return "'" + s.replace("'", r"'\''") + "'"


def emit_array(name: str, values: list) -> None:
    quoted = " ".join(shell_quote(v) for v in values)
    print(f"{name}=({quoted})")


def _validate_baseline(deploy_path: Path) -> None:
    """Joint baseline.json sanity checks (all warnings, no crashes).

    Verifies the DualKPIDeltaGate inputs in ``resources/models/rl_pawn/baseline.json``:
    null fields, out-of-range values, window-config bounds, sample activity coverage.
    """
    baseline_path = deploy_path.parent.parent / "models" / "rl_pawn" / "baseline.json"
    if not baseline_path.is_file():
        info(f"WARN: {baseline_path} ontbreekt — DualKPIDeltaGate kan geen ratios berekenen; "
             f"trainer valt terug op placeholder mode (zie training_loop._load_baselines).")
        return

    try:
        baseline = json.loads(baseline_path.read_text())
    except json.JSONDecodeError as e:
        fail(f"ERROR: invalid JSON in {baseline_path}: {e}")
        return

    baseline_keys = (
        "decoupled_movement_flag_score",
        "decoupled_shooting_combat_score",
        "decoupled_vr_shots_on_target_rate",
    )
    null_keys = [k for k in baseline_keys if baseline.get(k) is None]
    if null_keys:
        info(f"WARN: baseline.json heeft null velden: {null_keys}. "
             f"DualKPIDeltaGate draait in placeholder mode (ratios absoluut), "
             f"geen champion-promote mogelijk. Vul de waarden in voordat joint "
             f"training champions kan promoten.")

    combat = baseline.get("decoupled_shooting_combat_score")
    if combat is not None and not (0.0 <= float(combat) <= 20.0):
        info(f"WARN: baseline.json decoupled_shooting_combat_score={combat} ligt buiten "
             f"realistische UT99 CTF range [0.0, 20.0]. "
             f"DualKPIDeltaGate gebruikt deze waarde als deler.")
    aim = baseline.get("decoupled_vr_shots_on_target_rate")
    if aim is not None and not (0.0 <= float(aim) <= 30.0):
        info(f"WARN: baseline.json decoupled_vr_shots_on_target_rate={aim} ligt buiten "
             f"realistische per-minute range [0.0, 30.0]. "
             f"DualKPIDeltaGate gebruikt deze waarde als shots-on-target/min deler.")
    movement = baseline.get("decoupled_movement_flag_score")
    if movement is not None and not (0.0 <= float(movement) <= 10.0):
        info(f"WARN: baseline.json decoupled_movement_flag_score={movement} ligt buiten "
             f"realistische flag_score range [0.0, 10.0]. Controleer dat dit "
             f"1*taken + 7*captured + 3*returned per minuut is.")

    window_minutes = baseline.get("measurement_window_minutes")
    if window_minutes is not None:
        if not isinstance(window_minutes, int) or not (1 <= window_minutes <= 30):
            info(f"WARN: baseline.json measurement_window_minutes={window_minutes} ligt "
                 f"buiten range [1, 30]. Default = 5 min/venster.")
    window_count = baseline.get("measurement_window_count")
    if window_count is not None:
        if not isinstance(window_count, int) or not (3 <= window_count <= 50):
            info(f"WARN: baseline.json measurement_window_count={window_count} ligt "
                 f"buiten range [3, 50]. Default = 10 vensters.")


def main() -> None:
    if len(sys.argv) != 3:
        fail(f"Usage: {sys.argv[0]} <deploy.json> <index.json>")
    deploy_path = Path(sys.argv[1])
    index_path = Path(sys.argv[2])

    if not deploy_path.is_file():
        fail(f"ERROR: {deploy_path} not found")
    if not index_path.is_file():
        fail(f"ERROR: {index_path} not found")

    try:
        cfg = json.loads(deploy_path.read_text())
    except json.JSONDecodeError as e:
        fail(f"ERROR: invalid JSON in {deploy_path}: {e}")
    try:
        index = json.loads(index_path.read_text())
    except json.JSONDecodeError as e:
        fail(f"ERROR: invalid JSON in {index_path}: {e}")

    valid_models = {m["model_key"] for m in index.get("models", []) if m.get("model_key")}
    if not valid_models:
        fail(f"ERROR: no models in {index_path}")

    if "hosts" not in cfg:
        fail("ERROR: deploy.json missing 'hosts' (use [] for all servers)")
    hosts = cfg["hosts"]
    if not isinstance(hosts, list):
        fail("ERROR: deploy.json 'hosts' must be a list")
    for h in hosts:
        if not isinstance(h, str) or not h:
            fail(f"ERROR: deploy.json 'hosts' entries must be non-empty strings, got {h!r}")

    for fld in GLOBAL_BOOL_FIELDS:
        if fld not in cfg:
            fail(f"ERROR: deploy.json missing global '{fld}'")
        if not isinstance(cfg[fld], bool):
            fail(f"ERROR: deploy.json '{fld}' must be true/false")

    if "models" not in cfg or not isinstance(cfg["models"], dict):
        fail("ERROR: deploy.json 'models' must be an object")
    models_cfg = cfg["models"]

    for mk in models_cfg:
        if mk not in valid_models:
            fail(
                f"ERROR: deploy.json model '{mk}' not in resources/models/index.json "
                f"(valid: {sorted(valid_models)})"
            )
    for mk in valid_models:
        if mk not in models_cfg:
            fail(f"ERROR: deploy.json missing model entry for '{mk}'")

    for mk, mcfg in models_cfg.items():
        if not isinstance(mcfg, dict):
            fail(f"ERROR: deploy.json models['{mk}'] must be an object")
        for fld in MODEL_BOOL_FIELDS:
            if fld not in mcfg:
                fail(f"ERROR: deploy.json models['{mk}'] missing '{fld}'")
            if not isinstance(mcfg[fld], bool):
                fail(f"ERROR: deploy.json models['{mk}']['{fld}'] must be true/false")

    for mk, mcfg in models_cfg.items():
        if mcfg["reset-sac-baseline"] and not mcfg["train-sac"]:
            info(f"INFO: enabling train-sac for {mk} (implied by reset-sac-baseline)")
            mcfg["train-sac"] = True
        if mcfg["reset-sac-to-bc-baseline"] and not mcfg["train-sac"]:
            info(f"INFO: enabling train-sac for {mk} (implied by reset-sac-to-bc-baseline)")
            mcfg["train-sac"] = True
        if mcfg["reset-sac-baseline"] and mcfg["reset-sac-to-bc-baseline"]:
            fail(f"ERROR: deploy.json models['{mk}'] has both reset-sac-baseline and "
                 f"reset-sac-to-bc-baseline=true (mutually exclusive — pick one). "
                 f"reset-sac-baseline = soft (only resets best_mean_return + baseline_return), "
                 f"reset-sac-to-bc-baseline = hard (also wipes _sac.pt + _sac_best.pt "
                 f"so trainer falls back to BC weights).")
        if mcfg["reset-current-to-last-champion"]:
            if not mcfg["train-sac"]:
                info(f"INFO: enabling train-sac for {mk} (implied by reset-current-to-last-champion)")
                mcfg["train-sac"] = True
            # The restore wipes the on-disk buffer feed and defers bot startup so
            # the champion ONNX is live before the buffer refills. Without
            # clean-experience the pre-deploy NPZ stash (written while the bots ran
            # the disturbed policy) would be ingested at trainer restart and the
            # restored champion would immediately train on disturbed transitions.
            if not mcfg["clean-experience"]:
                info(f"INFO: enabling clean-experience for {mk} (implied by reset-current-to-last-champion)")
                mcfg["clean-experience"] = True
            conflicts = [f for f in (
                "reset-sac-baseline",
                "reset-sac-to-bc-baseline",
                "reset-champions",
                "train-bc",
            ) if mcfg[f]]
            if conflicts:
                fail(f"ERROR: deploy.json models['{mk}'] reset-current-to-last-champion=true "
                     f"is mutually exclusive with {conflicts}. It restores the LAST PROMOTED "
                     f"champion's weights into the SAC bootstrap ladder, which conflicts with: "
                     f"reset-sac-baseline (soft metric-only reset), reset-sac-to-bc-baseline "
                     f"(resets to BC weights instead), reset-champions (wipes the very champion "
                     f"you want to restore to), and train-bc (overwrites the model via BC). "
                     f"Pick one model-state operation.")

    # recordings_sync — global toggle: bots run in CAPTURE mode, all sources
    # sync to recording_server. Standalone (independent of replay-export).
    if "recordings_sync" not in cfg or not isinstance(cfg["recordings_sync"], dict):
        fail("ERROR: deploy.json missing 'recordings_sync' object")
    rec_sync = cfg["recordings_sync"]
    if "enabled" not in rec_sync or not isinstance(rec_sync["enabled"], bool):
        fail("ERROR: deploy.json 'recordings_sync.enabled' must be true/false")

    # replay-export advisories (per model). Both used to be hard fails but turned
    # out too strict for the common "keep training the existing model, just feed
    # it more reward-tuned experience" workflow.
    for mk, mcfg in models_cfg.items():
        if mcfg["replay-export"]:
            if not mcfg["clean-experience"]:
                info(f"WARN: deploy.json models['{mk}'] replay-export=true with "
                     f"clean-experience=false — fresh batch_replay-*.npz will be MIXED "
                     f"with whatever is already in rl-replay-buffer/{mk}/. Set "
                     f"clean-experience=true if you want a pure replay buffer.")
            if not (mcfg["reset-sac-baseline"] or mcfg["reset-sac-to-bc-baseline"]):
                info(f"INFO: deploy.json models['{mk}'] replay-export=true without any "
                     f"reset-*-baseline — existing checkpoint + best_mean_return are "
                     f"preserved. The trainer continues from the current policy and "
                     f"absorbs the new experience.")

    for mk, mcfg in models_cfg.items():
        if mcfg["convert-from-jsons"] and not mcfg["replay-export"]:
            fail(f"ERROR: deploy.json models['{mk}'] convert-from-jsons=true requires "
                 f"replay-export=true (otherwise the converted .rec.gz never reach the trainer)")

    for mk, mcfg in models_cfg.items():
        if mcfg["reset-sac-to-bc-baseline"] and not mcfg["clean-experience"]:
            fail(f"ERROR: deploy.json models['{mk}'] reset-sac-to-bc-baseline=true requires "
                 f"clean-experience=true. Otherwise pending batch_*.npz files written by "
                 f"bots before the deploy stop (with the OLD rewards-config) are loaded "
                 f"into the fresh in-memory ReplayBuffer at trainer restart, and the "
                 f"BC-baseline actor immediately trains on a critic fit to the old "
                 f"rewards. Set clean-experience=true to wipe the pending NPZ stash.")

    _validate_baseline(deploy_path)

    print(f"DEPLOY_RESTART_BOTS={'true' if cfg['restart-bots'] else 'false'}")
    print(f"DEPLOY_CLEAN_LOGS={'true' if cfg['clean-logs'] else 'false'}")
    print(f"DEPLOY_EXTRACT_MAP_BOUNDS={'true' if cfg['extract-map-bounds'] else 'false'}")
    print(f"DEPLOY_RECORDINGS_SYNC={'true' if rec_sync['enabled'] else 'false'}")
    emit_array("DEPLOY_HOSTS", hosts)

    for fld, arr_name in FLAG_TO_ARRAY.items():
        models_with = sorted(mk for mk, mcfg in models_cfg.items() if mcfg[fld])
        emit_array(arr_name, models_with)


if __name__ == "__main__":
    main()
