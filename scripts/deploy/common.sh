#!/bin/bash
# Shared functions and constants for deploy subscripts.
# Source this file: source "$(dirname "$0")/common.sh"

# --- Constants ---

REMOTE_DIR="/home/kris/projects/ut99neuralnet"
SESSIONS_DIR="/home/kris/projects/ut99neuralnet-sessions"
LOG_DIR="/tmp/ut99-multi"
DEPLOY_LOG_DIR="/tmp/deploy-logs"
VENV_PYTHON="$REMOTE_DIR/.venv/bin/python3"
SAC_ASSIGNMENTS_PATH="$SESSIONS_DIR/sac-trainer-assignments.conf"

# Locate scripts/ and the canonical server inventory relative to this file
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(dirname "$COMMON_DIR")"
PROJECT_DIR="$(dirname "$SCRIPTS_DIR")"
SERVERS_JSON="$PROJECT_DIR/resources/config/servers.json"
SERVER_INVENTORY_MODULE="train.common.ServerInventory"

SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"

# --- Logging helpers ---

log_step() { echo "  $*"; }
log_ok()   { echo "  ✓ $*"; }
log_fail() { echo "  ✗ $*"; }

# --- SSH/rsync wrappers ---

# ssh_cmd HOST USER PASS "command"
ssh_cmd() {
    local host="$1" user="$2" pass="$3" cmd="$4"
    sshpass -p "$pass" ssh $SSH_OPTS "$user@$host" "$cmd" < /dev/null
}

# ssh_cmd_quiet HOST USER PASS "command"  — suppress stdout/stderr
ssh_cmd_quiet() {
    local host="$1" user="$2" pass="$3" cmd="$4"
    sshpass -p "$pass" ssh $SSH_OPTS "$user@$host" "$cmd" < /dev/null >/dev/null 2>&1
}

# rsync_to PASS SRC DEST_HOST DEST_USER DEST_PATH [extra rsync args...]
rsync_to() {
    local pass="$1" src="$2" host="$3" user="$4" dest="$5"
    shift 5
    sshpass -p "$pass" rsync -az "$@" \
        -e "ssh $SSH_OPTS" \
        "$src" "$user@$host:$dest" < /dev/null
}

# --- Server inventory parsing ---

# Global arrays populated by parse_servers_conf
SERVERS=()
USERS=()
PASSES=()
MACHINE_IDS=()
INSTANCES_RAW=()
CUDAS=()
TRAINERS=()
BC_TRAINER_SLOTS=()
SAC_TRAINER_SLOTS=()
CSV_WRITER_SLOTS=()
BC_TRAINER_PRIORITIES=()
DISPLAY_BASES=()
WEB_PORT_BASES=()
GAME_PORT_BASES=()
GAME_PORT_STEPS=()
UDP_PORT_BASES=()
STATE_UDP_PORT_BASES=()
GAME_SPEEDS=()
GAME_STYLES=()
EXTRA_ENV_JSONS=()

normalize_trainer_slots() {
    local raw="${1:-}"
    local lower="${raw,,}"
    case "$lower" in
        true|yes|on)   echo "1" ;;
        false|no|off|"") echo "0" ;;
        *)
            if [[ "$raw" =~ ^[0-9]+$ ]]; then
                echo "$raw"
            else
                return 1
            fi
            ;;
    esac
}

