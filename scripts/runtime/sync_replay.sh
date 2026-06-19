#!/bin/bash
# Periodic experience sync. Two modes:
#
#   NORMAL  (UT99_RAW_RECORDING=false / unset):
#     - Sync rl-replay-buffer/<model>/batch_<host>-*.npz to assigned SAC
#       trainer per model (existing flow).
#
#   CAPTURE (UT99_RAW_RECORDING=true):
#     - Sync experience-recordings/from-servers/<host>/<model>/*.rec.gz to the
#       central recording_server. Live npz writing is OFF (BotRuntimeFactory),
#       so there are no npz files to sync.
#
# In both modes: only files older than RAW_REC_MIN_AGE_MIN are pushed (avoids
# truncating an actively-rotating .rec.gz). `--remove-source-files` so the
# local copy is moved, not duplicated.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$(dirname "$SCRIPTS_DIR")"

source "$SCRIPTS_DIR/deploy/common.sh"
parse_servers_conf

detect_machine_id() {
    if [[ -n "${UT99_MACHINE_ID:-}" ]]; then
        echo "$UT99_MACHINE_ID"; return
    fi
    local hn; hn=$(hostname)
    local short="${hn%%.*}"
    for i in "${!SERVERS[@]}"; do
        local host="${SERVERS[$i]}"
        local host_short="${host%%.*}"
        if [[ "${hn,,}" == "${host,,}" || "${short,,}" == "${host_short,,}" ]]; then
            echo "${MACHINE_IDS[$i]}"; return
        fi
    done
    echo "$hn"
}

MACHINE_ID=$(detect_machine_id)

