#!/bin/bash
# Deploy code from dev machine to all servers configured in resources/config/deploy.json.
#
# All knobs live in deploy.json. There are no command-line flags — running the
# script always reads the current config and acts on it. See deploy.json schema:
#
#   - hosts:                    list of hostnames (substring match) or [] for all
#   - restart-bots:             kill + restart bot processes on each server
#   - clean-logs:               wipe /tmp/ut99-multi/*.log on each server
#   - extract-map-bounds:       run extract-map-bounds.sh --discover before sync
#   - models.<key>.clean-experience       wipe rl-replay-buffer/<key>/
#   - models.<key>.prepare-training-csv   regenerate CSV for this model
#   - models.<key>.keep-existing-model    preserve .pt/.onnx so BC resumes
#   - models.<key>.train-bc               run BC pre-training for this model
#   - models.<key>.train-sac              start SAC trainer for this model
#   - models.<key>.reset-sac-baseline     reset SAC checkpoint metrics (implies train-sac)
#   - models.<key>.reset-sac-to-bc-baseline  HARD reset: wipe _sac.pt + _sac_best.pt
#                                              so trainer falls back to BC weights
#                                              (implies train-sac, mutually exclusive
#                                              with reset-sac-baseline)
#   - models.<key>.convert-from-jsons     force re-run of ConvertJsonRecordingsToRecGzMain
#                                              for this model (bypasses convert-cache).
#                                              Implies replay-export.
#   - models.<key>.replay-export          run the full replay-export pipeline for this
#                                              model: convert (cached) → push .rec.gz to
#                                              recording_server → GenerateExperienceFromRecordingsMain
#                                              (cached per model) → mirror batch_replay-*.npz
#                                              to assigned SAC trainer.
#
# UCC compile, JAR build, and code sync always happen — they are cheap and
# correctness-critical. Map-bound extraction is gated by extract-map-bounds.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$SCRIPT_DIR/deploy"

source "$DEPLOY_DIR/common.sh"
source "$DEPLOY_DIR/load-deploy-config.sh"

parse_servers_conf

