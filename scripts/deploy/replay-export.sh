#!/bin/bash
# Replay-export pipeline. For each requested model:
#   1. (rl_pawn / rl_pawn / rl_pawn) Convert local
#      <sessions>/json-recording-sessions/<model>/* into
#      <recordings>/from-dev/<model>/*.rec.gz via ConvertJsonRecordingsToRecGzMain.
#      A background watcher streams rotated *.rec.gz to the recording_server
#      while convert is still running (excluding the still-active
#      highest-sequence file), so STAGE 2 only flushes the final file.
#      Cached: skipped if input JSONs + model features.json/model.json + jar are
#      unchanged since the last run (marker: <from-dev>/<model>/.convert-key).
#   2. Push <recordings>/from-dev/<model>/ → recording_server (rsync incremental,
#      catches the final active rec.gz + .convert-key after convert completes).
#   3. On recording_server: GenerateExperienceFromRecordingsMain converts the
#      full corpus (from-dev/<model>/ + from-servers/<host>/<model>/) into
#      batch_replay-*.npz. Cached per model: skipped if the corpus + rewards.json
#      + features.json/model.json + jar are unchanged
#      (marker: <replay-buffer>/<model>/.generate-key).
#   4. Mirror batch_replay-*.npz → assigned SAC trainer (rsync --delete on
#      that pattern only, leaves live-bot batch_<host>-*.npz untouched).
#
# Flags:
#   --force-convert=<csv>   Skip convert-cache for these models (re-run convert
#                           and mark fresh). Wired by deploy.sh from
#                           MODELS_CONVERT_FROM_JSONS — the deploy.json
#                           convert-from-jsons flag is the user-facing override.
#
# Cache-invalidating events handled automatically:
#   - new/modified JSON recording sessions (convert)
#   - features.json / model.json edits (both)
#   - rewards.json edits (generate)
#   - new live-bot recordings synced into from-servers/<host>/<model>/ (generate)
#   - jar rebuild (both)
#   - clean-experience.sh wipes <replay-buffer>/<model>/ (marker gone → MISS)
#
# Validation (already enforced by load_deploy_config.py):
#   convert-from-jsons=true ⇒ replay-export=true
#   replay-export=true is advised with clean-experience=true if you want a pure
#       replay buffer (warning, not enforced).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$(dirname "$SCRIPTS_DIR")"

source "$SCRIPT_DIR/common.sh"
parse_servers_conf
find_recording_server || exit 1

FORCE_CONVERT_CSV=""
MODELS=()
for arg in "$@"; do
    case "$arg" in
        --force-convert=*) FORCE_CONVERT_CSV="${arg#--force-convert=}" ;;
        --*) echo "[replay-export] Unknown flag: $arg" >&2; exit 1 ;;
        *) MODELS+=("$arg") ;;
    esac
done

if [ "${#MODELS[@]}" -eq 0 ]; then
    echo "[replay-export] No models requested — nothing to do."
    exit 0
fi

is_force_convert() {
    local m="$1"
    [ -z "$FORCE_CONVERT_CSV" ] && return 1
    local IFS=','
    local x
    for x in $FORCE_CONVERT_CSV; do
        [ "$x" = "$m" ] && return 0
    done
    return 1
}

read_files_path() {
    python3 -c "
import json
with open('$BASE_DIR/resources/config/files.json') as f:
    print(json.load(f).get('$1', ''), end='')
"
}

RECORDINGS_DIR=$(read_files_path "recordings_dir")
SESSIONS_DIR_LOCAL=$(read_files_path "sessions_dir")
if [ -z "$RECORDINGS_DIR" ] || [ -z "$SESSIONS_DIR_LOCAL" ]; then
    echo "ERROR: files.json missing recordings_dir or sessions_dir" >&2
    exit 1
fi

LOCAL_JAR="$BASE_DIR/java-aiplay/target/java-aiplay-1.0.jar"
REMOTE_JAR="$REMOTE_DIR/java-aiplay/target/java-aiplay-1.0.jar"
REPLAY_BUFFER_BASE="$SESSIONS_DIR_LOCAL/rl-replay-buffer"
JSON_RECORDING_DIR="$SESSIONS_DIR_LOCAL/json-recording-sessions"

