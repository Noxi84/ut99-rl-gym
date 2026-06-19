#!/bin/bash
# Clean log files on one or all servers.
#
# Usage:
#   bash scripts/deploy/clean-logs.sh --host HOST --user USER --pass PASS [--with-trainer-state]
#   bash scripts/deploy/clean-logs.sh --all [--with-trainer-state]
#
# --with-trainer-state additionally drops a sentinel that tells the SAC
# trainer's bootstrap to reset the DualKPIDeltaGate in-flight eval clock —
# necessary because we just wiped the KPI source logs, so the gate's
# wall-clock window from before this deploy no longer matches reality.

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

HOST="" USER="" PASS=""
ALL="false"
WITH_TRAINER_STATE="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --user) USER="$2"; shift 2 ;;
        --pass) PASS="$2"; shift 2 ;;
        --all)  ALL="true"; shift ;;
        --with-trainer-state) WITH_TRAINER_STATE="true"; shift ;;
        *) shift ;;
    esac
done

clean_logs_on_host() {
    local host="$1" user="$2" pass="$3" with_trainer="$4"
    local cmd="rm -f /tmp/ut99-multi/instance_*.log /tmp/ut99-multi/orchestrator.log /tmp/ut99-multi/sync_replay.log 2>/dev/null; : > /tmp/ut99-multi/multi_instance.log 2>/dev/null; rm -rf $SESSIONS_DIR/logs/instance-*/ 2>/dev/null"
    if [ "$with_trainer" = "true" ]; then
        cmd="$cmd; mkdir -p $SESSIONS_DIR/models/trainingmodel 2>/dev/null; touch $SESSIONS_DIR/models/trainingmodel/_reset_inflight_clock.flag 2>/dev/null"
    fi
    ssh_cmd_quiet "$host" "$user" "$pass" "$cmd" || true
}

if [ "$ALL" = "true" ]; then
    parse_servers_conf
    for i in "${!SERVERS[@]}"; do
        clean_logs_on_host "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" "$WITH_TRAINER_STATE" &
    done
    wait || true
    log_ok "Logs cleaned on all servers"
elif [ -n "$HOST" ]; then
    clean_logs_on_host "$HOST" "$USER" "$PASS" "$WITH_TRAINER_STATE"
else
    echo "Usage: $0 --host HOST --user USER --pass PASS [--with-trainer-state]"
    echo "       $0 --all [--with-trainer-state]"
    exit 1
fi
