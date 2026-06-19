#!/bin/bash
# Prepare training CSV data using distributed workers with ZIP-level sharding.
#
# Instead of assigning whole models to single servers, this script:
# 1. Inventories all ZIPs per model with byte sizes
# 2. Per-model hash check — only regenerates models whose ZIPs changed (partial reruns)
# 3. Expands each writer into N "virtual workers" based on csv_writer_slots
# 4. Distributes individual ZIPs across virtual workers (throughput-weighted bin-packing)
# 5. Syncs only assigned ZIPs to each physical machine
# 6. Launches each shard with --zip-list-file for bounded memory usage
# 7. Collects results (CSV + manifest.json) and publishes on trainer
#
# Each model is processed sequentially; within a model, all shards run in parallel.
# A writer with csv_writer_slots=3 runs up to 3 concurrent shard JVMs, each with
# heap = 75% RAM / slots. This ensures each JVM processes a small subset of ZIPs.
#
# Shard manifests (manifest.json) are written by Java with per-ZIP metrics (frames,
# rows, runtime, peak heap) and stored in csv-generation-history/ for auditability.
# Historical throughput (bytes/sec per machine) is computed from manifests and used
# for dynamic shard sizing on subsequent runs.
#
# Usage:
#   bash scripts/deploy/prepare-csv.sh                         # all models
#   bash scripts/deploy/prepare-csv.sh rl_pawn             # one model
#   bash scripts/deploy/prepare-csv.sh rl_pawn # explicit subset

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

parse_servers_conf
find_trainer || exit 1
resolve_all_model_keys
get_csv_writers

RECORDINGS_DIR="$SESSIONS_DIR/json-recording-sessions"
CSV_DIR="$SESSIONS_DIR/csv-training-data"
STAGING_DIR="$SESSIONS_DIR/csv-training-data-staging"
INBOX_DIR="$SESSIONS_DIR/csv-training-data-inbox"
HISTORY_DIR="$SESSIONS_DIR/csv-generation-history"

REQUESTED_MODELS=()
for arg in "$@"; do
    case "$arg" in
        --*) ;;
        *) [ -n "$arg" ] && REQUESTED_MODELS+=("$arg") ;;
    esac
done