# ConvertJsonRecordingsToRecGzMain ondersteunt het joint rl_pawn model.
# SAC is off-policy en de joint converter dist policy-acties uit GameStateDto.
# Zie ConvertJsonRecordingsToRecGzMain Javadoc voor de action-mappings.
CONVERT_CAPABLE=("rl_pawn")
is_convert_capable() {
    local m="$1"
    local c
    for c in "${CONVERT_CAPABLE[@]}"; do
        [ "$m" = "$c" ] && return 0
    done
    return 1
}

MODELS_CSV=$(IFS=, ; echo "${MODELS[*]}")
echo "[replay-export] Recording server: $RECORDING_MACHINE_ID ($RECORDING_HOST)"
echo "[replay-export] Models: $MODELS_CSV"
[ -n "$FORCE_CONVERT_CSV" ] && echo "[replay-export] Force convert: $FORCE_CONVERT_CSV"

# === STAGE 1: local convert (movement/viewrotation only, with cache) =========
# Cache key inputs that actually drive convert behavior:
#   - JSON sessions metadata (the source corpus)
#   - features.json + model.json (input builder shape + sequence length)
#   - jar metadata (Java logic)
# rewards.json is NOT included: convert produces .rec.gz that carry no rewards.
local_convert_key() {
    local model="$1"
    local json_dir="$JSON_RECORDING_DIR/$model"
    {
        echo "JSONS:"
        if [ -d "$json_dir" ]; then
            find "$json_dir" -type f 2>/dev/null | LC_ALL=C sort \
                | xargs -r stat -c "%n %s %Y"
        fi
        echo "MODEL_CFG:"
        for f in "$BASE_DIR/resources/models/$model/features.json" \
                 "$BASE_DIR/resources/models/$model/model.json"; do
            [ -f "$f" ] && sha256sum "$f"
        done
        echo "JAR:"
        [ -f "$LOCAL_JAR" ] && stat -c "%s %Y" "$LOCAL_JAR"
    } | sha256sum | awk '{print $1}'
}

CONVERT_TARGETS=()
for model_key in "${MODELS[@]}"; do
    is_convert_capable "$model_key" || continue
    json_dir="$JSON_RECORDING_DIR/$model_key"
    if [ ! -d "$json_dir" ]; then
        echo "[replay-export] $model_key: no JSON sessions at $json_dir — skip convert"
        continue
    fi
    out_dir="$RECORDINGS_DIR/from-dev/$model_key"
    key_file="$out_dir/.convert-key"
    new_key=$(local_convert_key "$model_key")
    has_recgz=false
    if [ -d "$out_dir" ] && \
       [ -n "$(find "$out_dir" -maxdepth 1 -name '*.rec.gz' -print -quit 2>/dev/null)" ]; then
        has_recgz=true
    fi
    if is_force_convert "$model_key"; then
        echo "[replay-export] $model_key: convert FORCE (--force-convert) — running"
        CONVERT_TARGETS+=("$model_key")
    elif [ -f "$key_file" ] && \
         [ "$new_key" = "$(cat "$key_file" 2>/dev/null)" ] && \
         [ "$has_recgz" = "true" ]; then
        echo "[replay-export] $model_key: convert cache HIT — skip"
    else
        echo "[replay-export] $model_key: convert cache MISS — running"
        CONVERT_TARGETS+=("$model_key")
    fi
done

