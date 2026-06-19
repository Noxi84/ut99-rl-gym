#!/bin/bash
# Clean experience files (.npz) and optionally models, CSVs, or BC cache.
#
# Operates either globally (across all models) or per-model. Both modes can be
# combined in one invocation. Per-model flags accept a space-separated list.
#
# Usage:
#   bash scripts/deploy/clean-experience.sh --all
#   bash scripts/deploy/clean-experience.sh --all --max-age 3600         # age in seconds (required value)
#   bash scripts/deploy/clean-experience.sh --all --clean-models --clean-csv
#   bash scripts/deploy/clean-experience.sh --host HOST --user USER --pass PASS [...]
#
# Per-model:
#   bash scripts/deploy/clean-experience.sh --all \
#       --wipe-experience-for "rl_pawn" \
#       --wipe-models-for "rl_pawn" \
#       --wipe-csv-for "rl_pawn" \
#       --wipe-bc-cache-for "rl_pawn"
#
# Notes:
#   --max-age applies only to global experience cleanup, not per-model wipes.
#   Per-model wipes always remove all matching files for the listed models.

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

HOST="" USER="" PASS=""
ALL="false"
CLEAN_MODELS="false"
CLEAN_CSV="false"
CLEAN_BC_CACHE="false"
USE_MAX_AGE="false"
MAX_AGE_SECONDS=""

WIPE_EXPERIENCE_FOR=""
WIPE_MODELS_FOR=""
WIPE_CSV_FOR=""
WIPE_BC_CACHE_FOR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host)            HOST="$2"; shift 2 ;;
        --user)            USER="$2"; shift 2 ;;
        --pass)            PASS="$2"; shift 2 ;;
        --all)             ALL="true"; shift ;;
        --clean-models)    CLEAN_MODELS="true"; shift ;;
        --clean-csv)       CLEAN_CSV="true"; shift ;;
        --clean-bc-cache)  CLEAN_BC_CACHE="true"; shift ;;
        --max-age)
            USE_MAX_AGE="true"
            if [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]]; then
                MAX_AGE_SECONDS="$2"; shift 2
            else
                echo "ERROR: --max-age requires a numeric SECONDS argument"
                exit 1
            fi
            ;;
        --wipe-experience-for) WIPE_EXPERIENCE_FOR="$2"; shift 2 ;;
        --wipe-models-for)     WIPE_MODELS_FOR="$2"; shift 2 ;;
        --wipe-csv-for)        WIPE_CSV_FOR="$2"; shift 2 ;;
        --wipe-bc-cache-for)   WIPE_BC_CACHE_FOR="$2"; shift 2 ;;
        *) shift ;;
    esac
done

# Build per-host cleanup command. All operations are appended to a single
# compound command and run in a single SSH round per host.
build_clean_cmd() {
    local cmd=""

    # Global experience cleanup (with optional age filter).
    if [ "$USE_MAX_AGE" = "true" ]; then
        local age_minutes=$(( MAX_AGE_SECONDS / 60 ))
        cmd="find $SESSIONS_DIR/rl-replay-buffer/ -name 'batch_*.npz' -mmin +${age_minutes} -delete 2>/dev/null; true"
    else
        # Only wipe globally if no per-model experience list was given AND no
        # per-model lists are present at all (= manual global invocation).
        if [ -z "$WIPE_EXPERIENCE_FOR$WIPE_MODELS_FOR$WIPE_CSV_FOR$WIPE_BC_CACHE_FOR" ]; then
            cmd="find $SESSIONS_DIR/rl-replay-buffer/ -name 'batch_*.npz' -delete 2>/dev/null; true"
        else
            cmd="true"
        fi
    fi

    if [ "$CLEAN_MODELS" = "true" ]; then
        cmd="$cmd; rm -rf $SESSIONS_DIR/models/trainingmodel && mkdir -p $SESSIONS_DIR/models/trainingmodel"
    fi
    if [ "$CLEAN_CSV" = "true" ]; then
        cmd="$cmd; rm -rf $SESSIONS_DIR/csv-training-data && mkdir -p $SESSIONS_DIR/csv-training-data"
        cmd="$cmd; rm -rf $SESSIONS_DIR/csv-training-data-staging"
        cmd="$cmd; rm -rf $SESSIONS_DIR/csv-training-data-inbox"
        cmd="$cmd; rm -f /tmp/csv_shard_*.txt"
    fi
    if [ "$CLEAN_BC_CACHE" = "true" ]; then
        cmd="$cmd; rm -rf $SESSIONS_DIR/bc-cache 2>/dev/null; true"
        cmd="$cmd; find $SESSIONS_DIR/csv-training-data -name 'bc_cache_*' -delete 2>/dev/null; true"
    fi

    local k
    for k in $WIPE_EXPERIENCE_FOR; do
        cmd="$cmd; rm -rf $SESSIONS_DIR/rl-replay-buffer/$k 2>/dev/null; true"
    done
    for k in $WIPE_MODELS_FOR; do
        cmd="$cmd; rm -f $SESSIONS_DIR/models/trainingmodel/${k}.* 2>/dev/null; true"
        cmd="$cmd; rm -f $SESSIONS_DIR/models/trainingmodel/${k}_*.* 2>/dev/null; true"
    done
    for k in $WIPE_CSV_FOR; do
        cmd="$cmd; rm -rf $SESSIONS_DIR/csv-training-data/$k 2>/dev/null; true"
    done
    for k in $WIPE_BC_CACHE_FOR; do
        cmd="$cmd; rm -rf $SESSIONS_DIR/bc-cache/$k 2>/dev/null; true"
    done

    echo "$cmd"
}

