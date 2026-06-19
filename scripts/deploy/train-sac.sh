#!/bin/bash
# Start or restart SAC training on trainer worker machines.
#
# Usage:
#   bash scripts/deploy/train-sac.sh                     # All enabled SAC models
#   bash scripts/deploy/train-sac.sh rl_pawn     # Restart specific model

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

parse_servers_conf
find_primary_trainer || exit 1
get_trainer_workers sac
resolve_sac_model_keys

if [ ${#SAC_MODEL_KEYS[@]} -eq 0 ]; then
    echo "No models with experience_sync_enabled=true in sac.json found. Skipping SAC."
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
    echo "ERROR: no SAC trainer workers available (sac_trainer_slots > 0)"
    exit 1
fi

model_is_enabled() {
    local needle="$1"
    for mk in "${ENABLED_MODELS[@]}"; do
        [ "$mk" = "$needle" ] && return 0
    done
    return 1
}

for mk in "${TARGET_MODELS[@]}"; do
    if ! model_is_enabled "$mk"; then
        echo "ERROR: model '$mk' is not SAC-enabled in resources/models/*/sac.json"
        exit 1
    fi
done

# Simple assignment: first available trainer worker for each model
declare -A ASSIGN_MACHINE=()
declare -A MACHINE_LOADS=()
BUSY_LIST=" ${BC_BUSY_MACHINES:-} "

for mk in "${TARGET_MODELS[@]}"; do
    # Pick trainer with lowest load, skipping machines currently running BC.
    local_best_mid=""
    local_best_load=999
    for i in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        mid="${TRAINER_WORKER_MACHINE_IDS[$i]}"
        if [[ "$BUSY_LIST" == *" $mid "* ]]; then
            continue
        fi
        load="${MACHINE_LOADS[$mid]:-0}"
        if [ -z "$local_best_mid" ] || [ "$load" -lt "$local_best_load" ]; then
            local_best_mid="$mid"
            local_best_load="$load"
        fi
    done
    if [ -z "$local_best_mid" ]; then
        echo "ERROR: no SAC trainer worker available for $mk (all excluded via BC_BUSY_MACHINES='${BC_BUSY_MACHINES:-}')"
        exit 1
    fi
    ASSIGN_MACHINE["$mk"]="$local_best_mid"
    MACHINE_LOADS["$local_best_mid"]=$(( ${MACHINE_LOADS[$local_best_mid]:-0} + 1 ))
done

# Publish assignment file
TMP_ASSIGN=$(mktemp)
trap 'rm -f "$TMP_ASSIGN"' EXIT
{
    echo "# model_key machine_id"
    for mk in "${ENABLED_MODELS[@]}"; do
        [ -n "${ASSIGN_MACHINE[$mk]:-}" ] && echo "$mk ${ASSIGN_MACHINE[$mk]}"
    done
} > "$TMP_ASSIGN"

for i in "${!SERVERS[@]}"; do
    sac_slots="${SAC_TRAINER_SLOTS[$i]:-0}"
    [ "$sac_slots" -gt 0 ] || continue
    ssh_cmd_quiet "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" "mkdir -p '$SESSIONS_DIR'" || true
    sshpass -p "${PASSES[$i]}" ssh $SSH_OPTS "${USERS[$i]}@${SERVERS[$i]}" \
        "cat > '${SAC_ASSIGNMENTS_PATH}.tmp' && mv -f '${SAC_ASSIGNMENTS_PATH}.tmp' '$SAC_ASSIGNMENTS_PATH'" < "$TMP_ASSIGN" 2>/dev/null || true
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

# Sync .pt checkpoints from primary trainer (BC output) to SAC worker machines.
# SAC needs .pt files to bootstrap from BC — ONNX sync alone is not enough.
MODEL_DIR="$SESSIONS_DIR/models/trainingmodel"
PRIMARY_IDX=$(find_server_index_by_machine_id "$TRAINER_MACHINE_ID") || true
if [ -n "${PRIMARY_IDX:-}" ]; then
    PRIMARY_HOST="${SERVERS[$PRIMARY_IDX]}"
    PRIMARY_USER="${USERS[$PRIMARY_IDX]}"
    PRIMARY_PASS="${PASSES[$PRIMARY_IDX]}"
    for w in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        wmid="${TRAINER_WORKER_MACHINE_IDS[$w]}"
        [ "$wmid" = "$TRAINER_MACHINE_ID" ] && continue
        whost="${TRAINER_WORKER_HOSTS[$w]}"
        wuser="${TRAINER_WORKER_USERS[$w]}"
        wpass="${TRAINER_WORKER_PASSES[$w]}"
        echo "  Syncing .pt checkpoints: $TRAINER_MACHINE_ID -> $wmid"
        ssh_cmd_quiet "$whost" "$wuser" "$wpass" "mkdir -p '$MODEL_DIR'" || true
        sshpass -p "$PRIMARY_PASS" ssh $SSH_OPTS "${PRIMARY_USER}@${PRIMARY_HOST}" \
            "sshpass -p '$wpass' rsync -az ${MODEL_DIR}/rl_*.pt ${wuser}@${whost}:${MODEL_DIR}/" \
            2>/dev/null || echo "  WARNING: .pt sync failed from $TRAINER_MACHINE_ID to $wmid"
    done
fi

echo "=========================================="
echo "  START SAC TRAINER"
echo "  Enabled SAC models: ${ENABLED_MODELS[*]}"
echo "  Target models: ${TARGET_MODELS[*]}"
for mk in "${TARGET_MODELS[@]}"; do
    echo "  Assign: $mk -> ${ASSIGN_MACHINE[$mk]}"
done
echo "=========================================="

# Kill existing SAC sessions for target models
for mk in "${TARGET_MODELS[@]}"; do
    for w in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        ssh_cmd_quiet "${TRAINER_WORKER_HOSTS[$w]}" "${TRAINER_WORKER_USERS[$w]}" "${TRAINER_WORKER_PASSES[$w]}" \
            "tmux kill-session -t sac_${mk} 2>/dev/null || true; pkill -f '${mk}.trainSAC' 2>/dev/null || true" \
            || true
    done
done

# Start SAC trainers
for mk in "${TARGET_MODELS[@]}"; do
    worker_idx=$(find_worker_index_by_machine_id "${ASSIGN_MACHINE[$mk]}") || {
        log_fail "Could not resolve worker index for ${ASSIGN_MACHINE[$mk]}"
        exit 1
    }
    session_name="sac_${mk}"
    log_name="${mk}_sac"
    target_host="${TRAINER_WORKER_HOSTS[$worker_idx]}"
    target_user="${TRAINER_WORKER_USERS[$worker_idx]}"
    target_pass="${TRAINER_WORKER_PASSES[$worker_idx]}"
    target_mid="${TRAINER_WORKER_MACHINE_IDS[$worker_idx]}"

    log_step "Starting SAC for $mk on $target_mid (session: $session_name)..."
    ssh_cmd_quiet "$target_host" "$target_user" "$target_pass" \
        "mkdir -p /tmp/ut99-multi && tmux new-session -d -s $session_name 'cd $REMOTE_DIR && export UT99_SESSIONS_DIR=$SESSIONS_DIR && export PYTHONPATH=$REMOTE_DIR && export UT99_MACHINE_ID=$target_mid && ${UT99_MODEL_CONFIG_DIR:+export UT99_MODEL_CONFIG_DIR=$UT99_MODEL_CONFIG_DIR &&} $VENV_PYTHON -m train.rl.${mk}.trainSAC 2>&1 | tee /tmp/ut99-multi/${log_name}.log'" \
        || true
done

# Verify
sleep 3
all_ok=true
for mk in "${TARGET_MODELS[@]}"; do
    worker_idx=$(find_worker_index_by_machine_id "${ASSIGN_MACHINE[$mk]}") || continue
    session_name="sac_${mk}"
    target_host="${TRAINER_WORKER_HOSTS[$worker_idx]}"
    target_user="${TRAINER_WORKER_USERS[$worker_idx]}"
    target_pass="${TRAINER_WORKER_PASSES[$worker_idx]}"
    target_mid="${TRAINER_WORKER_MACHINE_IDS[$worker_idx]}"
    sac_running=$(ssh_cmd "$target_host" "$target_user" "$target_pass" \
        "tmux has-session -t $session_name 2>/dev/null && echo yes || echo no" \
        2>/dev/null || echo "?")

    if [ "$sac_running" = "yes" ]; then
        log_ok "SAC $mk started on $target_mid (session: $session_name)"
    else
        log_fail "SAC $mk failed to start on $target_mid"
        all_ok=false
    fi
done

if [ "$all_ok" != "true" ]; then
    exit 1
fi