# Check if any AI bot is active — skip sync when no active bots produce experience
HAS_ACTIVE_RL_BOT=$(python3 -c "
import json, sys
with open('$BASE_DIR/resources/config/gameplay.json') as f:
    ai_bots = json.load(f).get('ai_bots', [])
print('true' if any(b.get('active') for b in ai_bots) else 'false')
")
if [ "$HAS_ACTIVE_RL_BOT" != "true" ]; then
    echo "No active RL bots in gameplay.json. Skipping experience sync."
    exit 0
fi

read_files_path() {
    python3 -c "
import json
with open('$BASE_DIR/resources/config/files.json') as f:
    print(json.load(f).get('$1', ''), end='')
"
}

SESSIONS_DIR=$(read_files_path "sessions_dir")
RECORDINGS_DIR=$(read_files_path "recordings_dir")
# Interval 10s (was 30s): kleinere bundels per sync zodat een transfer kort en
# licht blijft; de SAC-trainer ingest elke 2s dus krijgt bovendien verser materiaal.
INTERVAL=${SYNC_INTERVAL:-10}
RAW_REC_MIN_AGE_MIN=${RAW_REC_MIN_AGE_MIN:-2}
RAW_RECORDING="${UT99_RAW_RECORDING:-false}"
# Bandbreedte-shaping (KB/s). Zonder limiet ging een 30s-accumulatie (~200-300MB
# NPZ op de 4070/3070-vloot) op gigabit-LIJNRATE naar de trainer: de uplink van
# de bot-machine stond ~2-3s volledig dicht → UDP-jitter op de game/view-stream →
# zichtbaar schokkerige bots vlak vóór elke grote trainer-ingest. 20MB/s = ~16%
# van gigabit; productie is max ~10MB/s (4070: 7 inst × 4 bots × 30Hz × ~12KB)
# dus de sync loopt altijd bij terwijl de lijn vrij blijft voor game-verkeer.
BWLIMIT_KBPS=${SYNC_BWLIMIT_KBPS:-20000}

if [ "$RAW_RECORDING" = "true" ]; then
    # =========================================================
    #  CAPTURE-MODE: sync .rec.gz to recording_server
    # =========================================================
    find_recording_server || exit 1
    if [ "$MACHINE_ID" = "$RECORDING_MACHINE_ID" ]; then
        echo "[$(date)] On recording_server ($RECORDING_MACHINE_ID); no sync needed."
        exit 0
    fi
    if [ -z "$RECORDINGS_DIR" ]; then
        echo "ERROR: files.json missing 'recordings_dir'"
        exit 1
    fi

    LOCAL_BASE="$RECORDINGS_DIR/from-servers/$MACHINE_ID"
    REMOTE_BASE_DIR="$RECORDINGS_DIR/from-servers/$MACHINE_ID/"
    REMOTE_TARGET="${RECORDING_USER}@${RECORDING_HOST}:${REMOTE_BASE_DIR}"
    mkdir -p "$LOCAL_BASE"

    echo "[$(date)] CAPTURE-mode sync from $MACHINE_ID -> $RECORDING_MACHINE_ID"
    echo "[$(date)]   local : $LOCAL_BASE"
    echo "[$(date)]   remote: $REMOTE_TARGET"

    while true; do
        if [[ -d "$LOCAL_BASE" ]]; then
            for model_dir in "$LOCAL_BASE"/*/; do
                [ -d "$model_dir" ] || continue
                mapfile -t -d '' RAW_FILES < <(find "$model_dir" -maxdepth 1 -type f \
                    -name "*.rec.gz" -mmin +"$RAW_REC_MIN_AGE_MIN" -print0 2>/dev/null)
                if (( ${#RAW_FILES[@]} > 0 )); then
                    model_name="$(basename "$model_dir")"
                    REMOTE_MODEL_DIR="${RECORDING_USER}@${RECORDING_HOST}:${RECORDINGS_DIR}/from-servers/${MACHINE_ID}/${model_name}/"
                    sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
                        "${RECORDING_USER}@${RECORDING_HOST}" \
                        "mkdir -p '${RECORDINGS_DIR}/from-servers/${MACHINE_ID}/${model_name}'" \
                        2>/dev/null || true
                    # Geen -z: .rec.gz is al gzip-gecomprimeerd; hercompressie kost
                    # alleen CPU. --bwlimit voorkomt uplink-verzadiging (UDP-jitter).
                    rsync -a --bwlimit="$BWLIMIT_KBPS" --remove-source-files \
                        -e "sshpass -p $RECORDING_PASS ssh -o StrictHostKeyChecking=no" \
                        "${RAW_FILES[@]}" "$REMOTE_MODEL_DIR" 2>&1 | grep -v "^$" || true
                fi
            done
        fi
        sleep "$INTERVAL"
    done
fi

# =========================================================
#  NORMAL-MODE: sync .npz to SAC trainers
# =========================================================
find_primary_trainer || exit 1
resolve_sac_model_keys

if [ ${#SAC_MODEL_KEYS[@]} -eq 0 ]; then
    echo "No SAC-enabled models found. No replay sync needed."
    exit 0
fi

REPLAY_BASE="$SESSIONS_DIR/rl-replay-buffer"
ASSIGN_FILE="$SAC_ASSIGNMENTS_PATH"
MODEL_KEYS=("${SAC_MODEL_KEYS[@]}")

declare -A MODEL_TARGET_MACHINE=()
declare -A MODEL_TARGET_HOST=()
declare -A MODEL_TARGET_USER=()
declare -A MODEL_TARGET_PASS=()

resolve_target_machine() {
    local model_key="$1"
    local machine_id="$TRAINER_MACHINE_ID"

    if [ -f "$ASSIGN_FILE" ]; then
        while IFS=' ' read -r assigned_model assigned_machine _; do
            [[ -z "${assigned_model:-}" || "${assigned_model:-}" == \#* ]] && continue
            if [ "$assigned_model" = "$model_key" ] && [ -n "${assigned_machine:-}" ]; then
                machine_id="$assigned_machine"
                break
            fi
        done < "$ASSIGN_FILE"
    fi

    local idx
    if idx=$(find_server_index_by_machine_id "$machine_id"); then
        if [ "${SAC_TRAINER_SLOTS[$idx]:-0}" -gt 0 ]; then
            echo "$idx"
            return 0
        fi
    fi

    if idx=$(find_server_index_by_machine_id "$TRAINER_MACHINE_ID"); then
        echo "$idx"
        return 0
    fi
    return 1
}

refresh_routes() {
    for mk in "${MODEL_KEYS[@]}"; do
        target_idx=$(resolve_target_machine "$mk") || {
            echo "ERROR: could not resolve target trainer for model $mk"
            return 1
        }
        MODEL_TARGET_MACHINE["$mk"]="${MACHINE_IDS[$target_idx]}"
        MODEL_TARGET_HOST["$mk"]="${SERVERS[$target_idx]}"
        MODEL_TARGET_USER["$mk"]="${USERS[$target_idx]}"
        MODEL_TARGET_PASS["$mk"]="${PASSES[$target_idx]}"
    done
    return 0
}

for mk in "${MODEL_KEYS[@]}"; do
    mkdir -p "$REPLAY_BASE/$mk"
done

refresh_routes || exit 1
for mk in "${MODEL_KEYS[@]}"; do
    echo "[$(date)] Initial route: $mk -> ${MODEL_TARGET_MACHINE[$mk]}"
done

echo "[$(date)] NORMAL-mode replay buffer sync from $MACHINE_ID (every ${INTERVAL}s)"
echo "[$(date)] Model keys: ${MODEL_KEYS[*]}"

while true; do
    refresh_routes || true
    for mk in "${MODEL_KEYS[@]}"; do
        if [[ "$MACHINE_ID" == "${MODEL_TARGET_MACHINE[$mk]}" ]]; then
            continue
        fi
        LOCAL_DIR="$REPLAY_BASE/$mk/"
        REMOTE_DIR="${MODEL_TARGET_USER[$mk]}@${MODEL_TARGET_HOST[$mk]}:$REPLAY_BASE/$mk/"
        # Geen -z: NPZ is al deflate-gecomprimeerd (ReplayBufferWriter), dubbele
        # compressie levert ~0% op en kost een volle core. --bwlimit shaped de
        # burst zodat de uplink nooit verzadigt (schokkerige bots in de game).
        rsync -a --bwlimit="$BWLIMIT_KBPS" --remove-source-files \
            --include="batch_${MACHINE_ID}_*.npz" --exclude='*' \
            -e "sshpass -p ${MODEL_TARGET_PASS[$mk]} ssh -o StrictHostKeyChecking=no" \
            "$LOCAL_DIR" "$REMOTE_DIR" 2>&1 | grep -v "^$" || true

        # Retentie: drop lokale npz ouder dan de trainer-max_file_age (3600s = 60min).
        # Een host die sneller produceert dan z'n sync-bandbreedte loopt anders vol:
        # de 5-instance 4070 schrijft ~22MB-npz aan ~44MB/s, maar --bwlimit capt de
        # uplink op 20MB/s (=$BWLIMIT_KBPS). De ~24MB/s surplus stapelde op tot de
        # schijf 100% vol stond en de bot-JVM crashte (2026-06-13: onnxruntime
        # native-lib kon niet uitpakken — "No space left on device"). De trainer
        # negeert npz ouder dan max_file_age sowieso, dus dit gooit alleen
        # niet-meer-ingestbare surplus weg; z'n 200k-ring blijft verzadigd uit de
        # binnen-bandbreedte sync. Draait enkel op niet-trainer hosts (de trainer
        # `continue`t hierboven), dus $LOCAL_DIR bevat alleen deze host z'n eigen npz.
        find "$LOCAL_DIR" -maxdepth 1 -name "batch_${MACHINE_ID}_*.npz" -mmin +60 -delete 2>/dev/null || true
    done
    sleep "$INTERVAL"
done