if [ ${#REQUESTED_MODELS[@]} -eq 0 ]; then
    MODEL_DIRS=("${ALL_MODEL_KEYS[@]}")
else
    for mk in "${REQUESTED_MODELS[@]}"; do
        found=false
        for valid in "${ALL_MODEL_KEYS[@]}"; do
            [ "$mk" = "$valid" ] && { found=true; break; }
        done
        if [ "$found" != "true" ]; then
            echo "ERROR: model '$mk' not in resources/models/index.json (valid: ${ALL_MODEL_KEYS[*]})"
            exit 1
        fi
    done
    MODEL_DIRS=("${REQUESTED_MODELS[@]}")
fi

# If no CSV writers configured, fall back to trainer-only mode
if [ ${#CSV_WRITER_MACHINE_IDS[@]} -eq 0 ]; then
    CSV_WRITER_HOSTS=("$TRAINER_HOST")
    CSV_WRITER_USERS=("$TRAINER_USER")
    CSV_WRITER_PASSES=("$TRAINER_PASS")
    CSV_WRITER_MACHINE_IDS=("$TRAINER_MACHINE_ID")
    CSV_WRITER_SLOT_COUNTS=("${#MODEL_DIRS[@]}")
    CSV_WRITER_GPU_INSTANCES=("0")
fi

echo "=========================================="
echo "  PREPARE CSV (trainer: $TRAINER_HOST)"
echo "  Models: ${MODEL_DIRS[*]}"
for w in "${!CSV_WRITER_MACHINE_IDS[@]}"; do
    echo "  Writer: ${CSV_WRITER_MACHINE_IDS[$w]} (gpu=${CSV_WRITER_GPU_INSTANCES[$w]}, slots=${CSV_WRITER_SLOT_COUNTS[$w]})"
done
echo "=========================================="

# --- 1. ZIP uncompressed recording directories per model on dev ---
#
# The zip+cleanup loop runs over ALL models, not just the requested filter:
# turning a finished recording directory into its ZIP (and removing the
# loose-JSON folder) is orthogonal to which model's CSV we are about to
# regenerate. Without this, running `prepare-csv.sh rl_pawn` would
# leave stale uncompressed dirs under rl_pawn/ and rl_pawn/
# until the next time those models are explicitly prepared.

log_step "[1/7] Zipping uncompressed recordings on dev..."
for model_dir in "${ALL_MODEL_KEYS[@]}"; do
    MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"
    mkdir -p "$MODEL_REC_DIR"
    [ -d "$MODEL_REC_DIR" ] || continue
    for dir in "$MODEL_REC_DIR"/*/; do
        [ -d "$dir" ] || continue
        dirname=$(basename "$dir")
        zipfile="$MODEL_REC_DIR/${dirname}.zip"
        if [ ! -f "$zipfile" ]; then
            (cd "$MODEL_REC_DIR" && zip -rq "${dirname}.zip" "$dirname" && rm -rf "$dirname")
            log_step "Zipped $model_dir/$dirname"
        else
            rm -rf "$dir"
        fi
    done
done

# Verify that the requested-model subset has at least one ZIP. ZIPs in
# unrequested models stay untouched (compressed above) but don't satisfy this
# check — there is nothing to generate CSV from for the requested set.
TOTAL_ZIP_COUNT=0
for model_dir in "${MODEL_DIRS[@]}"; do
    MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"
    count=$(find "$MODEL_REC_DIR" -maxdepth 1 -name '*.zip' 2>/dev/null | wc -l)
    TOTAL_ZIP_COUNT=$((TOTAL_ZIP_COUNT + count))
    [ "$count" -gt 0 ] && log_ok "$model_dir: $count ZIP file(s) on dev"
done

if [ "$TOTAL_ZIP_COUNT" -eq 0 ]; then
    log_fail "No ZIP files found in any model subdirectory of $RECORDINGS_DIR"
    exit 1
fi

# --- 2. Check which models need CSV regeneration (per-model hashing) ---

log_step "[2/7] Checking which models need CSV regeneration..."

declare -A MODEL_HASHES
MODELS_TO_PROCESS=()

for model_dir in "${MODEL_DIRS[@]}"; do
    MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"
    MODEL_HASH=$(find "$MODEL_REC_DIR" -maxdepth 1 -name '*.zip' 2>/dev/null | sort | xargs sha256sum 2>/dev/null | sha256sum | cut -d' ' -f1)
    MODEL_HASH="${MODEL_HASH:-none}"
    MODEL_HASHES["$model_dir"]="$MODEL_HASH"

    STORED_HASH=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "cat '$CSV_DIR/$model_dir/.ziphash' 2>/dev/null || echo 'none'" \
        2>/dev/null || echo "none")

    if [ "$MODEL_HASH" = "$STORED_HASH" ]; then
        CSV_COUNT=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
            "find '$CSV_DIR/$model_dir' -maxdepth 1 -name '*.csv' 2>/dev/null | wc -l" \
            2>/dev/null || echo "0")
        CSV_COUNT=${CSV_COUNT:-0}
        if [ "$CSV_COUNT" -gt 0 ]; then
            log_ok "  $model_dir: up to date (hash=${STORED_HASH:0:12}..., $CSV_COUNT CSVs)"
            continue
        fi
    fi

    MODELS_TO_PROCESS+=("$model_dir")
done

if [ ${#MODELS_TO_PROCESS[@]} -eq 0 ]; then
    log_ok "All models up to date"
    exit 0
fi

log_step "  Models to process: ${MODELS_TO_PROCESS[*]}"

# Narrow MODEL_DIRS to only models that need work (steps 3-7 operate on this subset)
ALL_MODEL_DIRS=("${MODEL_DIRS[@]}")
MODEL_DIRS=("${MODELS_TO_PROCESS[@]}")

# --- 3. Check reachability, query RAM, build virtual worker pool, create shard plan ---

log_step "[3/7] Creating shard plan..."

# Check which CSV writers are reachable
REACHABLE_WRITERS=()
for w in "${!CSV_WRITER_HOSTS[@]}"; do
    if ssh_cmd_quiet "${CSV_WRITER_HOSTS[$w]}" "${CSV_WRITER_USERS[$w]}" "${CSV_WRITER_PASSES[$w]}" "true" 2>/dev/null; then
        REACHABLE_WRITERS+=("$w")
        log_ok "  ${CSV_WRITER_MACHINE_IDS[$w]} reachable"
    else
        log_fail "  ${CSV_WRITER_MACHINE_IDS[$w]} unreachable — skipping"
    fi
done

if [ ${#REACHABLE_WRITERS[@]} -eq 0 ]; then
    log_fail "No CSV writers reachable. Cannot generate CSV."
    exit 1
fi

# Query RAM from reachable writers for heap sizing
declare -A WRITER_RAM_MB
for w in "${REACHABLE_WRITERS[@]}"; do
    RAM_KB=$(ssh_cmd "${CSV_WRITER_HOSTS[$w]}" "${CSV_WRITER_USERS[$w]}" "${CSV_WRITER_PASSES[$w]}" \
        "awk '/MemTotal/{print \$2}' /proc/meminfo" 2>/dev/null || echo "0")
    WRITER_RAM_MB[$w]=$((RAM_KB / 1024))
done

# Build virtual worker pool: each csv_writer_slot becomes a virtual worker.
# A writer with slots=3 contributes 3 virtual workers, each running 1 concurrent JVM.
# Heap per JVM = 75% RAM / slots on that physical machine.
VWORKER_WIDX=()    # physical writer index for each virtual worker
VWORKER_SNUM=()    # shard number within physical writer (0, 1, 2...)

for w in "${REACHABLE_WRITERS[@]}"; do
    slots="${CSV_WRITER_SLOT_COUNTS[$w]}"
    [ "$slots" -le 0 ] && slots=1
    for ((s=0; s<slots; s++)); do
        VWORKER_WIDX+=("$w")
        VWORKER_SNUM+=("$s")
    done
done
NUM_VWORKERS=${#VWORKER_WIDX[@]}

# Compute heap per shard JVM: 75% of RAM / concurrent slots
declare -A WRITER_HEAP_MB
for w in "${REACHABLE_WRITERS[@]}"; do
    slots="${CSV_WRITER_SLOT_COUNTS[$w]}"
    [ "$slots" -le 0 ] && slots=1
    WRITER_HEAP_MB[$w]=$(( WRITER_RAM_MB[$w] * 75 / 100 / slots ))
    log_step "  ${CSV_WRITER_MACHINE_IDS[$w]}: ${WRITER_RAM_MB[$w]} MB RAM, $slots slot(s), heap ${WRITER_HEAP_MB[$w]}m per JVM"
done
log_step "  Virtual workers: $NUM_VWORKERS (across ${#REACHABLE_WRITERS[@]} machines)"

# Load historical throughput for dynamic shard sizing (bytes/sec per machine)
declare -A MACHINE_THROUGHPUT
THROUGHPUT_FILE="$CSV_DIR/.throughput"
THROUGHPUT_DATA=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "cat '$THROUGHPUT_FILE' 2>/dev/null || echo ''" 2>/dev/null || echo "")
if [ -n "$THROUGHPUT_DATA" ]; then
    while IFS=' ' read -r tp_mid tp_bps; do
        [ -n "$tp_mid" ] && [ -n "$tp_bps" ] && MACHINE_THROUGHPUT["$tp_mid"]="$tp_bps"
    done <<< "$THROUGHPUT_DATA"
    log_step "  Historical throughput: $(echo "$THROUGHPUT_DATA" | tr '\n' ' ')"
fi

# Bootstrap for cold-start machines: seed unknown throughput with the mean of
# known values. Otherwise new machines fall back to 1 byte/sec and never get
# any shards (self-reinforcing starvation — no shards means no manifest means
# no throughput entry next time either).
TP_SUM=0
TP_COUNT=0
for tp_bps in "${MACHINE_THROUGHPUT[@]}"; do
    if [ "$tp_bps" -gt 0 ] 2>/dev/null; then
        TP_SUM=$((TP_SUM + tp_bps))
        TP_COUNT=$((TP_COUNT + 1))
    fi
done
if [ "$TP_COUNT" -gt 0 ]; then
    TP_BOOTSTRAP=$((TP_SUM / TP_COUNT))
    log_step "  Bootstrap throughput for unknown machines: $TP_BOOTSTRAP bytes/sec (mean of $TP_COUNT known)"
else
    TP_BOOTSTRAP=1
fi

# Compute per-vworker throughput weight for dynamic shard sizing
declare -a VW_THROUGHPUT
for ((vw=0; vw<NUM_VWORKERS; vw++)); do
    w="${VWORKER_WIDX[$vw]}"
    mid="${CSV_WRITER_MACHINE_IDS[$w]}"
    slots="${CSV_WRITER_SLOT_COUNTS[$w]}"
    [ "$slots" -le 0 ] && slots=1
    base_tp="${MACHINE_THROUGHPUT[$mid]:-0}"
    [ "$base_tp" -le 0 ] 2>/dev/null && base_tp=$TP_BOOTSTRAP
    VW_THROUGHPUT[$vw]=$((base_tp / slots))
    [ "${VW_THROUGHPUT[$vw]}" -le 0 ] && VW_THROUGHPUT[$vw]=1
done

# Build UNIFIED shard plan: collect ZIPs from ALL models, sort globally by size,
# then greedy bin-pack across all virtual workers. Each model shares the full
# vworker pool, so every machine (including 4090) gets shards as soon as total
# ZIPs exceed the faster machines' slot count.
declare -A SHARD_ZIPS   # key="model:vw_idx" -> space-separated zip filenames
declare -A SHARD_BYTES  # key="model:vw_idx" -> total bytes

# Collect (size, model, name) tuples across all models
ALL_ZIP_ENTRIES=()
for model_dir in "${MODEL_DIRS[@]}"; do
    MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"
    while IFS=$'\t' read -r size name; do
        [ -z "$name" ] && continue
        ALL_ZIP_ENTRIES+=("${size}|${model_dir}|${name}")
    done < <(find "$MODEL_REC_DIR" -maxdepth 1 -name '*.zip' -printf '%s\t%f\n' 2>/dev/null)
done

if [ ${#ALL_ZIP_ENTRIES[@]} -eq 0 ]; then
    log_fail "No ZIPs found in any model directory: ${MODEL_DIRS[*]}"
    exit 1
fi

# Sort globally by size desc so biggest ZIPs go to the lowest-load vworker first
SORTED_ENTRIES=$(printf '%s\n' "${ALL_ZIP_ENTRIES[@]}" | sort -t'|' -k1,1 -rn)

# Per-vworker byte counters (global — shared across all models in this greedy pass)
declare -a _VW_BYTES
for ((vw=0; vw<NUM_VWORKERS; vw++)); do
    _VW_BYTES[$vw]=0
done

# Unified greedy bin-packing: each ZIP goes to the vworker with lowest estimated time.
# estimated_time = total_assigned_bytes / throughput_weight (throughput-weighted bin-packing).
# Tie-break on equal est: prefer the vworker with HIGHEST throughput (will finish
# fastest going forward). Without this, ties fall to lowest-index FIFO, which —
# combined with size-desc ZIP order — systematically gives the largest ZIPs to the
# first vworkers in the writer list and starves the last ones.
while IFS='|' read -r zip_bytes model_dir zip_name; do
    [ -z "$zip_name" ] && continue

    min_vw=0
    min_est=$(( ${_VW_BYTES[0]} * 1000 / ${VW_THROUGHPUT[0]} ))
    for ((vw=1; vw<NUM_VWORKERS; vw++)); do
        est=$(( ${_VW_BYTES[$vw]} * 1000 / ${VW_THROUGHPUT[$vw]} ))
        if [ "$est" -lt "$min_est" ] \
            || { [ "$est" -eq "$min_est" ] && [ "${VW_THROUGHPUT[$vw]}" -gt "${VW_THROUGHPUT[$min_vw]}" ]; }; then
            min_est=$est
            min_vw=$vw
        fi
    done

    key="${model_dir}:${min_vw}"
    if [ -n "${SHARD_ZIPS[$key]:-}" ]; then
        SHARD_ZIPS[$key]="${SHARD_ZIPS[$key]} ${zip_name}"
    else
        SHARD_ZIPS[$key]="$zip_name"
    fi
    SHARD_BYTES[$key]=$(( ${SHARD_BYTES[$key]:-0} + zip_bytes ))
    _VW_BYTES[$min_vw]=$(( _VW_BYTES[$min_vw] + zip_bytes ))
done <<< "$SORTED_ENTRIES"
unset _VW_BYTES

# When total ZIPs > num_vworkers, a vworker can end up with shards for multiple
# models. Those shards must NOT run concurrently on the same slot (>1 JVM = OOM).
# Serialization is enforced at launch time with a per-vworker flock (see step 5).
# Report multi-model vworkers so the scheduling log is transparent.
for ((vw=0; vw<NUM_VWORKERS; vw++)); do
    model_count=0
    for model_dir in "${MODEL_DIRS[@]}"; do
        key="${model_dir}:${vw}"
        [ -n "${SHARD_ZIPS[$key]:-}" ] && model_count=$((model_count + 1))
    done
    if [ "$model_count" -gt 1 ]; then
        log_step "  vw $vw queues $model_count models sequentially (flock)"
    fi
done


# Log shard assignments per model per physical machine
for model_dir in "${MODEL_DIRS[@]}"; do
    for w in "${REACHABLE_WRITERS[@]}"; do
        W_MID="${CSV_WRITER_MACHINE_IDS[$w]}"
        machine_zips=0
        machine_bytes=0
        machine_shards=0
        for ((vw=0; vw<NUM_VWORKERS; vw++)); do
            [ "${VWORKER_WIDX[$vw]}" != "$w" ] && continue
            key="${model_dir}:${vw}"
            [ -z "${SHARD_ZIPS[$key]:-}" ] && continue
            machine_shards=$((machine_shards + 1))
            machine_zips=$((machine_zips + $(echo "${SHARD_ZIPS[$key]}" | wc -w)))
            machine_bytes=$((machine_bytes + ${SHARD_BYTES[$key]:-0}))
        done
        [ "$machine_shards" -eq 0 ] && continue
        bytes_mb=$((machine_bytes / 1024 / 1024))
        if [ "$machine_shards" -gt 1 ]; then
            log_ok "  $model_dir -> $W_MID: $machine_shards shards, $machine_zips ZIPs (${bytes_mb} MB)"
        else
            log_ok "  $model_dir -> $W_MID: $machine_zips ZIPs (${bytes_mb} MB)"
        fi
    done
done

# --- 4. Sync shard ZIPs to assigned workers ---

log_step "[4/7] Syncing ZIPs to assigned workers..."

# Collect all ZIPs needed per (model, physical machine) across its virtual workers
declare -A SYNC_ZIPS  # key="model:writer_idx" -> space-separated unique zip names

for model_dir in "${MODEL_DIRS[@]}"; do
    for ((vw=0; vw<NUM_VWORKERS; vw++)); do
        key="${model_dir}:${vw}"
        [ -z "${SHARD_ZIPS[$key]:-}" ] && continue
        w="${VWORKER_WIDX[$vw]}"
        sync_key="${model_dir}:${w}"
        for zip_name in ${SHARD_ZIPS[$key]}; do
            if [ -z "${SYNC_ZIPS[$sync_key]:-}" ]; then
                SYNC_ZIPS[$sync_key]="$zip_name"
            elif [[ " ${SYNC_ZIPS[$sync_key]} " != *" $zip_name "* ]]; then
                SYNC_ZIPS[$sync_key]="${SYNC_ZIPS[$sync_key]} $zip_name"
            fi
        done
    done
done

# Rsync per (model, physical machine): only the ZIPs assigned to any shard on that machine
for model_dir in "${MODEL_DIRS[@]}"; do
    MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"
    for w in "${REACHABLE_WRITERS[@]}"; do
        sync_key="${model_dir}:${w}"
        [ -z "${SYNC_ZIPS[$sync_key]:-}" ] && continue

        W_HOST="${CSV_WRITER_HOSTS[$w]}"
        W_USER="${CSV_WRITER_USERS[$w]}"
        W_PASS="${CSV_WRITER_PASSES[$w]}"

        RSYNC_INCLUDES=()
        for zip_name in ${SYNC_ZIPS[$sync_key]}; do
            RSYNC_INCLUDES+=(--include="$zip_name")
        done

        ssh_cmd_quiet "$W_HOST" "$W_USER" "$W_PASS" \
            "mkdir -p '$MODEL_REC_DIR'" || true
        rsync_to "$W_PASS" "$MODEL_REC_DIR/" "$W_HOST" "$W_USER" "$MODEL_REC_DIR/" \
            --checksum "${RSYNC_INCLUDES[@]}" --exclude='*' \
            >/dev/null 2>&1 &
    done
done
wait || true
log_ok "ZIPs synced"

# --- 5. Launch CSV generation per model (all shards in parallel within each model) ---

log_step "[5/7] Launching CSV generation..."

RUN_ID="run_$(date +%Y%m%d_%H%M%S)"
ALL_SHARD_MODELS=()
ALL_SHARD_WIDX=()
ALL_SHARD_IDS=()
FAILED=false

# Per-vworker lock directory: used by flock below to serialize shards that share
# a vworker (total ZIPs > num_vworkers case). Shards on different vworkers remain
# fully parallel.
VW_LOCK_DIR="/tmp/csv_vw_locks_${RUN_ID}"
mkdir -p "$VW_LOCK_DIR"

mkdir -p "$DEPLOY_LOG_DIR"

# Schedule all shards across all models, then wait for all of them globally.
# Safe because the planner (step 3) asserts each vworker has shards for at most
# one model, so no slot runs >1 concurrent JVM.

SHARD_PIDS=()
SHARD_LOGFILES=()
SHARD_MODEL_KEYS=()
SHARD_WRITER_INDICES=()
SHARD_ID_LIST=()
SHARD_MACHINE_IDS=()

for model_dir in "${MODEL_DIRS[@]}"; do
    log_step "  Scheduling $model_dir shards..."

    for ((vw=0; vw<NUM_VWORKERS; vw++)); do
        key="${model_dir}:${vw}"
        [ -z "${SHARD_ZIPS[$key]:-}" ] && continue

        w="${VWORKER_WIDX[$vw]}"
        snum="${VWORKER_SNUM[$vw]}"
        W_HOST="${CSV_WRITER_HOSTS[$w]}"
        W_USER="${CSV_WRITER_USERS[$w]}"
        W_PASS="${CSV_WRITER_PASSES[$w]}"
        W_MID="${CSV_WRITER_MACHINE_IDS[$w]}"
        W_HEAP="${WRITER_HEAP_MB[$w]}"
        MODEL_REC_DIR="$RECORDINGS_DIR/$model_dir"

        SHARD_ID="${W_MID}_s${snum}"
        W_OUTPUT="$STAGING_DIR/$RUN_ID/$model_dir/$SHARD_ID"
        ZIP_LIST_FILE="/tmp/csv_shard_${RUN_ID}_${model_dir}_${SHARD_ID}.txt"
        GC_LOG_FILE="/tmp/csv_gc_${model_dir}_${SHARD_ID}.log"
        LOGFILE="$DEPLOY_LOG_DIR/csv_${model_dir}_${SHARD_ID}.log"

        # Build newline-separated ZIP list content
        ZIP_LIST_CONTENT=""
        for zip_name in ${SHARD_ZIPS[$key]}; do
            ZIP_LIST_CONTENT="${ZIP_LIST_CONTENT}${zip_name}\n"
        done

        zip_count=$(echo "${SHARD_ZIPS[$key]}" | wc -w)
        bytes_mb=$(( ${SHARD_BYTES[$key]} / 1024 / 1024 ))
        log_step "    $SHARD_ID ($model_dir): $zip_count ZIPs (${bytes_mb} MB), heap ${W_HEAP}m"

        W_STAGING="$STAGING_DIR/$RUN_ID/$model_dir/$SHARD_ID/"
        T_INBOX="$INBOX_DIR/$RUN_ID/$model_dir/$SHARD_ID/"
        VW_LOCK_FILE="$VW_LOCK_DIR/vw_${vw}.lock"
        (
            # Serialize shards that share a vworker: exclusive flock held for the
            # full JVM+sync lifecycle so concurrent shards on different vworkers
            # run in parallel, but same-vworker shards queue. Auto-released on exit.
            exec 200>"$VW_LOCK_FILE"
            flock 200

            # Phase 1: run JVM on worker to generate CSV shards
            sshpass -p "$W_PASS" ssh $SSH_OPTS -o ServerAliveInterval=30 \
                "$W_USER@$W_HOST" "bash -s" <<WORKER_EOF 2>&1
export UT99_SESSIONS_DIR="$SESSIONS_DIR"
JH=\$(ls -d /opt/java/jdk-* 2>/dev/null | head -1)
[ -n "\$JH" ] && export JAVA_HOME=\$JH && export PATH=\$JH/bin:\$PATH
cd "$REMOTE_DIR"
mkdir -p "$W_OUTPUT"
printf '${ZIP_LIST_CONTENT}' > "$ZIP_LIST_FILE"
java -Xmx${W_HEAP}m -Xms512m \
    -XX:+UseG1GC \
    -Xlog:gc*:file=${GC_LOG_FILE}:time,uptime,level,tags:filecount=2,filesize=10m \
    -cp java-aiplay/target/java-aiplay-1.0.jar aiplay.GenerateTrainingCsvMain \
    --model $model_dir \
    --source-dir $MODEL_REC_DIR \
    --output-dir $W_OUTPUT \
    --zip-list-file $ZIP_LIST_FILE \
    --run-id $RUN_ID \
    --shard-id $SHARD_ID
WORKER_EOF
            JVM_STATUS=$?
            if [ "$JVM_STATUS" -ne 0 ]; then
                echo "JVM failed with exit $JVM_STATUS"
                exit "$JVM_STATUS"
            fi

            # Phase 2: pipeline sync — rsync this shard's CSVs to trainer immediately
            # (do NOT wait for other shards). Exit codes 110+ signal sync failures.
            EXPECTED_CSVS=$(sshpass -p "$W_PASS" ssh $SSH_OPTS "$W_USER@$W_HOST" \
                "find '$W_STAGING' -maxdepth 1 -name '*.csv' 2>/dev/null | wc -l" 2>/dev/null || echo "0")
            EXPECTED_CSVS=${EXPECTED_CSVS:-0}

            if [ "$EXPECTED_CSVS" -le 0 ]; then
                # 0 CSVs is acceptable iff the manifest says status=complete
                # (shard processed all ZIPs but all frames were filtered/deduped).
                MANIFEST_STATUS=$(sshpass -p "$W_PASS" ssh $SSH_OPTS "$W_USER@$W_HOST" \
                    "python3 -c \"import json; print(json.load(open('${W_STAGING}manifest.json')).get('status',''))\" 2>/dev/null" \
                    2>/dev/null || echo "")
                if [ "$MANIFEST_STATUS" = "complete" ]; then
                    echo "Empty shard (all frames filtered) — sync skipped, OK"
                    exit 0
                fi
                echo "No CSVs and no complete manifest"
                exit 110
            fi

            if [ "$W_HOST" = "$TRAINER_HOST" ]; then
                sshpass -p "$TRAINER_PASS" ssh $SSH_OPTS "$TRAINER_USER@$TRAINER_HOST" \
                    "mkdir -p '$T_INBOX' && find '$W_STAGING' -maxdepth 1 \\( -name '*.csv' -o -name 'manifest.json' \\) -exec cp -f {} '$T_INBOX' \\;" \
                    2>/dev/null || { echo "trainer-local copy failed"; exit 120; }
            else
                sshpass -p "$TRAINER_PASS" ssh $SSH_OPTS "$TRAINER_USER@$TRAINER_HOST" \
                    "mkdir -p '$T_INBOX'" 2>/dev/null || { echo "trainer inbox mkdir failed"; exit 121; }
                sshpass -p "$W_PASS" ssh $SSH_OPTS "$W_USER@$W_HOST" \
                    "sshpass -p '$TRAINER_PASS' rsync -az -e 'ssh -o StrictHostKeyChecking=no' \
                    '$W_STAGING' '$TRAINER_USER@$TRAINER_HOST:$T_INBOX'" \
                    < /dev/null 2>/dev/null || { echo "rsync worker->trainer failed"; exit 122; }
            fi

            INBOX_CSVS=$(sshpass -p "$TRAINER_PASS" ssh $SSH_OPTS "$TRAINER_USER@$TRAINER_HOST" \
                "find '$T_INBOX' -maxdepth 1 -name '*.csv' 2>/dev/null | wc -l" 2>/dev/null || echo "0")
            INBOX_CSVS=${INBOX_CSVS:-0}
            if [ "$INBOX_CSVS" -ne "$EXPECTED_CSVS" ]; then
                echo "verify failed: expected $EXPECTED_CSVS got $INBOX_CSVS"
                exit 130
            fi

            echo "gen+sync OK ($INBOX_CSVS CSVs)"
            exit 0
        ) > "$LOGFILE" 2>&1 &

        SHARD_PIDS+=($!)
        SHARD_LOGFILES+=("$LOGFILE")
        SHARD_MODEL_KEYS+=("$model_dir")
        SHARD_WRITER_INDICES+=("$w")
        SHARD_ID_LIST+=("$SHARD_ID")
        SHARD_MACHINE_IDS+=("$W_MID")
    done
done

log_step "  All ${#SHARD_PIDS[@]} shards launched (gen+sync pipelined). Waiting..."

# Wait for every shard: each subshell runs its own JVM+sync+verify pipeline.
# Exit code convention: 0 = gen+sync OK, 1-109 = JVM failure, 110+ = sync/verify failure.
for i in "${!SHARD_PIDS[@]}"; do
    wait "${SHARD_PIDS[$i]}"
    EXIT_CODE=$?
    MK="${SHARD_MODEL_KEYS[$i]}"
    w="${SHARD_WRITER_INDICES[$i]}"
    LOGFILE="${SHARD_LOGFILES[$i]}"
    SID="${SHARD_ID_LIST[$i]}"
    S_MID="${SHARD_MACHINE_IDS[$i]}"

    if [ $EXIT_CODE -eq 0 ]; then
        log_ok "    $SID ($MK) gen+sync OK"
    elif [ $EXIT_CODE -ge 110 ]; then
        log_fail "    $SID ($MK) sync failed (exit $EXIT_CODE)"
        echo "    --- output ---"
        tail -20 "$LOGFILE" | sed 's/^/      /'
        FAILED=true
    else
        log_fail "    $SID ($MK) JVM failed (exit $EXIT_CODE)"
        echo "    --- output ---"
        tail -20 "$LOGFILE" | sed 's/^/      /'
        FAILED=true
    fi

    ALL_SHARD_MODELS+=("$MK")
    ALL_SHARD_WIDX+=("$w")
    ALL_SHARD_IDS+=("$SID")
done

if $FAILED; then
    log_fail "One or more CSV shards failed (gen or sync). Aborting."
    exit 1
fi

# --- 6. Summary (all shards already synced inside step 5 subshells) ---

log_step "[6/7] All shards synced to trainer inbox (pipelined during generation)"
SYNCED_TOTAL_CSVS=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "find '$INBOX_DIR/$RUN_ID' -name '*.csv' 2>/dev/null | wc -l" \
    2>/dev/null || echo "0")
SYNCED_TOTAL_CSVS=${SYNCED_TOTAL_CSVS:-0}
log_ok "All results synced to trainer inbox ($SYNCED_TOTAL_CSVS CSVs)"

# --- 7. Validate, publish, store history, and update throughput on trainer ---

log_step "[7/7] Publishing final CSV on trainer..."

# Publish: only replace model directories that were reprocessed (preserves skipped models)
sshpass -p "$TRAINER_PASS" ssh $SSH_OPTS "$TRAINER_USER@$TRAINER_HOST" "bash -s" <<PUBLISH_EOF 2>&1 | tail -20
INBOX="$INBOX_DIR/$RUN_ID"
CSV_DIR="$CSV_DIR"

mkdir -p "\$CSV_DIR"

for model_dir in \$(ls "\$INBOX" 2>/dev/null); do
    rm -rf "\$CSV_DIR/\$model_dir"
    mkdir -p "\$CSV_DIR/\$model_dir"
    part_num=1
    for shard_dir in \$(ls "\$INBOX/\$model_dir" 2>/dev/null | sort); do
        for csv_file in \$(ls "\$INBOX/\$model_dir/\$shard_dir"/*.csv 2>/dev/null | sort); do
            cp "\$csv_file" "\$CSV_DIR/\$model_dir/\$(printf 'data_part%03d.csv' \$part_num)"
            part_num=\$((part_num + 1))
        done
    done
    echo "  \$model_dir: \$((part_num - 1)) CSV part(s) published"
done
PUBLISH_EOF

VERIFY_MODEL_DIRS=("${ALL_MODEL_DIRS[@]}")
MODEL_LIST=$(printf "'%s' " "${VERIFY_MODEL_DIRS[@]}")
CSV_COUNTS=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "for d in $MODEL_LIST; do c=\$(find '$CSV_DIR'/\$d -maxdepth 1 -name '*.csv' 2>/dev/null | wc -l); echo \"\$d:\$c\"; done" \
    2>/dev/null || true)

TOTAL_CSV_COUNT=0
MISSING_MODELS=()
while IFS=: read -r model count; do
    [ -n "$model" ] || continue
    count=${count:-0}
    TOTAL_CSV_COUNT=$((TOTAL_CSV_COUNT + count))
    if [ "$count" -le 0 ]; then
        MISSING_MODELS+=("$model")
    fi
done <<< "$CSV_COUNTS"

if [ "${#MISSING_MODELS[@]}" -ne 0 ]; then
    log_fail "CSV generation incomplete; missing CSVs for: ${MISSING_MODELS[*]}"
    printf '%s\n' "$CSV_COUNTS" | sed 's/^/    /'
    exit 1
fi

# Store per-model hashes after successful publish.
for model_dir in "${MODELS_TO_PROCESS[@]}"; do
    ssh_cmd_quiet "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "echo '${MODEL_HASHES[$model_dir]}' > '$CSV_DIR/$model_dir/.ziphash'" &
done
wait || true

# Store shard manifests in run history for throughput tracking and auditability
ssh_cmd_quiet "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "mkdir -p '$HISTORY_DIR/$RUN_ID'" || true
for i in "${!ALL_SHARD_MODELS[@]}"; do
    MODEL="${ALL_SHARD_MODELS[$i]}"
    SID="${ALL_SHARD_IDS[$i]}"
    T_INBOX="$INBOX_DIR/$RUN_ID/$MODEL/$SID"
    ssh_cmd_quiet "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "[ -f '$T_INBOX/manifest.json' ] && cp '$T_INBOX/manifest.json' '$HISTORY_DIR/$RUN_ID/${MODEL}_${SID}.json'" &
done
wait || true
log_ok "Run history stored: $HISTORY_DIR/$RUN_ID/"

# Update throughput file from this run's manifests (bytes/sec per machine for shard scheduling).
#
# Per-machine bytes/sec must reflect WALL-CLOCK throughput, not summed runtime across
# parallel slots. If two slots on the same machine each run for 600s in parallel,
# wall-clock is ~600s — summing to 1200s would halve the recorded throughput and
# cause the planner (which divides by slots again) to under-weight multi-slot
# machines on the next run. We approximate wall-clock as max(runtime_ms) across
# all shards on the machine: shards launch ~simultaneously, so the longest
# shard's runtime is the effective wall-clock for that machine.
sshpass -p "$TRAINER_PASS" ssh $SSH_OPTS "$TRAINER_USER@$TRAINER_HOST" \
    "python3 - '$HISTORY_DIR' '$RUN_ID' '$CSV_DIR/.throughput'" <<'PYEOF' 2>/dev/null || true
import json, glob, os, sys

history_dir, run_id, throughput_file = sys.argv[1], sys.argv[2], sys.argv[3]
manifests = glob.glob(os.path.join(history_dir, run_id, '*.json'))
if not manifests:
    sys.exit(0)

machines = {}
for mf in manifests:
    with open(mf) as f:
        data = json.load(f)
    sid = data.get('shard_id', '')
    if '_s' not in sid:
        continue
    mid = sid.rsplit('_s', 1)[0]
    t = data.get('totals', {})
    zb, rm = t.get('total_zip_bytes', 0), t.get('runtime_ms', 0)
    if rm <= 0:
        continue
    machines.setdefault(mid, {'b': 0, 'm': 0})
    machines[mid]['b'] += zb
    if rm > machines[mid]['m']:
        machines[mid]['m'] = rm

with open(throughput_file, 'w') as f:
    for mid in sorted(machines):
        bps = int(machines[mid]['b'] * 1000 / machines[mid]['m'])
        f.write(f'{mid} {bps}\n')
        print(f'  throughput: {mid} = {bps} bytes/sec')
PYEOF

# Clean up staging, inbox, and zip-list files on all involved machines
CLEANED_MACHINES=()
for w in "${ALL_SHARD_WIDX[@]}"; do
    local_mid="${CSV_WRITER_MACHINE_IDS[$w]}"
    [[ " ${CLEANED_MACHINES[*]:-} " == *" $local_mid "* ]] && continue
    CLEANED_MACHINES+=("$local_mid")
    ssh_cmd_quiet "${CSV_WRITER_HOSTS[$w]}" "${CSV_WRITER_USERS[$w]}" "${CSV_WRITER_PASSES[$w]}" \
        "rm -rf '$STAGING_DIR/$RUN_ID'; rm -f /tmp/csv_shard_${RUN_ID}_*.txt" &
done
ssh_cmd_quiet "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
    "rm -rf '$INBOX_DIR/$RUN_ID'" &
wait || true

log_ok "CSV generation complete ($TOTAL_CSV_COUNT files across ${#ALL_MODEL_DIRS[@]} models)"
printf '%s\n' "$CSV_COUNTS" | sed 's/^/    /'
