#!/bin/bash
# Hard reset SAC: stop trainer, wipe _sac.pt + _sac_best.pt + _sac_checkpoint.pt
# (and the corresponding _sac_best.onnx) so the trainer falls back to BC weights
# at next start, then restart SAC.
#
# Use when the SAC policy has collapsed into a local optimum that the soft
# `reset-sac-baseline` cannot escape (e.g. action-mode collapse where an action
# dimension drifts to a saturated value the gradient can no longer escape from).
# Soft reset only resets the metric counter; this script also discards the
# weights so learning truly restarts from BC.
#
# Usage:
#   bash scripts/deploy/reset-sac-to-bc-baseline.sh                  # All SAC models
#   bash scripts/deploy/reset-sac-to-bc-baseline.sh rl_pawn  # Specific model

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

parse_servers_conf
find_primary_trainer || exit 1
get_trainer_workers sac
resolve_sac_model_keys

if [ ${#SAC_MODEL_KEYS[@]} -eq 0 ]; then
    echo "No SAC-enabled models found."
    exit 0
fi

ENABLED_MODELS=("${SAC_MODEL_KEYS[@]}")
TARGET_MODELS=()
for arg in "$@"; do
    case "$arg" in
        --*) ;;
        *) TARGET_MODELS+=("$arg") ;;
    esac
done

if [ ${#TARGET_MODELS[@]} -eq 0 ]; then
    TARGET_MODELS=("${ENABLED_MODELS[@]}")
fi

if [ ${#TRAINER_WORKER_MACHINE_IDS[@]} -eq 0 ]; then
    echo "ERROR: no SAC trainer workers available"
    exit 1
fi

# Assign models to workers (same logic as train-sac.sh / reset-sac-baseline.sh)
declare -A ASSIGN_MACHINE=()
declare -A MACHINE_LOADS=()

for mk in "${TARGET_MODELS[@]}"; do
    local_best_mid=""
    local_best_load=999
    for i in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        mid="${TRAINER_WORKER_MACHINE_IDS[$i]}"
        load="${MACHINE_LOADS[$mid]:-0}"
        if [ -z "$local_best_mid" ] || [ "$load" -lt "$local_best_load" ]; then
            local_best_mid="$mid"
            local_best_load="$load"
        fi
    done
    ASSIGN_MACHINE["$mk"]="$local_best_mid"
    MACHINE_LOADS["$local_best_mid"]=$(( ${MACHINE_LOADS[$local_best_mid]:-0} + 1 ))
done

find_worker_index_by_machine_id() {
    local needle="$1"
    for i in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        if [ "${TRAINER_WORKER_MACHINE_IDS[$i]}" = "$needle" ]; then
            echo "$i"
            return 0
        fi
    done
    return 1
}

echo "=========================================="
echo "  RESET SAC TO BC BASELINE (hard reset)"
echo "  Target models: ${TARGET_MODELS[*]}"
echo "=========================================="

for mk in "${TARGET_MODELS[@]}"; do
    worker_idx=$(find_worker_index_by_machine_id "${ASSIGN_MACHINE[$mk]}") || {
        echo "ERROR: could not resolve worker for ${ASSIGN_MACHINE[$mk]}"
        exit 1
    }
    target_host="${TRAINER_WORKER_HOSTS[$worker_idx]}"
    target_user="${TRAINER_WORKER_USERS[$worker_idx]}"
    target_pass="${TRAINER_WORKER_PASSES[$worker_idx]}"
    target_mid="${TRAINER_WORKER_MACHINE_IDS[$worker_idx]}"
    session_name="sac_${mk}"
    model_dir="$SESSIONS_DIR/models/trainingmodel"

    echo "--- $mk on $target_mid ---"

    echo "  Stopping $session_name..."
    ssh_cmd_quiet "$target_host" "$target_user" "$target_pass" \
        "tmux kill-session -t $session_name 2>/dev/null || true; pkill -f '${mk}.trainSAC' 2>/dev/null || true" || true

    # Files to wipe: SAC weights (current + best, including critic + temperature
    # in checkpoint metadata), checkpoint metadata, DeltaGate baseline, and the SAC-best ONNX. We
    # deliberately KEEP <mk>.pt (BC weights) and <mk>.onnx (currently active
    # model on bots — gets replaced by next SAC export, or by trainer's initial
    # export if the current ONNX is corrupted).
    echo "  Wiping SAC state (falls back to BC at next start)..."
    ssh_cmd_quiet "$target_host" "$target_user" "$target_pass" \
        "rm -fv '$model_dir/${mk}_sac.pt' '$model_dir/${mk}_sac_best.pt' \
                '$model_dir/${mk}_sac_checkpoint.pt' '$model_dir/${mk}_sac_best.onnx' \
                '$model_dir/${mk}_sac_best.onnx.data' '$model_dir/${mk}_sac_inflight.pt' \
                '$model_dir/${mk}_sac_delta_baseline.pt' \
                '$model_dir/${mk}_sac_delta_baseline.onnx' \
                '$model_dir/${mk}_sac_delta_baseline.onnx.data' \
                '$model_dir/${mk}_sac_delta_baseline.json' 2>&1 | sed 's/^/    /'" || true

    echo ""
done

echo "Restarting SAC for: ${TARGET_MODELS[*]}"
bash "$(cd "$(dirname "$0")" && pwd)/train-sac.sh" "${TARGET_MODELS[@]}"
