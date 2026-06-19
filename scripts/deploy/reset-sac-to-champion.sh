#!/bin/bash
# Restore SAC training state to the newest promoted champion (champion -> current).
#
# Per model: stop the SAC trainer, then run train.common.restore_to_champion on
# the assigned trainer worker. That copies the champion .pt into the SAC bootstrap
# ladder (_sac_best.pt + _sac.pt), copies the champion ONNX over the live
# trainingmodel ONNX and pushes it to every server, drops stale SAC
# checkpoint/inflight/delta state, then this script restarts SAC so it bootstraps
# from the champion.
#
# Offline, "hard" counterpart to the DeltaGate's in-trainer actor-only rollback
# (training_loop._rollback_joint_to_champion). Use when the live SAC policy is
# disturbed and you want every bot back on the last champion NOW, with a wiped
# replay buffer. Driven by deploy.json `reset-current-to-last-champion` (implies
# train-sac + clean-experience). The deploy pipeline defers bot startup until
# after this script's ONNX push so bots come up on the champion.
#
# Usage:
#   bash scripts/deploy/reset-sac-to-champion.sh                  # All SAC models
#   bash scripts/deploy/reset-sac-to-champion.sh rl_pawn          # Specific model

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

# Assign models to workers (same logic as train-sac.sh / reset-sac-*.sh)
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
echo "  RESTORE SAC TO CHAMPION (champion -> current)"
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

    echo "--- $mk on $target_mid ---"

    # Stop SAC. tmux kill-session is the primary stop; the pkill is a fallback for
    # a trainer running outside tmux. (tmux runs first so the trainer is already
    # gone before pkill — which can match its own remote shell — fires.)
    echo "  Stopping $session_name..."
    ssh_cmd_quiet "$target_host" "$target_user" "$target_pass" \
        "tmux kill-session -t $session_name 2>/dev/null || true; pkill -f '${mk}.trainSAC' 2>/dev/null || true" || true

    # Restore champion (.pt into ladder + ONNX over live model + push to all
    # servers). Runs on the worker where trainingmodel/ lives and from which
    # ModelSync reaches the other servers. Hard-fails on incompatible/missing
    # champion — abort the whole reset rather than restart SAC on a half-restore.
    echo "  Restoring champion (.pt + ONNX) and pushing to all servers..."
    if ! ssh_cmd "$target_host" "$target_user" "$target_pass" \
        "cd $REMOTE_DIR && $VENV_PYTHON -m train.common.restore_to_champion '$mk'"; then
        echo "ERROR: champion restore failed for $mk (see output above). Aborting."
        exit 1
    fi

    echo ""
done

# Restart SAC — it bootstraps from the restored _sac_best.pt (= champion).
echo "Restarting SAC for: ${TARGET_MODELS[*]}"
bash "$(cd "$(dirname "$0")" && pwd)/train-sac.sh" "${TARGET_MODELS[@]}"
