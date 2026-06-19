#!/bin/bash
# Run BC (behavioral cloning) pre-training on trainer worker machines.
# Models are distributed across available trainer slots (from servers.json).
# If only one model is requested, it trains on the primary trainer.
#
# Model keys are resolved from roles.json (via common.sh resolve_role_model_keys).
# Can be overridden via CLI args.
#
# Usage:
#   bash scripts/deploy/train-bc.sh                         # all models, parallel
#   bash scripts/deploy/train-bc.sh rl_pawn              # single model
#   bash scripts/deploy/train-bc.sh rl_pawn

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

parse_servers_conf
find_primary_trainer || exit 1
get_trainer_workers bc
resolve_all_model_keys

# Parse arguments
MODEL_KEYS=()
for arg in "$@"; do
    MODEL_KEYS+=("$arg")
done
if [ ${#MODEL_KEYS[@]} -eq 0 ]; then
    MODEL_KEYS=("${ALL_MODEL_KEYS[@]}")
fi

echo "=========================================="
echo "  BC TRAINING (primary: $TRAINER_HOST)"
echo "  Models: ${MODEL_KEYS[*]}"
if [ ${#MODEL_KEYS[@]} -gt 1 ] && [ ${#TRAINER_WORKER_MACHINE_IDS[@]} -gt 0 ]; then
    echo "  Mode: PARALLEL (trainer slots distributed)"
    for w in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        echo "  Worker: ${TRAINER_WORKER_MACHINE_IDS[$w]} (gpu=${TRAINER_WORKER_GPU_INSTANCES[$w]}, slots=${TRAINER_WORKER_SLOT_COUNTS[$w]})"
    done
else
    echo "  Mode: sequential (single model or no workers)"
fi
echo "=========================================="

# Show CSV info
log_step "CSV files on primary trainer:"
ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "find '$SESSIONS_DIR/csv-training-data' -mindepth 2 -maxdepth 2 -name '*.csv' -printf '    %s %p\n' 2>/dev/null | sort -k2 || echo '    None found!'" \
    2>/dev/null || true

# Fail fast if any requested model has no CSV files. Otherwise the Python trainer
# exits early and the shell script misleadingly reports success.
MISSING_CSV_MODELS=()
for MODEL_KEY in "${MODEL_KEYS[@]}"; do
    CSV_COUNT=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "find '$SESSIONS_DIR/csv-training-data/$MODEL_KEY' -maxdepth 1 -name '*.csv' 2>/dev/null | wc -l" \
        2>/dev/null || echo "0")
    if [ "${CSV_COUNT:-0}" -le 0 ]; then
        MISSING_CSV_MODELS+=("$MODEL_KEY")
    fi
done

if [ "${#MISSING_CSV_MODELS[@]}" -ne 0 ]; then
    log_fail "Missing CSV training data for: ${MISSING_CSV_MODELS[*]}"
    exit 1
fi

# ==========================================
#  Workload-based model ordering
# ==========================================
# Sort MODEL_KEYS by total CSV size desc so the heaviest model lands on the
# strongest-GPU slot (slots are pre-sorted slots-desc → GPU-desc by
# get_trainer_workers). For the 3-models + 3-slots-on-(4090,3070,2070) case
# this pins rl_pawn → 4090, middle → 3070, lightest → 2070.
if [ ${#MODEL_KEYS[@]} -gt 1 ]; then
    MODEL_SIZE_LINES=()
    for MODEL_KEY in "${MODEL_KEYS[@]}"; do
        SIZE=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
            "find '$SESSIONS_DIR/csv-training-data/$MODEL_KEY' -maxdepth 1 -name '*.csv' -printf '%s\n' 2>/dev/null | awk '{s+=\$1} END {print s+0}'" \
            2>/dev/null || echo "0")
        MODEL_SIZE_LINES+=("$(printf '%020d %s' "${SIZE:-0}" "$MODEL_KEY")")
    done

    SORTED_MODEL_KEYS=()
    log_step "Model workload ranking (by CSV size, desc):"
    while IFS=' ' read -r padded_size MODEL_KEY; do
        [ -z "$MODEL_KEY" ] && continue
        SORTED_MODEL_KEYS+=("$MODEL_KEY")
        bytes=$((10#$padded_size))
        gb=$(awk -v s="$bytes" 'BEGIN {printf "%.2f", s/1073741824}')
        echo "    $MODEL_KEY: ${gb} GB"
    done < <(printf '%s\n' "${MODEL_SIZE_LINES[@]}" | sort -r)

    # Only replace if we got the full set back (guard against parse errors).
    if [ ${#SORTED_MODEL_KEYS[@]} -eq ${#MODEL_KEYS[@]} ]; then
        MODEL_KEYS=("${SORTED_MODEL_KEYS[@]}")
    fi
fi

# ==========================================
#  Early-start state (SAC per-model after BC completes)
# ==========================================
# Active BC slots publish their machine_id to BC_STATE_DIR/slot_<label>.machine.
# When a model's BC completes, attempt_early_start checks which machines are
# still BC-busy and launches train-sac.sh for that model if at least one SAC
# trainer worker is free of BC work.
BC_STATE_DIR="$DEPLOY_LOG_DIR/bc_state"
EARLY_STARTED_FILE="$BC_STATE_DIR/early_started.txt"
EARLY_START_LOCK="$BC_STATE_DIR/.launch.lock"
mkdir -p "$BC_STATE_DIR"
rm -f "$BC_STATE_DIR"/slot_*.machine "$EARLY_STARTED_FILE"

mark_slot_active() {
    local slot_label="$1"
    local machine_id="$2"
    echo "$machine_id" > "$BC_STATE_DIR/slot_${slot_label}.machine"
}

mark_slot_done() {
    local slot_label="$1"
    rm -f "$BC_STATE_DIR/slot_${slot_label}.machine"
}

# Track which model each slot is currently training. Used for migration victim
# selection — we need to know WHICH model to transfer. Written before training
# starts, cleared after training returns (completion, failure, or migration).
mark_current_model() {
    local slot_label="$1"
    local model_key="$2"
    echo "$model_key" > "$BC_STATE_DIR/slot_${slot_label}.current_model"
}

clear_current_model() {
    local slot_label="$1"
    rm -f "$BC_STATE_DIR/slot_${slot_label}.current_model"
}

# Explicit per-machine BC trainer priority from servers.json. LOWER = stronger
# trainer (prio 1 beats prio 2). Used to order migration victim selection.
# Required because gpu_instances is a bot-capacity field and is 0 for
# trainer-only machines like the 4090, so it doesn't reflect GPU training power.
# Missing/unknown machine returns 9999 sentinel = treated as weakest.
machine_bc_priority() {
    local machine_id="$1"
    local i
    for i in "${!MACHINE_IDS[@]}"; do
        if [ "${MACHINE_IDS[$i]}" = "$machine_id" ]; then
            echo "${BC_TRAINER_PRIORITIES[$i]:-9999}"
            return 0
        fi
    done
    echo "9999"
}

# Returns space-separated list of machine_ids currently running BC (any slot).
bc_busy_machines() {
    local out=""
    local f
    for f in "$BC_STATE_DIR"/slot_*.machine; do
        [ -f "$f" ] || continue
        local mid
        mid=$(cat "$f" 2>/dev/null)
        [ -n "$mid" ] && out+=" $mid"
    done
    # Deduplicate
    echo "$out" | tr ' ' '\n' | awk 'NF && !seen[$0]++' | tr '\n' ' '
}

model_has_flag_enabled() {
    local mk="$1"
    local algo="$2"  # only "sac" supported
    local json="$PROJECT_DIR/resources/models/$mk/${algo}.json"
    [ -f "$json" ] || return 1
    local enabled
    enabled=$(python3 -c "import json; print(json.load(open('$json')).get('experience_sync_enabled', False))" 2>/dev/null || echo "False")
    [ "$enabled" = "True" ]
}

# Returns 0 if at least one SAC trainer worker is NOT BC-busy.
any_free_trainer_worker_for() {
    local algo="$1"  # only "sac" supported
    local busy=" $(bc_busy_machines) "
    for i in "${!SERVERS[@]}"; do
        local slots="${SAC_TRAINER_SLOTS[$i]}"
        [ "${slots:-0}" -gt 0 ] 2>/dev/null || continue
        local mid="${MACHINE_IDS[$i]}"
        if [[ "$busy" != *" $mid "* ]]; then
            return 0
        fi
    done
    return 1
}

# Pending-early-start queue is persisted on disk (one file per algo) so state
# survives the `(... ) 9> LOCK` subshell that flock requires.
PENDING_SAC_FILE="$BC_STATE_DIR/pending_early_start_sac.txt"
: > "$PENDING_SAC_FILE"

record_early_started() {
    local mk="$1"
    local algo="$2"
    echo "$mk $algo" >> "$EARLY_STARTED_FILE"
}

pending_file_for() {
    case "$1" in
        sac) echo "$PENDING_SAC_FILE" ;;
    esac
}

pending_add_unique() {
    local algo="$1"
    local mk="$2"
    local f
    f="$(pending_file_for "$algo")"
    grep -qxF "$mk" "$f" 2>/dev/null || echo "$mk" >> "$f"
}

pending_remove() {
    local algo="$1"
    local mk="$2"
    local f tmp
    f="$(pending_file_for "$algo")"
    [ -f "$f" ] || return 0
    tmp="$(mktemp)"
    grep -vxF "$mk" "$f" > "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$f"
}

pending_list() {
    local f
    f="$(pending_file_for "$1")"
    [ -f "$f" ] && cat "$f" || true
}

# Try launching an early-start for one (model, algo) pair. No-op if the model
# does not have this algo enabled. If the trainer is still BC-busy, the model
# stays on the pending list for retry on the next completion.
try_launch_one() {
    local mk="$1"
    local algo="$2"  # only "sac" supported
    local script_dir="$3"
    local enabled_var="${EARLY_START_SAC:-false}"
    [ "$enabled_var" = "true" ] || { pending_remove "$algo" "$mk"; return 0; }
    if ! model_has_flag_enabled "$mk" "$algo"; then
        pending_remove "$algo" "$mk"
        return 0
    fi

    local busy_machines algo_upper
    busy_machines="$(bc_busy_machines)"
    algo_upper="$(echo "$algo" | tr '[:lower:]' '[:upper:]')"
    if ! any_free_trainer_worker_for "$algo"; then
        echo "  [early-start] Deferring $algo_upper for $mk — all $algo_upper trainers BC-busy"
        return 0
    fi

    echo "  [early-start] Launching $algo_upper for $mk (BC-busy: ${busy_machines:-none})"
    local script="$script_dir/train-${algo}.sh"
    local log="$DEPLOY_LOG_DIR/early_start_${algo}_${mk}.log"
    if BC_BUSY_MACHINES="$busy_machines" bash "$script" "$mk" > "$log" 2>&1; then
        record_early_started "$mk" "$algo"
    else
        echo "  [early-start] $algo_upper launch for $mk failed (see $log)"
    fi
    pending_remove "$algo" "$mk"
}

# Launch SAC for the just-completed model AND retry any previously deferred
# models whose trainer workers are now BC-free. Serialized via flock so
# concurrent slot completions don't race on the pending files.
attempt_early_start() {
    local mk="$1"
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

    (
        flock 9

        pending_add_unique sac "$mk"

        local candidate
        while IFS= read -r candidate; do
            [ -n "$candidate" ] && try_launch_one "$candidate" sac "$script_dir"
        done < <(pending_list sac)
    ) 9> "$EARLY_START_LOCK"
}

# Track machines whose bots were started early (during BC). Deploy.sh's post-BC
# start-bots loop skips any machine listed here so it doesn't double-launch.
BOTS_STARTED_DIR="$BC_STATE_DIR/bots_started"
mkdir -p "$BOTS_STARTED_DIR"
rm -f "$BOTS_STARTED_DIR"/*

# Start bots on a BC-trainer machine as soon as it has no more BC work. Gated
# by EARLY_START_BOTS=true so standalone `bash train-bc.sh` runs don't touch
# bots on the trainer machines — that's only wanted under `deploy.sh --retrain`
# with RESTART=true. Serialized via EARLY_START_LOCK to race-safely coexist
# with attempt_early_start and BC migration.
attempt_start_bots_for_machine() {
    local MACHINE_ID="$1"
    [ "${EARLY_START_BOTS:-false}" = "true" ] || return 0

    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

    (
        flock 9

        if [ -f "$BOTS_STARTED_DIR/$MACHINE_ID" ]; then
            return 0
        fi

        # Still BC-busy on this machine? Some other slot holds the machine.
        local f
        for f in "$BC_STATE_DIR"/slot_*.machine; do
            [ -f "$f" ] || continue
            if [ "$(cat "$f" 2>/dev/null)" = "$MACHINE_ID" ]; then
                return 0
            fi
        done

        # Resolve host/user/pass by MACHINE_ID lookup.
        local i host="" user="" pass=""
        for i in "${!MACHINE_IDS[@]}"; do
            if [ "${MACHINE_IDS[$i]}" = "$MACHINE_ID" ]; then
                host="${SERVERS[$i]}"
                user="${USERS[$i]}"
                pass="${PASSES[$i]}"
                break
            fi
        done
        if [ -z "$host" ]; then
            echo "  [bot-start] $MACHINE_ID not found in server inventory"
            return 0
        fi

        echo "  [bot-start] Starting bots on $MACHINE_ID (BC slots freed)"
        local log="$DEPLOY_LOG_DIR/bot_start_${MACHINE_ID}.log"
        if bash "$script_dir/start-bots.sh" --host "$host" --user "$user" --pass "$pass" > "$log" 2>&1; then
            touch "$BOTS_STARTED_DIR/$MACHINE_ID"
        else
            echo "  [bot-start] Failed for $MACHINE_ID (see $log)"
        fi
    ) 9> "$EARLY_START_LOCK"
}

# Pick the STRONGEST still-active BC slot on a machine whose priority is
# strictly weaker than ours — the "chain steal" strategy: 4090 takes from 3070
# first (freeing 3070 to take from 2070), not directly from 2070. Lower
# bc_trainer_priority = stronger, so "weaker than me" means a higher prio
# number. Echoes "victim_label victim_model victim_machine_id" on success,
# empty string otherwise. Marks the victim as "being_stolen" under the same
# lock so concurrent stealers can't pick the same victim.
find_migration_victim() {
    local my_label="$1"
    local my_machine_id="$2"
    local my_prio
    my_prio=$(machine_bc_priority "$my_machine_id")

    (
        flock 9

        # Sentinel 10000 > any real priority → first valid candidate wins.
        local victim_label="" victim_machine="" victim_prio=10000
        local f label machine prio
        for f in "$BC_STATE_DIR"/slot_*.machine; do
            [ -f "$f" ] || continue
            label=$(basename "$f" .machine)
            label=${label#slot_}
            [ "$label" = "$my_label" ] && continue
            [ -f "$BC_STATE_DIR/slot_${label}.being_stolen" ] && continue
            machine=$(cat "$f" 2>/dev/null)
            [ -z "$machine" ] && continue
            prio=$(machine_bc_priority "$machine")
            # Weaker than me (prio > my_prio) AND stronger than current best
            # candidate (prio < victim_prio).
            if [ "$prio" -gt "$my_prio" ] && [ "$prio" -lt "$victim_prio" ]; then
                victim_label="$label"
                victim_machine="$machine"
                victim_prio="$prio"
            fi
        done

        if [ -z "$victim_label" ]; then
            echo ""
            exit 0
        fi

        local victim_model
        victim_model=$(cat "$BC_STATE_DIR/slot_${victim_label}.current_model" 2>/dev/null || echo "")
        if [ -z "$victim_model" ]; then
            echo ""
            exit 0
        fi

        # Claim the victim: paired with handling in run_slot_queue that skips
        # complete/fail when SIGTERM-induced exit 42 fires.
        echo "$my_label" > "$BC_STATE_DIR/slot_${victim_label}.being_stolen"

        echo "$victim_label $victim_model $victim_machine"
    ) 9> "$EARLY_START_LOCK"
}

# SIGTERM the victim's Python trainer, wait for graceful checkpoint save + exit
# (up to 60s), then rsync checkpoint + bc-cache (from victim) and CSV (from
# primary trainer, canonical source) to the target machine. Returns 0 on
# success. The Python trainer catches SIGTERM in bc_training_loop.py and
# exits with MIGRATION_EXIT_CODE (42) after save_checkpoint().
migrate_artifacts() {
    local VICTIM_HOST="$1"
    local VICTIM_USER="$2"
    local VICTIM_PASS="$3"
    local TARGET_HOST="$4"
    local TARGET_USER="$5"
    local TARGET_PASS="$6"
    local TARGET_MACHINE_ID="$7"
    local MODEL_KEY="$8"

    echo "  [migrate] SIGTERM trainer for $MODEL_KEY on $VICTIM_HOST"
    ssh_cmd_quiet "$VICTIM_HOST" "$VICTIM_USER" "$VICTIM_PASS" \
        "pkill -SIGTERM -f 'train\\.rl\\.${MODEL_KEY}\\.trainBC' 2>/dev/null || true" \
        || true

    local waited alive
    for waited in $(seq 1 60); do
        alive=$(ssh_cmd "$VICTIM_HOST" "$VICTIM_USER" "$VICTIM_PASS" \
            "pgrep -f 'train\\.rl\\.${MODEL_KEY}\\.trainBC' 2>/dev/null | wc -l" \
            2>/dev/null || echo "0")
        [ "${alive:-0}" -eq 0 ] && break
        sleep 1
    done
    ssh_cmd_quiet "$VICTIM_HOST" "$VICTIM_USER" "$VICTIM_PASS" \
        "pkill -9 -f 'train\\.rl\\.${MODEL_KEY}\\.trainBC' 2>/dev/null || true" \
        || true

    ssh_cmd_quiet "$TARGET_HOST" "$TARGET_USER" "$TARGET_PASS" \
        "mkdir -p '$SESSIONS_DIR/models/trainingmodel' '$SESSIONS_DIR/bc-cache/$MODEL_KEY'" \
        || return 1

    echo "  [migrate] Transferring .pt + bc-cache from $VICTIM_HOST to $TARGET_HOST"
    sshpass -p "$VICTIM_PASS" ssh $SSH_OPTS "$VICTIM_USER@$VICTIM_HOST" \
        "cd '$SESSIONS_DIR' && tar -cf - \
            models/trainingmodel/${MODEL_KEY}.pt \
            models/trainingmodel/${MODEL_KEY}_best.pt \
            bc-cache/${MODEL_KEY} 2>/dev/null || true" \
        | sshpass -p "$TARGET_PASS" ssh $SSH_OPTS "$TARGET_USER@$TARGET_HOST" \
            "cd '$SESSIONS_DIR' && tar -xf - 2>/dev/null || true"

    # CSV from primary trainer (canonical source). No-op if target == primary.
    if ! sync_model_csv_to_worker "$MODEL_KEY" "$TARGET_HOST" "$TARGET_USER" "$TARGET_PASS" "$TARGET_MACHINE_ID"; then
        echo "  [migrate] CSV sync to $TARGET_MACHINE_ID failed"
        return 1
    fi
    return 0
}

# Train a single model (blocking) using the Python BC dispatcher.
# The dispatcher routes to the correct trainer module via role resolution.
train_one_model() {
    local MODEL_KEY="$1"
    local WORKER_HOST="$2"
    local WORKER_USER="$3"
    local WORKER_PASS="$4"
    local WORKER_MACHINE_ID="$5"

    log_step "Starting BC pre-trainer for $MODEL_KEY on $WORKER_MACHINE_ID..."
    sshpass -p "$WORKER_PASS" ssh $SSH_OPTS -o ServerAliveInterval=30 \
        "$WORKER_USER@$WORKER_HOST" "bash -s" <<BC_EOF 2>&1 | awk '/step=|complete|error|Error|Traceback|Total samples|Starting BC|Epoch|loss|turn_acc|loc_acc|fire_acc|No CSV|WARNING|File|line|PROMOTED|REJECTED|variant=/' | tail -40
export UT99_SESSIONS_DIR="$SESSIONS_DIR"
export PYTHONPATH="$REMOTE_DIR"
export UT99_MACHINE_ID="$WORKER_MACHINE_ID"
${UT99_MODEL_CONFIG_DIR:+export UT99_MODEL_CONFIG_DIR="$UT99_MODEL_CONFIG_DIR"}
cd "$REMOTE_DIR"
$VENV_PYTHON -m train.rl.${MODEL_KEY}.trainBC
BC_EOF

    return ${PIPESTATUS[0]}
}

model_exists_on_worker() {
    local MODEL_KEY="$1"
    local WORKER_HOST="$2"
    local WORKER_USER="$3"
    local WORKER_PASS="$4"
    local count
    count=$(ssh_cmd "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" \
        "find '$SESSIONS_DIR/models/trainingmodel' -maxdepth 1 -name '$MODEL_KEY.onnx' | wc -l" \
        2>/dev/null || echo "0")
    [ "${count:-0}" -gt 0 ]
}

sync_model_csv_to_worker() {
    local MODEL_KEY="$1"
    local WORKER_HOST="$2"
    local WORKER_USER="$3"
    local WORKER_PASS="$4"
    local WORKER_MACHINE_ID="$5"

    if [ "$WORKER_HOST" = "$TRAINER_HOST" ]; then
        return 0
    fi

    log_step "Syncing CSV for $MODEL_KEY to $WORKER_MACHINE_ID..."
    # Worker pulls from trainer via rsync. Identical files (size + mtime match)
    # are skipped so a no-op deploy is near-instant; --delete preserves the
    # rm-rf-style "trainer is canonical" semantics from the previous tar pipe.
    sshpass -p "$WORKER_PASS" ssh $SSH_OPTS "$WORKER_USER@$WORKER_HOST" \
        "mkdir -p '$SESSIONS_DIR/csv-training-data/$MODEL_KEY' && \
         sshpass -p '$TRAINER_PASS' rsync -az --delete \
            -e 'ssh $SSH_OPTS' \
            '$TRAINER_USER@$TRAINER_HOST:$SESSIONS_DIR/csv-training-data/$MODEL_KEY/' \
            '$SESSIONS_DIR/csv-training-data/$MODEL_KEY/'"
}

# Run one BC model on the given slot. SKIP_CSV_SYNC=true means CSV is already
# on the worker (e.g. after migration).
# Returns: 0 = completed & ONNX produced, 1 = hard failure, 2 = migrated away
# (Python exited with MIGRATION_EXIT_CODE). Callers in run_slot_queue treat
# exit 2 as "not my model anymore, continue" — no complete/fail claim.
_run_one_bc_model() {
    local SLOT_LABEL="$1"
    local MODEL_KEY="$2"
    local WORKER_HOST="$3"
    local WORKER_USER="$4"
    local WORKER_PASS="$5"
    local WORKER_MACHINE_ID="$6"
    local SKIP_CSV_SYNC="$7"

    echo "[$SLOT_LABEL] Preparing $MODEL_KEY on $WORKER_MACHINE_ID"
    mark_current_model "$SLOT_LABEL" "$MODEL_KEY"

    if [ "$SKIP_CSV_SYNC" != "true" ]; then
        if ! sync_model_csv_to_worker "$MODEL_KEY" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" "$WORKER_MACHINE_ID"; then
            clear_current_model "$SLOT_LABEL"
            return 1
        fi
    fi

    train_one_model "$MODEL_KEY" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" "$WORKER_MACHINE_ID"
    local exit_code=$?
    clear_current_model "$SLOT_LABEL"

    # Python exit 42 = SIGTERM received, checkpoint saved, migrated away.
    # The stealing slot owns this model now; don't claim complete/fail here.
    if [ "$exit_code" -eq 42 ]; then
        rm -f "$BC_STATE_DIR/slot_${SLOT_LABEL}.being_stolen"
        echo "[$SLOT_LABEL] Migrated away from $MODEL_KEY on $WORKER_MACHINE_ID"
        return 2
    fi
    # Belt-and-braces: if a stealer set being_stolen for this slot but SIGTERM
    # hit before the Python handler was registered (extremely rare — window
    # between process start and `import bc_training_loop`), Python dies with a
    # non-42 code. Treat any non-zero exit while being_stolen as a migration.
    if [ "$exit_code" -ne 0 ] && [ -f "$BC_STATE_DIR/slot_${SLOT_LABEL}.being_stolen" ]; then
        rm -f "$BC_STATE_DIR/slot_${SLOT_LABEL}.being_stolen"
        echo "[$SLOT_LABEL] Terminated during migration window for $MODEL_KEY — no checkpoint saved"
        return 2
    fi
    if [ "$exit_code" -ne 0 ]; then
        return 1
    fi
    if model_exists_on_worker "$MODEL_KEY" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS"; then
        echo "[$SLOT_LABEL] BC training complete for $MODEL_KEY on $WORKER_MACHINE_ID"
        attempt_early_start "$MODEL_KEY"
        return 0
    else
        echo "[$SLOT_LABEL] BC training missing ONNX for $MODEL_KEY on $WORKER_MACHINE_ID"
        return 1
    fi
}

run_slot_queue() {
    local SLOT_LABEL="$1"
    local WORKER_HOST="$2"
    local WORKER_USER="$3"
    local WORKER_PASS="$4"
    local WORKER_MACHINE_ID="$5"
    shift 5
    local MODELS=("$@")

    mark_slot_active "$SLOT_LABEL" "$WORKER_MACHINE_ID"
    # Release slot + any stale current_model marker on any exit path.
    trap "mark_slot_done '$SLOT_LABEL'; clear_current_model '$SLOT_LABEL'" RETURN

    # Phase 1: run the initial queue assigned by the scheduler.
    local MODEL_KEY rc
    for MODEL_KEY in "${MODELS[@]}"; do
        _run_one_bc_model "$SLOT_LABEL" "$MODEL_KEY" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" "$WORKER_MACHINE_ID" "false"
        rc=$?
        if [ "$rc" -eq 1 ]; then
            return 1
        fi
        # rc 0 (done) or rc 2 (migrated away) → continue to next assigned model
        attempt_start_bots_for_machine "$WORKER_MACHINE_ID"
    done

    # Phase 2: steal loop. While any weaker machine is still running BC, take
    # over its current model (SIGTERM → checkpoint rsync → resume here).
    while true; do
        local steal_result
        steal_result=$(find_migration_victim "$SLOT_LABEL" "$WORKER_MACHINE_ID")
        [ -z "$steal_result" ] && break

        local victim_label victim_model victim_machine
        read -r victim_label victim_model victim_machine <<< "$steal_result"
        echo "[$SLOT_LABEL] Stealing $victim_model from $victim_machine (slot $victim_label)"

        local vh="" vu="" vp="" j
        for j in "${!MACHINE_IDS[@]}"; do
            if [ "${MACHINE_IDS[$j]}" = "$victim_machine" ]; then
                vh="${SERVERS[$j]}"
                vu="${USERS[$j]}"
                vp="${PASSES[$j]}"
                break
            fi
        done

        if [ -z "$vh" ] || ! migrate_artifacts "$vh" "$vu" "$vp" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" "$WORKER_MACHINE_ID" "$victim_model"; then
            echo "[$SLOT_LABEL] Migration of $victim_model aborted — giving up steal loop"
            rm -f "$BC_STATE_DIR/slot_${victim_label}.being_stolen"
            break
        fi
        rm -f "$BC_STATE_DIR/slot_${victim_label}.being_stolen"

        _run_one_bc_model "$SLOT_LABEL" "$victim_model" "$WORKER_HOST" "$WORKER_USER" "$WORKER_PASS" "$WORKER_MACHINE_ID" "true"
        rc=$?
        if [ "$rc" -eq 1 ]; then
            return 1
        fi
        # rc 0 or 2 → keep looking for more weaker machines
    done

    # Slot truly done. Explicit mark + bot-start so the idle machine starts its
    # bot cohort without waiting for other slots to finish.
    mark_slot_done "$SLOT_LABEL"
    attempt_start_bots_for_machine "$WORKER_MACHINE_ID"
}

if [ ${#MODEL_KEYS[@]} -gt 1 ]; then
    if [ ${#TRAINER_WORKER_MACHINE_IDS[@]} -eq 0 ]; then
        log_fail "No trainer workers available (trainer > 0)."
        exit 1
    fi

    SLOT_HOSTS=()
    SLOT_USERS=()
    SLOT_PASSES=()
    SLOT_MACHINE_IDS=()
    SLOT_LABELS=()
    SLOT_QUEUES=()
    for w in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        slots="${TRAINER_WORKER_SLOT_COUNTS[$w]}"
        for ((s=0; s<slots; s++)); do
            SLOT_HOSTS+=("${TRAINER_WORKER_HOSTS[$w]}")
            SLOT_USERS+=("${TRAINER_WORKER_USERS[$w]}")
            SLOT_PASSES+=("${TRAINER_WORKER_PASSES[$w]}")
            SLOT_MACHINE_IDS+=("${TRAINER_WORKER_MACHINE_IDS[$w]}")
            SLOT_LABELS+=("${TRAINER_WORKER_MACHINE_IDS[$w]}_t$s")
            SLOT_QUEUES+=("")
        done
    done

    if [ ${#SLOT_LABELS[@]} -eq 0 ]; then
        log_fail "Trainer worker pool is empty after slot expansion."
        exit 1
    fi

    for i in "${!MODEL_KEYS[@]}"; do
        slot_idx=$(( i % ${#SLOT_LABELS[@]} ))
        MODEL_KEY="${MODEL_KEYS[$i]}"
        if [ -n "${SLOT_QUEUES[$slot_idx]}" ]; then
            SLOT_QUEUES[$slot_idx]+=" "
        fi
        SLOT_QUEUES[$slot_idx]+="$MODEL_KEY"
    done

    log_step "Trainer-slot assignment:"
    for i in "${!SLOT_LABELS[@]}"; do
        [ -z "${SLOT_QUEUES[$i]}" ] && continue
        echo "    ${SLOT_LABELS[$i]} -> ${SLOT_QUEUES[$i]}"
    done

    # BC trainers are already killed in deploy.sh fase 1 by kill-processes.sh,
    # no need for a second per-host SSH round here.

    PIDS=()
    LOGFILES=()
    SLOT_USED=()
    mkdir -p "$DEPLOY_LOG_DIR"

    for i in "${!SLOT_LABELS[@]}"; do
        [ -z "${SLOT_QUEUES[$i]}" ] && continue
        LOGFILE="$DEPLOY_LOG_DIR/bc_${SLOT_LABELS[$i]}.log"
        LOGFILES+=("$LOGFILE")
        SLOT_USED+=("$i")
        (
            # shellcheck disable=SC2086
            run_slot_queue "${SLOT_LABELS[$i]}" "${SLOT_HOSTS[$i]}" "${SLOT_USERS[$i]}" "${SLOT_PASSES[$i]}" "${SLOT_MACHINE_IDS[$i]}" ${SLOT_QUEUES[$i]}
        ) > "$LOGFILE" 2>&1 &
        PIDS+=($!)
        # Build steps summary for each model in this slot's queue
        STEPS_INFO=""
        for mk in ${SLOT_QUEUES[$i]}; do
            bc_json="$PROJECT_DIR/resources/models/$mk/bc.json"
            steps=$(python3 -c "import json; print(json.load(open('$bc_json'))['pretrain_steps'])" 2>/dev/null || echo "?")
            STEPS_INFO+=" $mk=${steps}"
        done
        log_step "Launched ${SLOT_LABELS[$i]} (PID $!) —${STEPS_INFO} steps"
    done

    FAILED=false
    for j in "${!PIDS[@]}"; do
        wait "${PIDS[$j]}"
        EXIT_CODE=$?
        SLOT_IDX="${SLOT_USED[$j]}"
        LOGFILE="${LOGFILES[$j]}"
        echo ""
        echo "  --- ${SLOT_LABELS[$SLOT_IDX]} output ---"
        cat "$LOGFILE"
        if [ $EXIT_CODE -eq 0 ]; then
            log_ok "${SLOT_LABELS[$SLOT_IDX]} complete"
        else
            log_fail "${SLOT_LABELS[$SLOT_IDX]} failed (exit $EXIT_CODE)"
            FAILED=true
        fi
    done

    if $FAILED; then
        exit 1
    fi
else
    # --- Sequential mode (default) ---
    # BC trainers already killed in deploy.sh fase 1 by kill-processes.sh.

    for MODEL_KEY in "${MODEL_KEYS[@]}"; do
        train_one_model "$MODEL_KEY" "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" "$TRAINER_MACHINE_ID"
        if [ $? -eq 0 ] && model_exists_on_worker "$MODEL_KEY" "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS"; then
            log_ok "BC training complete for $MODEL_KEY on $TRAINER_MACHINE_ID"
        else
            log_fail "BC training failed for $MODEL_KEY"
            exit 1
        fi
    done
fi

# Verify at least one model was created on trainer workers OR primary trainer.
MODEL_EXISTS=0

# Check primary trainer first (sequential mode trains here)
COUNT=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "find '$SESSIONS_DIR/models/trainingmodel' -maxdepth 1 -name 'rl_*.onnx' | wc -l" \
    2>/dev/null || echo "0")
[ "${COUNT:-0}" -gt 0 ] && MODEL_EXISTS=$COUNT

# Also check trainer workers (parallel mode trains there)
if [ "$MODEL_EXISTS" -eq 0 ]; then
    for w in "${!TRAINER_WORKER_MACHINE_IDS[@]}"; do
        COUNT=$(ssh_cmd "${TRAINER_WORKER_HOSTS[$w]}" "${TRAINER_WORKER_USERS[$w]}" "${TRAINER_WORKER_PASSES[$w]}" \
            "find '$SESSIONS_DIR/models/trainingmodel' -maxdepth 1 -name 'rl_*.onnx' | wc -l" \
            2>/dev/null || echo "0")
        if [ "${COUNT:-0}" -gt 0 ]; then
            MODEL_EXISTS=$COUNT
            break
        fi
    done
fi

if [ "$MODEL_EXISTS" -gt 0 ]; then
    log_ok "ONNX model(s) created"
else
    log_fail "No ONNX model found after BC training!"
    exit 1
fi