gpu_instances_from_raw() {
    local raw="${1:-0}"
    if [[ "$raw" == */* ]]; then
        echo "${raw%%/*}"
    else
        echo "$raw"
    fi
}

parse_servers_conf() {
    SERVERS=(); USERS=(); PASSES=(); MACHINE_IDS=(); INSTANCES_RAW=()
    CUDAS=(); TRAINERS=(); BC_TRAINER_SLOTS=(); SAC_TRAINER_SLOTS=(); CSV_WRITER_SLOTS=(); BC_TRAINER_PRIORITIES=(); DISPLAY_BASES=(); WEB_PORT_BASES=()
    GAME_PORT_BASES=(); GAME_PORT_STEPS=(); UDP_PORT_BASES=(); STATE_UDP_PORT_BASES=(); GAME_SPEEDS=()
    GAME_STYLES=(); EXTRA_ENV_JSONS=()

    local inventory_lines
    if ! inventory_lines=$(PYTHONPATH="$PROJECT_DIR${PYTHONPATH:+:$PYTHONPATH}" python3 -m "$SERVER_INVENTORY_MODULE" list-tsv 2>/dev/null); then
        echo "ERROR: failed to load server inventory from $SERVERS_JSON"
        exit 1
    fi

    while IFS=$'\t' read -r host user pass machine_id instances cuda bc_trainer_slots_raw sac_trainer_slots_raw csv_writer_slots bc_trainer_priority display_base web_port_base game_port_base game_port_step udp_port_base state_udp_port_base game_speed gamestyle extra_env_json; do
        [ -z "$host" ] && continue
        local bc_trainer_slots sac_trainer_slots
        if ! bc_trainer_slots=$(normalize_trainer_slots "$bc_trainer_slots_raw"); then
            echo "ERROR: invalid bc_trainer_slots value '$bc_trainer_slots_raw' for machine '$machine_id' in server inventory"
            exit 1
        fi
        if ! sac_trainer_slots=$(normalize_trainer_slots "$sac_trainer_slots_raw"); then
            echo "ERROR: invalid sac_trainer_slots value '$sac_trainer_slots_raw' for machine '$machine_id' in server inventory"
            exit 1
        fi
        SERVERS+=("$host")
        USERS+=("$user")
        PASSES+=("$pass")
        MACHINE_IDS+=("$machine_id")
        INSTANCES_RAW+=("$instances")
        CUDAS+=("$cuda")
        BC_TRAINER_SLOTS+=("$bc_trainer_slots")
        SAC_TRAINER_SLOTS+=("$sac_trainer_slots")
        if [ "$bc_trainer_slots" -gt 0 ] || [ "$sac_trainer_slots" -gt 0 ]; then
            TRAINERS+=("true")
        else
            TRAINERS+=("false")
        fi
        CSV_WRITER_SLOTS+=("$csv_writer_slots")
        # Lower bc_trainer_priority = stronger trainer (1 ranks above 2).
        # Missing/empty = sentinel 9999 so the machine sorts last.
        BC_TRAINER_PRIORITIES+=("${bc_trainer_priority:-9999}")
        DISPLAY_BASES+=("$display_base")
        WEB_PORT_BASES+=("$web_port_base")
        GAME_PORT_BASES+=("$game_port_base")
        GAME_PORT_STEPS+=("$game_port_step")
        UDP_PORT_BASES+=("$udp_port_base")
        STATE_UDP_PORT_BASES+=("$state_udp_port_base")
        GAME_SPEEDS+=("$game_speed")
        GAME_STYLES+=("${gamestyle:-classic}")
        EXTRA_ENV_JSONS+=("${extra_env_json:-{}}")
    done <<< "$inventory_lines"
}

# filter_servers FILTER1 [FILTER2 ...] — keep only servers matching any filter substring
filter_servers() {
    local filters=("$@")
    [ ${#filters[@]} -eq 0 ] && return 0

    local new_servers=() new_users=() new_passes=() new_machine_ids=()
    local new_instances=() new_cudas=() new_trainers=() new_bc_trainer_slots=() new_sac_trainer_slots=() new_csv_slots=() new_bc_priorities=() new_display_bases=()
    local new_web_ports=() new_game_ports=() new_game_steps=() new_udp_ports=() new_state_udp_ports=() new_game_speeds=()
    local new_game_styles=() new_extra_env_jsons=()

    for i in "${!SERVERS[@]}"; do
        local host_lower="${SERVERS[$i],,}"
        local matched=false
        for f in "${filters[@]}"; do
            [[ "$host_lower" == *"${f,,}"* ]] && matched=true && break
        done
        [ "$matched" != "true" ] && continue

        new_servers+=("${SERVERS[$i]}")
        new_users+=("${USERS[$i]}")
        new_passes+=("${PASSES[$i]}")
        new_machine_ids+=("${MACHINE_IDS[$i]}")
        new_instances+=("${INSTANCES_RAW[$i]}")
        new_cudas+=("${CUDAS[$i]}")
        new_trainers+=("${TRAINERS[$i]}")
        new_bc_trainer_slots+=("${BC_TRAINER_SLOTS[$i]}")
        new_sac_trainer_slots+=("${SAC_TRAINER_SLOTS[$i]}")
        new_csv_slots+=("${CSV_WRITER_SLOTS[$i]}")
        new_bc_priorities+=("${BC_TRAINER_PRIORITIES[$i]}")
        new_display_bases+=("${DISPLAY_BASES[$i]}")
        new_web_ports+=("${WEB_PORT_BASES[$i]}")
        new_game_ports+=("${GAME_PORT_BASES[$i]}")
        new_game_steps+=("${GAME_PORT_STEPS[$i]}")
        new_udp_ports+=("${UDP_PORT_BASES[$i]}")
        new_state_udp_ports+=("${STATE_UDP_PORT_BASES[$i]}")
        new_game_speeds+=("${GAME_SPEEDS[$i]}")
        new_game_styles+=("${GAME_STYLES[$i]}")
        new_extra_env_jsons+=("${EXTRA_ENV_JSONS[$i]}")
    done

    SERVERS=("${new_servers[@]}")
    USERS=("${new_users[@]}")
    PASSES=("${new_passes[@]}")
    MACHINE_IDS=("${new_machine_ids[@]}")
    INSTANCES_RAW=("${new_instances[@]}")
    CUDAS=("${new_cudas[@]}")
    TRAINERS=("${new_trainers[@]}")
    BC_TRAINER_SLOTS=("${new_bc_trainer_slots[@]}")
    SAC_TRAINER_SLOTS=("${new_sac_trainer_slots[@]}")
    CSV_WRITER_SLOTS=("${new_csv_slots[@]}")
    BC_TRAINER_PRIORITIES=("${new_bc_priorities[@]}")
    DISPLAY_BASES=("${new_display_bases[@]}")
    WEB_PORT_BASES=("${new_web_ports[@]}")
    GAME_PORT_BASES=("${new_game_ports[@]}")
    GAME_PORT_STEPS=("${new_game_steps[@]}")
    UDP_PORT_BASES=("${new_udp_ports[@]}")
    STATE_UDP_PORT_BASES=("${new_state_udp_ports[@]}")
    GAME_SPEEDS=("${new_game_speeds[@]}")
    GAME_STYLES=("${new_game_styles[@]}")
    EXTRA_ENV_JSONS=("${new_extra_env_jsons[@]}")
}

# resolve_all_model_keys — reads ALL model keys from models/index.json
ALL_MODEL_KEYS=()
resolve_all_model_keys() {
    ALL_MODEL_KEYS=()
    local index_json="$PROJECT_DIR/resources/models/index.json"
    if [ ! -f "$index_json" ]; then
        echo "ERROR: $index_json not found"
        exit 1
    fi
    local keys
    keys=$(python3 -c "
import json
with open('$index_json') as f:
    models = json.load(f).get('models', [])
for m in models:
    k = m.get('model_key', '')
    if k:
        print(k)
" 2>/dev/null)
    if [ -z "$keys" ]; then
        echo "ERROR: could not parse $index_json"
        exit 1
    fi
    while IFS= read -r k; do
        [ -n "$k" ] && ALL_MODEL_KEYS+=("$k")
    done <<< "$keys"
}

SAC_MODEL_KEYS=()
resolve_sac_model_keys() {
    SAC_MODEL_KEYS=()
    resolve_all_model_keys
    for mk in "${ALL_MODEL_KEYS[@]}"; do
        local sac_json="$PROJECT_DIR/resources/models/$mk/sac.json"
        [ -f "$sac_json" ] || continue
        local enabled
        enabled=$(python3 -c "import json; print(json.load(open('$sac_json')).get('experience_sync_enabled', False))" 2>/dev/null || echo "False")
        if [ "$enabled" = "True" ]; then
            SAC_MODEL_KEYS+=("$mk")
        fi
    done
}

find_server_index_by_machine_id() {
    local needle="${1:-}"
    for i in "${!MACHINE_IDS[@]}"; do
        if [ "${MACHINE_IDS[$i],,}" = "${needle,,}" ]; then
            echo "$i"
            return 0
        fi
    done
    return 1
}

PRIMARY_TRAINER_INDEX=""
TRAINER_HOST=""
TRAINER_USER=""
TRAINER_PASS=""
TRAINER_MACHINE_ID=""

find_primary_trainer_index() {
    PRIMARY_TRAINER_INDEX=""
    local lines=()
    for i in "${!SERVERS[@]}"; do
        local bc_slots="${BC_TRAINER_SLOTS[$i]}"
        local sac_slots="${SAC_TRAINER_SLOTS[$i]}"
        local total_slots=$(( bc_slots + sac_slots ))
        [ "$total_slots" -gt 0 ] 2>/dev/null || continue
        local gpu_count
        gpu_count=$(gpu_instances_from_raw "${INSTANCES_RAW[$i]}")
        lines+=("$(printf '%04d %04d %s %d' $((9999 - total_slots)) $((9999 - gpu_count)) "${MACHINE_IDS[$i]}" "$i")")
    done
    if [ ${#lines[@]} -eq 0 ]; then
        echo "ERROR: no trainer machine found in server inventory (bc_trainer_slots + sac_trainer_slots > 0)"
        return 1
    fi
    local first
    first=$(printf '%s\n' "${lines[@]}" | sort | head -n1)
    read -r _slot_key _gpu_key _machine_key idx <<< "$first"
    if [ -z "${idx:-}" ]; then
        echo "ERROR: could not determine primary trainer from server inventory"
        return 1
    fi
    PRIMARY_TRAINER_INDEX="$idx"
    return 0
}

find_primary_trainer() {
    find_primary_trainer_index || return 1
    TRAINER_HOST="${SERVERS[$PRIMARY_TRAINER_INDEX]}"
    TRAINER_USER="${USERS[$PRIMARY_TRAINER_INDEX]}"
    TRAINER_PASS="${PASSES[$PRIMARY_TRAINER_INDEX]}"
    TRAINER_MACHINE_ID="${MACHINE_IDS[$PRIMARY_TRAINER_INDEX]}"
    return 0
}

# Compatibility alias: "trainer" now means the primary trainer selected from
# all machines with trainer slots > 0.
find_trainer() {
    find_primary_trainer
}

# Recording server: single sink for raw .rec.gz files from bots + dev human
# recordings. Resolved from servers.json's top-level "recording_server" field
# (machine_id). Sets RECORDING_HOST / USER / PASS / MACHINE_ID.
RECORDING_HOST=""
RECORDING_USER=""
RECORDING_PASS=""
RECORDING_MACHINE_ID=""

find_recording_server() {
    RECORDING_HOST=""; RECORDING_USER=""; RECORDING_PASS=""; RECORDING_MACHINE_ID=""
    local servers_json="$BASE_DIR/resources/config/servers.json"
    [ -f "$servers_json" ] || servers_json="$LOAD_DEPLOY_PROJECT_DIR/resources/config/servers.json"
    if [ ! -f "$servers_json" ]; then
        echo "ERROR: servers.json not found while resolving recording_server" >&2
        return 1
    fi
    local rec_id
    rec_id=$(python3 -c "
import json, sys
with open('$servers_json') as f:
    data = json.load(f)
v = data.get('recording_server')
print(v if v else '', end='')
")
    if [ -z "$rec_id" ]; then
        echo "ERROR: servers.json missing top-level 'recording_server' field" >&2
        return 1
    fi
    local idx
    if ! idx=$(find_server_index_by_machine_id "$rec_id"); then
        echo "ERROR: recording_server '$rec_id' not found in servers.json machines list" >&2
        return 1
    fi
    RECORDING_HOST="${SERVERS[$idx]}"
    RECORDING_USER="${USERS[$idx]}"
    RECORDING_PASS="${PASSES[$idx]}"
    RECORDING_MACHINE_ID="${MACHINE_IDS[$idx]}"
    return 0
}

# get_csv_writers — populates CSV_WRITER_* arrays sorted by csv_writer_slots desc.
# Tie-break: trainer first, then machine_id alphabetically.
# Only includes servers with csv_writer_slots > 0.
#
# csv_writer_slots is the direct capacity proxy: it determines how many concurrent
# CSV-writer JVMs a machine runs. GPU-bot count is decoupled from CSV-writer
# capacity (the primary trainer typically has gpu_instances=0 but is the most
# powerful CSV writer in absolute terms), so sorting by GPU count would bury the
# trainer at the end of the shard-assignment queue and starve it of large ZIPs.
CSV_WRITER_HOSTS=()
CSV_WRITER_USERS=()
CSV_WRITER_PASSES=()
CSV_WRITER_MACHINE_IDS=()
CSV_WRITER_SLOT_COUNTS=()
CSV_WRITER_GPU_INSTANCES=()

get_csv_writers() {
    CSV_WRITER_HOSTS=(); CSV_WRITER_USERS=(); CSV_WRITER_PASSES=()
    CSV_WRITER_MACHINE_IDS=(); CSV_WRITER_SLOT_COUNTS=(); CSV_WRITER_GPU_INSTANCES=()

    local primary_idx=""
    find_primary_trainer_index >/dev/null 2>&1 || true
    primary_idx="${PRIMARY_TRAINER_INDEX:-}"

    # Collect eligible writers as "sort_key index" lines.
    # Primary sort: csv_writer_slots descending (negate). Tie-break: trainer first
    # (0 before 1), then machine_id alphabetically for stable ordering across runs.
    local lines=()
    for i in "${!SERVERS[@]}"; do
        local slots="${CSV_WRITER_SLOTS[$i]}"
        [ "$slots" -gt 0 ] 2>/dev/null || continue

        local trainer_sort=1
        [ -n "$primary_idx" ] && [ "$i" -eq "$primary_idx" ] && trainer_sort=0
        lines+=("$(printf '%04d %d %s %d' $((9999 - slots)) $trainer_sort "${MACHINE_IDS[$i]}" $i)")
    done

    # Sort and populate arrays
    local sorted
    sorted=$(printf '%s\n' "${lines[@]}" | sort)
    while read -r _slots_key _trainer_key _mid_key idx; do
        [ -z "$idx" ] && continue
        CSV_WRITER_HOSTS+=("${SERVERS[$idx]}")
        CSV_WRITER_USERS+=("${USERS[$idx]}")
        CSV_WRITER_PASSES+=("${PASSES[$idx]}")
        CSV_WRITER_MACHINE_IDS+=("${MACHINE_IDS[$idx]}")
        CSV_WRITER_SLOT_COUNTS+=("${CSV_WRITER_SLOTS[$idx]}")
        local raw="${INSTANCES_RAW[$idx]}"
        CSV_WRITER_GPU_INSTANCES+=("$(gpu_instances_from_raw "$raw")")
    done <<< "$sorted"
}

TRAINER_WORKER_HOSTS=()
TRAINER_WORKER_USERS=()
TRAINER_WORKER_PASSES=()
TRAINER_WORKER_MACHINE_IDS=()
TRAINER_WORKER_SLOT_COUNTS=()
TRAINER_WORKER_GPU_INSTANCES=()

# get_trainer_workers <bc|sac>
# Populates TRAINER_WORKER_* arrays filtered and sorted by the given slot type.
get_trainer_workers() {
    local slot_type="${1:?Usage: get_trainer_workers <bc|sac>}"
    TRAINER_WORKER_HOSTS=(); TRAINER_WORKER_USERS=(); TRAINER_WORKER_PASSES=()
    TRAINER_WORKER_MACHINE_IDS=(); TRAINER_WORKER_SLOT_COUNTS=(); TRAINER_WORKER_GPU_INSTANCES=()

    local lines=()
    for i in "${!SERVERS[@]}"; do
        local slots
        if [ "$slot_type" = "sac" ]; then
            slots="${SAC_TRAINER_SLOTS[$i]}"
        else
            slots="${BC_TRAINER_SLOTS[$i]}"
        fi
        [ "$slots" -gt 0 ] 2>/dev/null || continue
        # BC ranks on bc_trainer_priority where LOWER = stronger (prio 1 beats
        # prio 2). Trainer-only machines have gpu_instances=0 (that's bot
        # capacity, not GPU power), so we cannot derive BC strength from it.
        # SAC stays on gpu_count where HIGHER = better collocation host.
        local rank_key
        if [ "$slot_type" = "bc" ]; then
            rank_key="${BC_TRAINER_PRIORITIES[$i]:-9999}"
        else
            rank_key=$((9999 - $(gpu_instances_from_raw "${INSTANCES_RAW[$i]}")))
        fi
        lines+=("$(printf '%04d %04d %s %d' $((9999 - slots)) "$rank_key" "${MACHINE_IDS[$i]}" "$i")")
    done

    [ ${#lines[@]} -eq 0 ] && return 0

    local sorted
    sorted=$(printf '%s\n' "${lines[@]}" | sort)
    while read -r _slot_key _gpu_key _machine_key idx; do
        [ -z "$idx" ] && continue
        TRAINER_WORKER_HOSTS+=("${SERVERS[$idx]}")
        TRAINER_WORKER_USERS+=("${USERS[$idx]}")
        TRAINER_WORKER_PASSES+=("${PASSES[$idx]}")
        TRAINER_WORKER_MACHINE_IDS+=("${MACHINE_IDS[$idx]}")
        if [ "$slot_type" = "sac" ]; then
            TRAINER_WORKER_SLOT_COUNTS+=("${SAC_TRAINER_SLOTS[$idx]}")
        else
            TRAINER_WORKER_SLOT_COUNTS+=("${BC_TRAINER_SLOTS[$idx]}")
        fi
        TRAINER_WORKER_GPU_INSTANCES+=("$(gpu_instances_from_raw "${INSTANCES_RAW[$idx]}")")
    done <<< "$sorted"
}