if [ ${#DEPLOY_HOSTS[@]} -gt 0 ]; then
    filter_servers "${DEPLOY_HOSTS[@]}"
fi

if [ ${#SERVERS[@]} -eq 0 ]; then
    echo "ERROR: No servers matched DEPLOY_HOSTS: ${DEPLOY_HOSTS[*]}"
    exit 1
fi

mkdir -p "$DEPLOY_LOG_DIR"

# ==========================================
#  Derived state
# ==========================================

RETRAINING="false"
[ ${#MODELS_TRAIN_BC[@]} -gt 0 ] && RETRAINING="true"

# Models whose .pt/.onnx must be wiped before BC: training requested AND not
# explicitly resumed via keep-existing-model.
WIPE_MODELS_LIST=()
for mk in "${MODELS_TRAIN_BC[@]}"; do
    if ! deploy_list_contains "$mk" "${MODELS_KEEP_EXISTING[@]}"; then
        WIPE_MODELS_LIST+=("$mk")
    fi
done

DOING_CLEAN="false"
if [ ${#MODELS_CLEAN_EXPERIENCE[@]} -gt 0 ] || \
   [ ${#WIPE_MODELS_LIST[@]} -gt 0 ] || \
   [ ${#MODELS_PREPARE_CSV[@]} -gt 0 ]; then
    DOING_CLEAN="true"
fi

# Defer bot startup when CSV preparation is requested without BC retraining.
# CSV generation claims large JVM heaps on every machine — starting bots
# concurrently wastes resources (SAC isn't consuming experience yet anyway).
DEFER_FOR_CSV="false"
if [ ${#MODELS_PREPARE_CSV[@]} -gt 0 ] && [ "$RETRAINING" != "true" ]; then
    DEFER_FOR_CSV="true"
fi

echo "=========================================="
echo "  DEPLOY (config: $DEPLOY_JSON)"
echo "=========================================="
echo "  Hosts (filter): ${DEPLOY_HOSTS[*]:-<all>}"
echo "  Servers:        ${SERVERS[*]}"
echo "  Restart bots:   $DEPLOY_RESTART_BOTS"
echo "  Clean logs:     $DEPLOY_CLEAN_LOGS"
echo "  Extract bounds: $DEPLOY_EXTRACT_MAP_BOUNDS"
echo "  Train BC:       ${MODELS_TRAIN_BC[*]:-<none>}"
echo "  Train SAC:      ${MODELS_TRAIN_SAC[*]:-<none>}"
echo "  Prepare CSV:    ${MODELS_PREPARE_CSV[*]:-<none>}"
echo "  Keep existing:  ${MODELS_KEEP_EXISTING[*]:-<none>}"
echo "  Clean exp:      ${MODELS_CLEAN_EXPERIENCE[*]:-<none>}"
echo "  Reset SAC:      ${MODELS_RESET_SAC_BASELINE[*]:-<none>}"
echo "  Reset SAC→BC:   ${MODELS_RESET_SAC_TO_BC_BASELINE[*]:-<none>}"
echo "  Restore→champ:  ${MODELS_RESET_CURRENT_TO_CHAMPION[*]:-<none>}"
echo "  Reset champ:    ${MODELS_RESET_CHAMPIONS[*]:-<none>}"
echo "  Convert JSONs:  ${MODELS_CONVERT_FROM_JSONS[*]:-<none>}"
echo "  Replay export:  ${MODELS_REPLAY_EXPORT[*]:-<none>}"
echo "=========================================="
echo ""

# ==========================================
#  Helper functions
# ==========================================

start_bots_parallel() {
    for i in "${DEPLOY_OK[@]}"; do
        bash "$DEPLOY_DIR/start-bots.sh" --host "${SERVERS[$i]}" --user "${USERS[$i]}" --pass "${PASSES[$i]}" \
            --raw-recording "$DEPLOY_RECORDINGS_SYNC" 2>/dev/null &
    done
    wait
}

poll_bots_started() {
    echo ""
    echo "=========================================="
    echo "  WAITING FOR BOTS TO START (polling every 30s)"
    echo "=========================================="

    EXPECTED=()
    for i in "${!SERVERS[@]}"; do
        raw="${INSTANCES_RAW[$i]}"
        if [[ "$raw" == */* ]]; then
            EXPECTED+=( $(( ${raw%%/*} + ${raw##*/} )) )
        else
            EXPECTED+=("$raw")
        fi
    done

    POLL_TMP=$(mktemp -d)

    POLL_INTERVAL=30
    MAX_WAIT_SEC=900
    MAX_UNREACHABLE_POLLS=2
    declare -A UNREACHABLE_COUNT
    POLL_INDICES=("${DEPLOY_OK[@]}")
    elapsed=0
    iteration=0

    while true; do
        iteration=$((iteration + 1))

        for i in "${POLL_INDICES[@]}"; do
            (
                count=$(ssh_cmd "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" \
                    "ps aux | grep ucc-bin | grep -v grep | wc -l" \
                    2>/dev/null || echo "?")
                echo "$count" > "$POLL_TMP/$i"
            ) &
        done
        wait

        echo ""
        echo "  --- poll #$iteration (elapsed: ${elapsed}s) ---"
        all_ready=true
        next_poll=()
        for i in "${POLL_INDICES[@]}"; do
            host="${SERVERS[$i]}"
            expected="${EXPECTED[$i]}"
            count=$(cat "$POLL_TMP/$i" 2>/dev/null || echo "?")
            if [ "$count" = "?" ]; then
                UNREACHABLE_COUNT[$i]=$(( ${UNREACHABLE_COUNT[$i]:-0} + 1 ))
                if [ "${UNREACHABLE_COUNT[$i]}" -ge "$MAX_UNREACHABLE_POLLS" ]; then
                    printf "  ✗ %-32s  ?/%s  (unreachable — dropped)\n" "$host" "$expected"
                else
                    printf "  ✗ %-32s  ?/%s  (unreachable, retry)\n" "$host" "$expected"
                    next_poll+=("$i")
                    all_ready=false
                fi
            elif [ "$count" -ge "$expected" ] 2>/dev/null; then
                printf "  ✓ %-32s  %s/%s\n" "$host" "$count" "$expected"
            else
                printf "  … %-32s  %s/%s\n" "$host" "$count" "$expected"
                next_poll+=("$i")
                all_ready=false
            fi
        done
        POLL_INDICES=("${next_poll[@]}")

        if [ ${#POLL_INDICES[@]} -eq 0 ]; then
            all_ready=true
        fi

        if [ "$all_ready" = "true" ]; then
            echo ""
            log_ok "All servers fully started."
            break
        fi

        if [ "$elapsed" -ge "$MAX_WAIT_SEC" ]; then
            echo ""
            log_fail "Timeout (${MAX_WAIT_SEC}s) — not all bots started. Continuing anyway."
            break
        fi

        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done

    rm -rf "$POLL_TMP"
}

# ==========================================
#  1. COMPILE UCC (on dev machine)
# ==========================================

bash "$DEPLOY_DIR/compile-ucc.sh"
echo ""

# ==========================================
#  1b. BUILD JAR (on dev machine, once)
# ==========================================

echo "=========================================="
echo "  BUILD JAR (on dev machine)"
echo "=========================================="
if (cd "$PROJECT_DIR" && ./mvnw package -DskipTests -q 2>&1 | tail -3); then
    log_ok "JAR built on dev"
else
    log_fail "JAR build failed on dev"
    exit 1
fi
echo ""

# ==========================================
#  1c. EXTRACT MAP BOUNDS (on dev, updates gameplay.json in-place)
# ==========================================

if [ "$DEPLOY_EXTRACT_MAP_BOUNDS" = "true" ]; then
    echo "=========================================="
    echo "  EXTRACT MAP BOUNDS"
    echo "=========================================="
    bash "$DEPLOY_DIR/extract-map-bounds.sh" --discover
    echo ""
else
    echo "=========================================="
    echo "  EXTRACT MAP BOUNDS — SKIPPED (extract-map-bounds: false)"
    echo "=========================================="
    echo ""
fi

# ==========================================
#  2. DEPLOY to all servers (parallel)
# ==========================================

echo "=========================================="
echo "  DEPLOY to ${#SERVERS[@]} servers (parallel)"
echo "=========================================="
echo "Servers: ${SERVERS[*]}"
echo ""

kill_trainers_only() {
    # Kill BC/SAC trainer processes WITHOUT touching running bots. Used when
    # restart-bots=false but retraining is still requested.
    local host="$1" user="$2" pass="$3" sac_slots="$4"
    local cmd="pkill -f '\\.trainBC' 2>/dev/null || true; tmux kill-session -t bc 2>/dev/null || true"
    if [ "${sac_slots:-0}" -gt 0 ]; then
        cmd="$cmd; tmux ls 2>/dev/null | awk -F: '/^sac_/ {print \$1}' | xargs -r -I{} tmux kill-session -t {} 2>/dev/null"
        cmd="$cmd; pkill -f '\\.trainSAC' 2>/dev/null || true"
    fi
    ssh_cmd_quiet "$host" "$user" "$pass" "$cmd" || true
}

deploy_one() {
    local idx="$1"
    local host="${SERVERS[$idx]}"
    local user="${USERS[$idx]}"
    local pass="${PASSES[$idx]}"
    local sac_slots="${SAC_TRAINER_SLOTS[$idx]}"
    local log="$DEPLOY_LOG_DIR/$host.log"

    if [ "$DEPLOY_RESTART_BOTS" = "true" ]; then
        local kill_flags=""
        # During retrain, BC competes with RL trainers for VRAM/RAM. Kill them
        # so the trainer machines have a clean slate; phase 4/5 restarts them.
        if [ "$RETRAINING" = "true" ] && [ "${sac_slots:-0}" -gt 0 ]; then
            kill_flags="$kill_flags --kill-sac"
        fi
        bash "$DEPLOY_DIR/kill-processes.sh" --host "$host" --user "$user" --pass "$pass" $kill_flags 2>/dev/null

        if [ "$DEPLOY_CLEAN_LOGS" = "true" ]; then
            # On SAC-trainer hosts also drop a sentinel so bootstrap.py resets
            # the DualKPIDeltaGate eval clock — the candidate's wall-clock
            # `started_at` is stale once we wipe its KPI source logs.
            if [ "${sac_slots:-0}" -gt 0 ]; then
                bash "$DEPLOY_DIR/clean-logs.sh" --host "$host" --user "$user" --pass "$pass" --with-trainer-state 2>/dev/null
            else
                bash "$DEPLOY_DIR/clean-logs.sh" --host "$host" --user "$user" --pass "$pass" 2>/dev/null
            fi
        fi
    elif [ "$RETRAINING" = "true" ]; then
        # Bots stay alive but RL/BC trainers must die to free VRAM for new BC.
        kill_trainers_only "$host" "$user" "$pass" "$sac_slots"
    fi

    # Cleanup runs after kill so newly-restarted bots can't write .npz between
    # wipe and start. Per-host parallel call to avoid a separate centralized round.
    if [ "$DOING_CLEAN" = "true" ]; then
        local clean_args=()
        if [ ${#MODELS_CLEAN_EXPERIENCE[@]} -gt 0 ]; then
            clean_args+=(--wipe-experience-for "${MODELS_CLEAN_EXPERIENCE[*]}")
        fi
        if [ ${#WIPE_MODELS_LIST[@]} -gt 0 ]; then
            clean_args+=(--wipe-models-for "${WIPE_MODELS_LIST[*]}")
        fi
        if [ ${#MODELS_PREPARE_CSV[@]} -gt 0 ]; then
            clean_args+=(--wipe-csv-for "${MODELS_PREPARE_CSV[*]}")
        fi
        bash "$DEPLOY_DIR/clean-experience.sh" --host "$host" --user "$user" --pass "$pass" "${clean_args[@]}" 2>/dev/null
    fi

    if bash "$DEPLOY_DIR/sync-code.sh" --host "$host" --user "$user" --pass "$pass" 2>/dev/null; then
        echo "SYNC_OK" > "$log"
    else
        echo "SYNC_FAILED" > "$log"
        return 1
    fi

    if [ ${#MODELS_RESET_CHAMPIONS[@]} -gt 0 ]; then
        local champ_cmds=""
        for mk in "${MODELS_RESET_CHAMPIONS[@]}"; do
            champ_cmds="$champ_cmds rm -rf '$SESSIONS_DIR/models/champions/$mk';"
        done
        champ_cmds="$champ_cmds rm -f '$SESSIONS_DIR/models/champions/bundles.json';"
        ssh_cmd_quiet "$host" "$user" "$pass" "$champ_cmds" || true
    fi

    # Defer bot startup when retraining, preparing CSV, resetting champions
    # (champion bootstrap + sync must complete before bots can resolve rl_pawn/newest),
    # or restoring to champion (the champion ONNX must be pushed to all servers first
    # so bots come up on the champion, not the disturbed current model).
    if [ "$DEPLOY_RESTART_BOTS" = "true" ] && [ "$RETRAINING" != "true" ] && [ "$DEFER_FOR_CSV" != "true" ] && [ ${#MODELS_RESET_CHAMPIONS[@]} -eq 0 ] && [ ${#MODELS_RESET_CURRENT_TO_CHAMPION[@]} -eq 0 ]; then
        bash "$DEPLOY_DIR/start-bots.sh" --host "$host" --user "$user" --pass "$pass" \
            --raw-recording "$DEPLOY_RECORDINGS_SYNC" 2>/dev/null
    fi

    echo "DONE" >> "$log"
}

PIDS=()
for i in "${!SERVERS[@]}"; do
    deploy_one "$i" &
    PIDS+=($!)
    echo "  Started deploy to ${SERVERS[$i]} (PID $!)"
done

echo ""
echo "Waiting for all deploys to complete..."
DEPLOY_OK=()
UNREACHABLE=()
for i in "${!PIDS[@]}"; do
    wait "${PIDS[$i]}" 2>/dev/null
    host="${SERVERS[$i]}"
    log="$DEPLOY_LOG_DIR/$host.log"
    if grep -q "DONE" "$log" 2>/dev/null; then
        log_ok "$host — OK"
        DEPLOY_OK+=("$i")
    elif grep -q "SYNC_FAILED" "$log" 2>/dev/null; then
        log_fail "$host — SYNC FAILED (unreachable?) — skipping"
        UNREACHABLE+=("$i")
    else
        log_fail "$host — FAILED (check $log) — skipping"
        UNREACHABLE+=("$i")
    fi
done

if [ ${#UNREACHABLE[@]} -gt 0 ]; then
    skipped=()
    for i in "${UNREACHABLE[@]}"; do skipped+=("${SERVERS[$i]}"); done
    echo ""
    echo "  Skipped unreachable/failed servers: ${skipped[*]}"
fi

# Champion bootstrap + deferred bot startup when reset-champions was set.
# The SAC trainer creates the bootstrap champion from BC baseline, syncs it
# to all servers, then bots start and can resolve rl_pawn/newest.
if [ ${#MODELS_RESET_CHAMPIONS[@]} -gt 0 ] && [ "$DEPLOY_RESTART_BOTS" = "true" ] && [ ${#DEPLOY_OK[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  CHAMPION BOOTSTRAP (post-reset)"
    echo "=========================================="
    find_primary_trainer || { log_fail "No primary trainer found"; exit 1; }
    ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "cd $REMOTE_DIR && $VENV_PYTHON -m train.common.champion_pool bootstrap" 2>&1 | tail -5
    log_ok "Champion bootstrapped and synced to all servers"

    echo ""
    echo "=========================================="
    echo "  STARTING BOTS (post-champion-bootstrap)"
    echo "=========================================="
    start_bots_parallel
    poll_bots_started
fi

# Wait for bots to start (skip when retraining, deferred for CSV, champion-reset,
# champion-restore, or restart-bots=false).
if [ "$DEPLOY_RESTART_BOTS" = "true" ] && [ "$RETRAINING" != "true" ] && [ "$DEFER_FOR_CSV" != "true" ] && [ ${#MODELS_RESET_CHAMPIONS[@]} -eq 0 ] && [ ${#MODELS_RESET_CURRENT_TO_CHAMPION[@]} -eq 0 ] && [ ${#DEPLOY_OK[@]} -gt 0 ]; then
    poll_bots_started
fi

# ==========================================
#  2b. RECORDINGS SYNC (dev → recording_server)
#      Servers in CAPTURE-mode push their own from-servers/ via sync_replay.sh;
#      this step pushes dev's from-dev/ (human gameplay).
# ==========================================

if [ "$DEPLOY_RECORDINGS_SYNC" = "true" ]; then
    echo ""
    echo "=========================================="
    echo "  RECORDINGS SYNC (dev → recording_server)"
    echo "=========================================="
    bash "$DEPLOY_DIR/sync-recordings.sh" || log_fail "sync-recordings.sh failed (continuing)"
fi

# NOTE: convert-from-jsons + replay-export run AFTER train-sac.sh below.
# Reason: SAC's AsyncBatchProvider polls rl-replay-buffer/<model>/ on a timer
# and picks up batch_replay-*.npz that land after the trainer is already
# running. Putting the export here would only delay SAC start without changing
# when training actually begins (SAC blocks on min_buffer_size anyway).

# ==========================================
#  3. CSV + BC RETRAIN pipeline
#     (Per-model cleanup already happened inside deploy_one between kill+sync.)
# ==========================================

if [ ${#MODELS_PREPARE_CSV[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  PREPARE CSV: ${MODELS_PREPARE_CSV[*]}"
    echo "=========================================="
    if ! bash "$DEPLOY_DIR/prepare-csv.sh" "${MODELS_PREPARE_CSV[@]}"; then
        log_fail "CSV preparation failed. Aborting."
        exit 1
    fi
fi

if [ "$DEFER_FOR_CSV" = "true" ] && [ "$DEPLOY_RESTART_BOTS" = "true" ] && [ ${#DEPLOY_OK[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  STARTING BOTS (post-CSV)"
    echo "=========================================="
    start_bots_parallel
    poll_bots_started
fi

if [ "$RETRAINING" = "true" ]; then
    echo ""
    echo "=========================================="
    echo "  BC TRAINING: ${MODELS_TRAIN_BC[*]}"
    echo "=========================================="

    # Start bots on non-BC-trainer machines in parallel with BC. Trainer machines
    # stay idle until BC finishes (bot instances and BC compete for the GPU).
    if [ "$DEPLOY_RESTART_BOTS" = "true" ]; then
        log_step "Starting bots on non-BC-trainer machines in parallel with BC..."
        for i in "${!SERVERS[@]}"; do
            if [ "${BC_TRAINER_SLOTS[$i]:-0}" -eq 0 ]; then
                bash "$DEPLOY_DIR/start-bots.sh" --host "${SERVERS[$i]}" --user "${USERS[$i]}" --pass "${PASSES[$i]}" \
                    --raw-recording "$DEPLOY_RECORDINGS_SYNC" 2>/dev/null &
            fi
        done
    fi

    # Early-start SAC training as soon as a model's BC completes AND a worker is free.
    # Disabled if any reset-baseline is set for SAC — the reset must run instead
    # of plain train-sac.sh.
    export EARLY_START_SAC="false"
    export EARLY_START_BOTS="false"
    if [ ${#MODELS_TRAIN_SAC[@]} -gt 0 ] \
        && [ ${#MODELS_RESET_SAC_BASELINE[@]} -eq 0 ] \
        && [ ${#MODELS_RESET_SAC_TO_BC_BASELINE[@]} -eq 0 ]; then
        export EARLY_START_SAC="true"
    fi
    if [ "$DEPLOY_RESTART_BOTS" = "true" ]; then
        export EARLY_START_BOTS="true"
    fi

    bash "$DEPLOY_DIR/train-bc.sh" "${MODELS_TRAIN_BC[@]}"

    if [ "$DEPLOY_RESTART_BOTS" = "true" ]; then
        wait
        log_step "Starting bots on BC-trainer machines (post-BC)..."
        BOTS_STARTED_DIR="$DEPLOY_LOG_DIR/bc_state/bots_started"
        for i in "${!SERVERS[@]}"; do
            if [ "${BC_TRAINER_SLOTS[$i]:-0}" -gt 0 ]; then
                if [ -f "$BOTS_STARTED_DIR/${MACHINE_IDS[$i]}" ]; then
                    log_ok "  ${MACHINE_IDS[$i]} — already started early"
                    continue
                fi
                bash "$DEPLOY_DIR/start-bots.sh" --host "${SERVERS[$i]}" --user "${USERS[$i]}" --pass "${PASSES[$i]}" \
                    --raw-recording "$DEPLOY_RECORDINGS_SYNC" 2>/dev/null &
            fi
        done
        wait
    fi
fi

# ==========================================
#  4. RESOLVE EARLY-STARTED SAC TRAINERS
# ==========================================

EARLY_STARTED_SAC=()
EARLY_STARTED_FILE="$DEPLOY_LOG_DIR/bc_state/early_started.txt"
if [ "$RETRAINING" = "true" ] && [ -f "$EARLY_STARTED_FILE" ]; then
    while read -r mk algo _rest; do
        [ -z "${mk:-}" ] && continue
        case "$algo" in
            sac) EARLY_STARTED_SAC+=("$mk") ;;
        esac
    done < "$EARLY_STARTED_FILE"
fi

if [ ${#EARLY_STARTED_SAC[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  EARLY-STARTED DURING BC"
    echo "=========================================="
    echo "  SAC: ${EARLY_STARTED_SAC[*]}"
fi

# Subtract early-started AND reset-baselined from the train list. Reset-baselined
# models are handled in step 5; the rest go to train-sac.sh in step 6.
REMAINING_SAC=()
for mk in "${MODELS_TRAIN_SAC[@]}"; do
    deploy_list_contains "$mk" "${EARLY_STARTED_SAC[@]}" && continue
    deploy_list_contains "$mk" "${MODELS_RESET_SAC_BASELINE[@]}" && continue
    deploy_list_contains "$mk" "${MODELS_RESET_SAC_TO_BC_BASELINE[@]}" && continue
    deploy_list_contains "$mk" "${MODELS_RESET_CURRENT_TO_CHAMPION[@]}" && continue
    REMAINING_SAC+=("$mk")
done

# ==========================================
#  5. RESET BASELINES (kills + resets + restarts)
# ==========================================

if [ ${#MODELS_RESET_SAC_BASELINE[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  RESET SAC BASELINE: ${MODELS_RESET_SAC_BASELINE[*]}"
    echo "=========================================="
    bash "$DEPLOY_DIR/reset-sac-baseline.sh" "${MODELS_RESET_SAC_BASELINE[@]}"
fi

if [ ${#MODELS_RESET_SAC_TO_BC_BASELINE[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  RESET SAC TO BC BASELINE: ${MODELS_RESET_SAC_TO_BC_BASELINE[*]}"
    echo "  (wipes _sac.pt + _sac_best.pt + _sac_checkpoint.pt"
    echo "   so trainer falls back to BC weights)"
    echo "=========================================="
    bash "$DEPLOY_DIR/reset-sac-to-bc-baseline.sh" "${MODELS_RESET_SAC_TO_BC_BASELINE[@]}"
fi

if [ ${#MODELS_RESET_CURRENT_TO_CHAMPION[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  RESTORE SAC TO CHAMPION: ${MODELS_RESET_CURRENT_TO_CHAMPION[*]}"
    echo "  (champion .pt -> _sac_best/_sac ladder, champion ONNX -> all servers,"
    echo "   then restart SAC bootstrapping from the champion)"
    echo "=========================================="
    bash "$DEPLOY_DIR/reset-sac-to-champion.sh" "${MODELS_RESET_CURRENT_TO_CHAMPION[@]}"
fi

# ==========================================
#  6. START remaining SAC trainers
# ==========================================

if [ ${#REMAINING_SAC[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  START SAC: ${REMAINING_SAC[*]}"
    echo "=========================================="
    bash "$DEPLOY_DIR/train-sac.sh" "${REMAINING_SAC[@]}"
fi

# ==========================================
#  5b. START deferred bots (post-champion-restore)
#      reset-sac-to-champion.sh (step 5) already pushed the champion ONNX to
#      every server and started SAC (in warmup on the cleared buffer). Bots were
#      deferred so they come up on the champion and refill the buffer with
#      champion-consistent experience — avoiding the buffer-race where a freshly
#      cleared buffer is re-poisoned by disturbed-policy transitions.
# ==========================================

if [ ${#MODELS_RESET_CURRENT_TO_CHAMPION[@]} -gt 0 ] && [ "$DEPLOY_RESTART_BOTS" = "true" ] && [ ${#DEPLOY_OK[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  STARTING BOTS (post-champion-restore)"
    echo "=========================================="
    start_bots_parallel
    poll_bots_started
fi

# ==========================================
#  REPLAY EXPORT (now that trainers are up)
#       For each model with replay-export=true: run the full pipeline on dev →
#       recording_server → assigned trainer. Per-model caching skips work that
#       has identical inputs since the previous run (see replay-export.sh).
#       The trainer's AsyncBatchProvider picks up the new batch_replay-*.npz on
#       its next ingest tick (no restart needed).
#
#       convert-from-jsons=true is forwarded as --force-convert=<csv> so those
#       models bypass the convert-cache (the JSON → .rec.gz step re-runs even
#       if the cache says HIT). Useful when global config affecting convert
#       (e.g. command_controller max-step) changed without touching JSON or
#       features.json.
# ==========================================

if [ ${#MODELS_REPLAY_EXPORT[@]} -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "  REPLAY EXPORT: ${MODELS_REPLAY_EXPORT[*]}"
    echo "=========================================="
    REPLAY_ARGS=()
    if [ ${#MODELS_CONVERT_FROM_JSONS[@]} -gt 0 ]; then
        FORCE_CONVERT_CSV=$(IFS=, ; echo "${MODELS_CONVERT_FROM_JSONS[*]}")
        REPLAY_ARGS+=("--force-convert=$FORCE_CONVERT_CSV")
    fi
    if ! bash "$DEPLOY_DIR/replay-export.sh" "${REPLAY_ARGS[@]}" "${MODELS_REPLAY_EXPORT[@]}"; then
        log_fail "replay-export.sh failed. Aborting."
        exit 1
    fi
fi

echo ""
echo "=========================================="
echo "  DEPLOY COMPLETE"
echo "=========================================="