if [ "${#CONVERT_TARGETS[@]}" -gt 0 ]; then
    if [ ! -f "$LOCAL_JAR" ]; then
        echo "[replay-export] ERROR: $LOCAL_JAR missing — run ./mvnw package -DskipTests" >&2
        exit 1
    fi
    CONVERT_CSV=$(IFS=, ; echo "${CONVERT_TARGETS[*]}")
    echo "[replay-export] Convert: $CONVERT_CSV (streaming push to $RECORDING_HOST in parallel)"

    # Pre-create remote dirs so the parallel watcher rsync below doesn't race.
    sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
        "${RECORDING_USER}@${RECORDING_HOST}" \
        "mkdir -p '$RECORDINGS_DIR/from-dev'" 2>/dev/null || true
    for model_key in "${CONVERT_TARGETS[@]}"; do
        sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
            "${RECORDING_USER}@${RECORDING_HOST}" \
            "mkdir -p '$RECORDINGS_DIR/from-dev/$model_key'" 2>/dev/null || true
    done

    # PropertyReaderUtils resolves resources/config + resources/models from cwd,
    # so we must run from BASE_DIR even when this script is invoked from elsewhere.
    # Convert runs in the background; the watcher loop streams rotated rec.gz
    # files to the recording server in parallel. The active (highest-sequence)
    # file is excluded each cycle and flushed by STAGE 2 after convert exits.
    ( cd "$BASE_DIR" && java -cp "$LOCAL_JAR" aiplay.ConvertJsonRecordingsToRecGzMain ) &
    CONVERT_PID=$!

    (
        set +e
        while kill -0 "$CONVERT_PID" 2>/dev/null; do
            sleep 30
            for model_key in "${CONVERT_TARGETS[@]}"; do
                local_dir="$RECORDINGS_DIR/from-dev/$model_key"
                [ -d "$local_dir" ] || continue
                active_path=$(find "$local_dir" -maxdepth 1 -name '*.rec.gz' 2>/dev/null | sort -V | tail -n 1)
                active="${active_path##*/}"
                sshpass -p "$RECORDING_PASS" rsync -az \
                    --exclude="${active:-_no_active_file_}" \
                    --include="*.rec.gz" --exclude='*' \
                    -e "ssh -o StrictHostKeyChecking=no" \
                    "$local_dir/" \
                    "${RECORDING_USER}@${RECORDING_HOST}:$RECORDINGS_DIR/from-dev/$model_key/" \
                    >/dev/null 2>&1
            done
        done
    ) &
    WATCHER_PID=$!

    set +e
    wait "$CONVERT_PID"
    CONVERT_RC=$?
    set -e

    kill "$WATCHER_PID" 2>/dev/null || true
    wait "$WATCHER_PID" 2>/dev/null || true

    if [ "$CONVERT_RC" -ne 0 ]; then
        echo "[replay-export] ERROR: convert failed (rc=$CONVERT_RC)" >&2
        exit "$CONVERT_RC"
    fi

    for model_key in "${CONVERT_TARGETS[@]}"; do
        out_dir="$RECORDINGS_DIR/from-dev/$model_key"
        mkdir -p "$out_dir"
        local_convert_key "$model_key" > "$out_dir/.convert-key"
    done
fi

# === STAGE 2: push from-dev/<model>/ → recording_server (rsync incremental) ==
sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
    "${RECORDING_USER}@${RECORDING_HOST}" \
    "mkdir -p '$RECORDINGS_DIR/from-dev'" 2>/dev/null || true

for model_key in "${MODELS[@]}"; do
    LOCAL_FROM_DEV="$RECORDINGS_DIR/from-dev/$model_key"
    [ -d "$LOCAL_FROM_DEV" ] || continue
    sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
        "${RECORDING_USER}@${RECORDING_HOST}" \
        "mkdir -p '$RECORDINGS_DIR/from-dev/$model_key'" || true
    sshpass -p "$RECORDING_PASS" rsync -az --delete \
        --include="*.rec.gz" --include=".convert-key" --exclude='*' \
        -e "ssh -o StrictHostKeyChecking=no" \
        "$LOCAL_FROM_DEV/" \
        "${RECORDING_USER}@${RECORDING_HOST}:$RECORDINGS_DIR/from-dev/$model_key/"
done

