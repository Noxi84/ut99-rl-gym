#!/bin/bash
# Kill bot processes on one or all servers.
#
# Usage:
#   bash scripts/deploy/kill-processes.sh --host HOST --user USER --pass PASS [--kill-sac]
#   bash scripts/deploy/kill-processes.sh --all [--kill-sac]
#   bash scripts/deploy/kill-processes.sh --all --bots-only

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

KILL_SAC="false"
BOTS_ONLY="false"
HOST="" USER="" PASS=""
ALL="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --kill-sac) KILL_SAC="true"; shift ;;
        --bots-only) BOTS_ONLY="true"; shift ;;
        --all)      ALL="true"; shift ;;
        --host)     HOST="$2"; shift 2 ;;
        --user)     USER="$2"; shift 2 ;;
        --pass)     PASS="$2"; shift 2 ;;
        *)          shift ;;
    esac
done

kill_on_host() {
    local host="$1" user="$2" pass="$3"

    local kill_trainer_cmds=""
    if [ "$KILL_SAC" = "true" ]; then
        kill_trainer_cmds="$kill_trainer_cmds"'
tmux ls 2>/dev/null | awk -F: '"'"'/^sac_/ {print $1}'"'"' | while read -r session; do
    [ -n "$session" ] && tmux kill-session -t "$session" 2>/dev/null || true
done
pkill -f "\.trainSAC" 2>/dev/null || true'
    fi

sshpass -p "$pass" ssh $SSH_OPTS "$user@$host" "bash -s" <<KILL_EOF >/dev/null 2>&1 || true
tmux kill-session -t bots 2>/dev/null || true
if [ "$BOTS_ONLY" != "true" ]; then
tmux kill-session -t bc 2>/dev/null || true
pkill -f '\.trainBC' 2>/dev/null || true
pkill -f 'sync_replay' 2>/dev/null || true
fi
$kill_trainer_cmds
pkill -f 'multi_instance' 2>/dev/null || true
pkill -f 'java.*java-aiplay' 2>/dev/null || true
pkill -f 'java.*MultiInstanceLauncher' 2>/dev/null || true
sleep 2
killall -9 ucc-bin-amd64 2>/dev/null || true
pkill -9 -f 'ucc-bin' 2>/dev/null || true
sleep 1
REMAINING=\$(pgrep -c -f 'ucc-bin' 2>/dev/null || echo 0)
if [ "\$REMAINING" -gt 0 ]; then
    pkill -9 -f 'ucc-bin' 2>/dev/null || true
fi
KILL_EOF
}

build_extras_label() {
    local extras=""
    [ "$KILL_SAC" = "true" ] && extras="$extras +SAC"
    echo "$extras"
}

if [ "$ALL" = "true" ]; then
    parse_servers_conf
    extras_label="$(build_extras_label)"
    for i in "${!SERVERS[@]}"; do
        log_step "Killing bots/BC trainers${extras_label} on ${SERVERS[$i]}..."
        kill_on_host "${SERVERS[$i]}" "${USERS[$i]}" "${PASSES[$i]}" &
    done
    wait || true
    log_ok "All servers cleaned"
elif [ -n "$HOST" ]; then
    log_step "Killing bots/BC trainers$(build_extras_label) on $HOST..."
    kill_on_host "$HOST" "$USER" "$PASS"
else
    echo "Usage: $0 --host HOST --user USER --pass PASS [--kill-sac] [--bots-only]"
    echo "       $0 --all [--kill-sac] [--bots-only]"
    exit 1
fi
