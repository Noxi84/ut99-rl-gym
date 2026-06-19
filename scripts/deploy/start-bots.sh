#!/bin/bash
# Start the bot orchestrator (multi_instance.sh) in a tmux session on a server.
#
# Usage:
#   bash scripts/deploy/start-bots.sh --host HOST --user USER --pass PASS
#   bash scripts/deploy/start-bots.sh --all

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

HOST="" USER="" PASS=""
ALL="false"
RAW_RECORDING="false"   # global CAPTURE-mode toggle: skip live npz, dump .rec.gz instead

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --user) USER="$2"; shift 2 ;;
        --pass) PASS="$2"; shift 2 ;;
        --all)  ALL="true"; shift ;;
        --raw-recording) RAW_RECORDING="$2"; shift 2 ;;
        *) shift ;;
    esac
done

ORCH_SCRIPT="$REMOTE_DIR/scripts/runtime/multi_instance.sh"

start_on_host() {
    local host="$1" user="$2" pass="$3"
    # Inject UT99_RAW_RECORDING into the tmux session env so multi_instance.sh
    # picks it up and the JVM sees it via System.getenv. tmux send-environment
    # is brittle; setting it inside the shell command is robust.
    local env_prefix="export UT99_RAW_RECORDING=$RAW_RECORDING; "
    ssh_cmd_quiet "$host" "$user" "$pass" \
        "tmux kill-session -t bots 2>/dev/null; mkdir -p /tmp/ut99-multi; tmux new-session -d -s bots '${env_prefix}bash $ORCH_SCRIPT 2>&1 | tee /tmp/ut99-multi/orchestrator.log'" \
        || true
}

if [ "$ALL" = "true" ]; then
    parse_servers_conf
    for i in "${!SERVERS[@]}"; do
        log_step "Starting bots on ${SERVERS[$i]}..."
        start_on_host "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" &
    done
    wait || true
    log_ok "Bots started on all servers"
elif [ -n "$HOST" ]; then
    start_on_host "$HOST" "$USER" "$PASS"
else
    echo "Usage: $0 --host HOST --user USER --pass PASS"
    echo "       $0 --all"
    exit 1
fi