# === STAGE 3: generate-experience on recording_server (per-model cache) ======
# Cache key inputs (per model):
#   - all .rec.gz across every source root (from-dev + from-servers/<host>)
#   - rewards.json + features.json + model.json
#   - jar metadata
# A model with cache HIT is dropped from --models; if all hit, the Java tool
# isn't invoked at all.
MODELS_REMOTE_LIST="${MODELS[*]}"
sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
    "${RECORDING_USER}@${RECORDING_HOST}" "bash -s" <<REMOTE_EOF || \
    { echo "[replay-export] Java replay tool failed on $RECORDING_HOST" >&2; exit 1; }
        set -e
        cd '$REMOTE_DIR'
        mkdir -p '$REPLAY_BUFFER_BASE'

        srcs=()
        [ -d '$RECORDINGS_DIR/from-dev' ] && srcs+=('$RECORDINGS_DIR/from-dev')
        if [ -d '$RECORDINGS_DIR/from-servers' ]; then
            for host_dir in '$RECORDINGS_DIR/from-servers'/*/; do
                [ -d "\$host_dir" ] && srcs+=("\${host_dir%/}")
            done
        fi
        if [ "\${#srcs[@]}" -eq 0 ]; then
            echo "[replay-export] No source roots under $RECORDINGS_DIR — skip generate"
            exit 0
        fi

        compute_key() {
            local model="\$1"
            {
                echo "REWARDS:"
                [ -f '$REMOTE_DIR'/resources/models/"\$model"/rewards.json ] \
                    && sha256sum '$REMOTE_DIR'/resources/models/"\$model"/rewards.json | awk '{print \$1}'
                echo "MODEL_CFG:"
                for f in '$REMOTE_DIR'/resources/models/"\$model"/features.json \
                         '$REMOTE_DIR'/resources/models/"\$model"/model.json; do
                    [ -f "\$f" ] && sha256sum "\$f"
                done
                echo "CORPUS:"
                local src
                for src in "\${srcs[@]}"; do
                    local mdir="\$src/\$model"
                    [ -d "\$mdir" ] || continue
                    find "\$mdir" -maxdepth 1 -type f -name "*.rec.gz" 2>/dev/null \
                        | LC_ALL=C sort | xargs -r stat -c "%n %s %Y"
                done
                echo "JAR:"
                [ -f '$REMOTE_JAR' ] && stat -c "%s %Y" '$REMOTE_JAR'
            } | sha256sum | awk '{print \$1}'
        }

        models=( $MODELS_REMOTE_LIST )
        stale_models=()
        for model in "\${models[@]}"; do
            out_dir='$REPLAY_BUFFER_BASE'/"\$model"
            mkdir -p "\$out_dir"
            new_key=\$(compute_key "\$model")
            key_file="\$out_dir/.generate-key"
            has_npz=false
            [ -n "\$(find "\$out_dir" -maxdepth 1 -name 'batch_replay-*.npz' -print -quit 2>/dev/null)" ] \
                && has_npz=true
            if [ -f "\$key_file" ] \
               && [ "\$new_key" = "\$(cat "\$key_file" 2>/dev/null)" ] \
               && [ "\$has_npz" = "true" ]; then
                echo "[replay-export] \$model: generate cache HIT — skip"
            else
                echo "[replay-export] \$model: generate cache MISS — re-running"
                stale_models+=("\$model")
            fi
        done

        if [ "\${#stale_models[@]}" -eq 0 ]; then
            echo "[replay-export] All models cached — skip generate-experience"
            exit 0
        fi

        stale_csv=\$(IFS=, ; echo "\${stale_models[*]}")

        # Wipe stale .npz so regenerated batches don't mix with old ones (the
        # ExperienceCollector uses unique filenames, so without this they'd
        # accumulate). Also drop the marker so a mid-run failure leaves the
        # cache invalidated rather than mismatched.
        for model in "\${stale_models[@]}"; do
            find '$REPLAY_BUFFER_BASE'/"\$model" -maxdepth 1 -type f -name "batch_replay-*.npz" -delete 2>/dev/null || true
            rm -f '$REPLAY_BUFFER_BASE'/"\$model"/.generate-key 2>/dev/null || true
        done

        for src in "\${srcs[@]}"; do
            echo "[replay-export] Source root: \$src"
            java -cp '$REMOTE_JAR' aiplay.GenerateExperienceFromRecordingsMain \
                --recordings-dir "\$src" \
                --output-dir '$REPLAY_BUFFER_BASE' \
                --models "\$stale_csv" \
                --breakdown
        done

        for model in "\${stale_models[@]}"; do
            new_key=\$(compute_key "\$model")
            echo "\$new_key" > '$REPLAY_BUFFER_BASE'/"\$model"/.generate-key
        done
REMOTE_EOF

# === STAGE 4: mirror batch_replay-*.npz → assigned trainer ====================
# Mirror semantics (--delete on the include pattern only): trainer's
# batch_replay-*.npz set always equals recording_server's. Other files in the
# directory (live-bot batch_<host>-*.npz) are excluded from delete.
resolve_sac_model_keys
find_primary_trainer || exit 1

resolve_target_for_model() {
    local model_key="$1"
    local machine_id="$TRAINER_MACHINE_ID"
    local assign_file="$SAC_ASSIGNMENTS_PATH"
    if [ -f "$assign_file" ]; then
        while IFS=' ' read -r assigned_model assigned_machine _; do
            [[ -z "${assigned_model:-}" || "${assigned_model:-}" == \#* ]] && continue
            if [ "$assigned_model" = "$model_key" ] && [ -n "${assigned_machine:-}" ]; then
                machine_id="$assigned_machine"
                break
            fi
        done < "$assign_file"
    fi
    find_server_index_by_machine_id "$machine_id" || \
        find_server_index_by_machine_id "$TRAINER_MACHINE_ID"
}

for model_key in "${MODELS[@]}"; do
    target_idx=$(resolve_target_for_model "$model_key") || {
        echo "[replay-export] ERROR: cannot route $model_key — skipping"
        continue
    }
    target_host="${SERVERS[$target_idx]}"
    target_user="${USERS[$target_idx]}"
    target_pass="${PASSES[$target_idx]}"
    target_id="${MACHINE_IDS[$target_idx]}"

    if [ "$target_id" = "$RECORDING_MACHINE_ID" ]; then
        echo "[replay-export] $model_key target $target_id == recording_server — no transfer needed."
        continue
    fi

    REMOTE_BUFFER_DIR="${target_user}@${target_host}:${REPLAY_BUFFER_BASE}/${model_key}/"
    echo "[replay-export] Routing $model_key: ${RECORDING_MACHINE_ID} → ${target_id}"

    sshpass -p "$target_pass" ssh -o StrictHostKeyChecking=no \
        "${target_user}@${target_host}" \
        "mkdir -p '${REPLAY_BUFFER_BASE}/${model_key}'" 2>/dev/null || true

    # Stage to dev as a hop because direct recording→trainer rsync would need
    # ssh keys (we only have sshpass per-host). Mirror semantics on both legs.
    STAGE_DIR=$(mktemp -d)
    trap "rm -rf '$STAGE_DIR'" EXIT

    rsync -az --delete \
        --include="batch_replay-*.npz" --exclude='*' \
        -e "sshpass -p $RECORDING_PASS ssh -o StrictHostKeyChecking=no" \
        "${RECORDING_USER}@${RECORDING_HOST}:${REPLAY_BUFFER_BASE}/${model_key}/" \
        "$STAGE_DIR/" 2>&1 | grep -v "^$" || true

    rsync -az --delete \
        --include="batch_replay-*.npz" --exclude='*' \
        -e "sshpass -p $target_pass ssh -o StrictHostKeyChecking=no" \
        "$STAGE_DIR/" "$REMOTE_BUFFER_DIR" 2>&1 | grep -v "^$" || true

    if [ -n "$(find "$STAGE_DIR" -maxdepth 1 -type f -name 'batch_replay-*.npz' 2>/dev/null)" ]; then
        echo "[replay-export] $model_key: mirrored batches to $target_id"
    else
        echo "[replay-export] $model_key: no batches present (empty corpus?)"
    fi

    rm -rf "$STAGE_DIR"
    trap - EXIT
done

echo "[replay-export] Done."