clean_on_host() {
    local host="$1" user="$2" pass="$3"
    local cmd
    cmd=$(build_clean_cmd)
    ssh_cmd_quiet "$host" "$user" "$pass" "$cmd" || true
}

if [ "$ALL" = "true" ]; then
    parse_servers_conf
    for i in "${!SERVERS[@]}"; do
        clean_on_host "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" &
    done
    wait || true
    if [ "$USE_MAX_AGE" = "true" ]; then
        log_ok "Experience cleaned on all servers (files older than ${MAX_AGE_SECONDS}s)"
    elif [ -z "$WIPE_EXPERIENCE_FOR$WIPE_MODELS_FOR$WIPE_CSV_FOR$WIPE_BC_CACHE_FOR" ]; then
        log_ok "Experience cleaned on all servers (all files)"
    fi
    [ "$CLEAN_MODELS" = "true" ] && log_ok "Models cleaned" || true
    [ "$CLEAN_CSV" = "true" ] && log_ok "CSV training data cleaned" || true
    [ "$CLEAN_BC_CACHE" = "true" ] && log_ok "BC cache cleaned" || true
    [ -n "$WIPE_EXPERIENCE_FOR" ] && log_ok "Experience wiped for: $WIPE_EXPERIENCE_FOR" || true
    [ -n "$WIPE_MODELS_FOR" ]     && log_ok "Models wiped for: $WIPE_MODELS_FOR" || true
    [ -n "$WIPE_CSV_FOR" ]        && log_ok "CSV wiped for: $WIPE_CSV_FOR" || true
    [ -n "$WIPE_BC_CACHE_FOR" ]   && log_ok "BC cache wiped for: $WIPE_BC_CACHE_FOR" || true
elif [ -n "$HOST" ]; then
    clean_on_host "$HOST" "$USER" "$PASS"
else
    echo "Usage: $0 --host HOST --user USER --pass PASS [options]"
    echo "       $0 --all [options]"
    echo ""
    echo "Global options:"
    echo "  --max-age SECONDS                Wipe .npz older than threshold (required numeric value)"
    echo "                                   (no --max-age + no per-model flag = wipe all .npz)"
    echo "  --clean-models                   Wipe entire models/trainingmodel/ dir"
    echo "  --clean-csv                      Wipe entire csv-training-data/ + staging/inbox dirs"
    echo "  --clean-bc-cache                 Wipe entire bc-cache/ dir + legacy bc_cache_* files"
    echo ""
    echo "Per-model options (space-separated model_keys):"
    echo "  --wipe-experience-for \"k1 k2\"    rm -rf rl-replay-buffer/<k>/"
    echo "  --wipe-models-for \"k1 k2\"        rm -f models/trainingmodel/<k>.* and <k>_*.*"
    echo "  --wipe-csv-for \"k1 k2\"           rm -rf csv-training-data/<k>/"
    echo "  --wipe-bc-cache-for \"k1 k2\"      rm -rf bc-cache/<k>/"
    exit 1
fi
