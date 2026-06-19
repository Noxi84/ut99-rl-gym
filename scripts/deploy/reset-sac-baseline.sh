#!/bin/bash
# Reset SAC baseline and best return so training continues from current performance.
# Stops SAC, resets checkpoint metrics on the trainer machine, restarts SAC.
#
# Usage:
#   bash scripts/deploy/reset-sac-baseline.sh                    # All SAC models
#   bash scripts/deploy/reset-sac-baseline.sh rl_pawn    # Specific model

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

# Assign models to workers (same logic as train-sac.sh)
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESET_PY_LOCAL="$SCRIPT_DIR/reset_sac_checkpoint.py"

echo "=========================================="
echo "  RESET SAC BASELINE"
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
    ckpt_path="$SESSIONS_DIR/models/trainingmodel/${mk}_sac_checkpoint.pt"

    echo "--- $mk on $target_mid ---"

    # Stop SAC
    echo "  Stopping $session_name..."
    ssh_cmd_quiet "$target_host" "$target_user" "$target_pass" \
        "tmux kill-session -t $session_name 2>/dev/null || true" || true

    # Reset checkpoint
    echo "  Resetting checkpoint..."
    sshpass -p "$target_pass" scp $SSH_OPTS "$RESET_PY_LOCAL" "$target_user@$target_host:/tmp/reset_sac_checkpoint.py" 2>/dev/null
    sshpass -p "$target_pass" ssh $SSH_OPTS "$target_user@$target_host" \
        "$VENV_PYTHON /tmp/reset_sac_checkpoint.py '$ckpt_path'"

    echo ""
done

# Restart SAC
echo "Restarting SAC for: ${TARGET_MODELS[*]}"
bash "$(cd "$(dirname "$0")" && pwd)/train-sac.sh" "${TARGET_MODELS[@]}"
