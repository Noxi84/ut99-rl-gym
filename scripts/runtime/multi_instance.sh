#!/bin/bash
# Launcher for N parallel headless UT99 bot instances.
# Each instance gets its own game port and webservice port.
# All instances share the same session/model/replay-buffer directories.
#
# Configuration is read from resources/config/servers.json by matching the current hostname.
#
# Usage: ./scripts/runtime/multi_instance.sh [NUM_INSTANCES_OVERRIDE]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$(dirname "$SCRIPTS_DIR")"
JAR="$BASE_DIR/java-aiplay/target/java-aiplay-1.0.jar"
LOG_DIR=/tmp/ut99-multi

source "$SCRIPTS_DIR/deploy/common.sh"

# --- Read config from shared server inventory by matching hostname ---

parse_servers_conf
MY_HOSTNAME=$(hostname)
MY_SHORT="${MY_HOSTNAME%%.*}"

FOUND_INDEX=""
for i in "${!SERVERS[@]}"; do
    conf_host="${SERVERS[$i]}"
    conf_short="${conf_host%%.*}"
    if [[ "${MY_HOSTNAME,,}" == "${conf_host,,}" || "${MY_SHORT,,}" == "${conf_short,,}" ]]; then
        FOUND_INDEX="$i"
        break
    fi
done

if [ -z "$FOUND_INDEX" ]; then
    echo "ERROR: hostname '$MY_HOSTNAME' not found in $SERVERS_JSON"
    exit 1
fi

machine_id="${MACHINE_IDS[$FOUND_INDEX]}"
instances="${INSTANCES_RAW[$FOUND_INDEX]}"
cuda="${CUDAS[$FOUND_INDEX]}"
display_base="${DISPLAY_BASES[$FOUND_INDEX]}"
web_port_base="${WEB_PORT_BASES[$FOUND_INDEX]}"
game_port_base="${GAME_PORT_BASES[$FOUND_INDEX]}"
game_port_step="${GAME_PORT_STEPS[$FOUND_INDEX]}"
udp_port_base="${UDP_PORT_BASES[$FOUND_INDEX]}"
state_udp_port_base="${STATE_UDP_PORT_BASES[$FOUND_INDEX]}"
game_speed="${GAME_SPEEDS[$FOUND_INDEX]}"
gamestyle="${GAME_STYLES[$FOUND_INDEX]}"
extra_env_json="${EXTRA_ENV_JSONS[$FOUND_INDEX]:-{}}"

# Parse instances: either plain number (e.g. "20") or gpu/cpu split (e.g. "5/15")
RAW_INSTANCES=${1:-$instances}
if [[ "$RAW_INSTANCES" == */* ]]; then
    GPU_INSTANCES="${RAW_INSTANCES%%/*}"
    CPU_INSTANCES="${RAW_INSTANCES##*/}"
    NUM_INSTANCES=$(( GPU_INSTANCES + CPU_INSTANCES ))
else
    NUM_INSTANCES=$RAW_INSTANCES
    if [ "$cuda" = "true" ]; then
        GPU_INSTANCES=$NUM_INSTANCES
        CPU_INSTANCES=0
    else
        GPU_INSTANCES=0
        CPU_INSTANCES=$NUM_INSTANCES
    fi
fi
GAME_PORT_BASE=$game_port_base
GAME_PORT_STEP=$game_port_step
WEB_PORT_BASE=$web_port_base
UDP_PORT_BASE=$udp_port_base
STATE_UDP_PORT_BASE=$state_udp_port_base

echo "[$(date)] Config: machine=$machine_id instances=$NUM_INSTANCES (gpu=$GPU_INSTANCES cpu=$CPU_INSTANCES) cuda=$cuda web=$WEB_PORT_BASE udp=$UDP_PORT_BASE stateUdp=$STATE_UDP_PORT_BASE game=$GAME_PORT_BASE step=$GAME_PORT_STEP speed=${game_speed}x gamestyle=${gamestyle}"

# --- Auto-detect JAVA_HOME ---

if ! command -v java &>/dev/null; then
    for candidate in /opt/java/jdk-* /usr/lib/jvm/temurin-*-jdk-amd64; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Set JAVA_HOME or install JDK."
    exit 1
fi

# --- Environment ---

export UT99_SESSIONS_DIR=/home/kris/projects/ut99neuralnet-sessions
export UT99_PROJECT_ROOT=$BASE_DIR
export UT99_MACHINE_ID=$machine_id
# Mode switch (CAPTURE vs NORMAL) — set by start-bots.sh via tmux env. Default
# false: bots write live .npz experience for SAC training. When true:
# bots skip live .npz and dump .rec.gz raw recordings into the central
# recordings_dir for offline replay.
export UT99_RAW_RECORDING="${UT99_RAW_RECORDING:-false}"
if [ -z "${game_speed:-}" ]; then
    echo "ERROR: game_speed not set in server inventory for $MY_HOSTNAME"
    exit 1
fi
export UT99_GAME_SPEED=$game_speed
export UT99_GAME_STYLE=${gamestyle:-classic}

# NVIDIA/CUDA libs for GPU ONNX inference (enabled via capacity.cuda_enabled)
if [ "$cuda" = "true" ]; then
    NVIDIA_LIBS="$BASE_DIR/.venv/lib/python3.12/site-packages/nvidia"
    if [ -d "$NVIDIA_LIBS" ]; then
        CUDA_LIB_PATH=""
        for d in "$NVIDIA_LIBS"/*/lib; do
            [ -d "$d" ] && CUDA_LIB_PATH="$d:$CUDA_LIB_PATH"
        done
        export LD_LIBRARY_PATH="${CUDA_LIB_PATH}${LD_LIBRARY_PATH:-}"
    else
        echo "WARNING: cuda=true but NVIDIA libs not found at $NVIDIA_LIBS"
    fi
fi

# Extra env vars from server inventory (e.g. GALLIUM_DRIVER=llvmpipe)
if [ -n "${extra_env_json:-}" ] && [[ "$extra_env_json" == *:* ]]; then
    while IFS='=' read -r key value; do
        [ -n "$key" ] && export "$key=$value"
    done < <(python3 -c "
import json, sys
for key, value in json.loads(sys.argv[1]).items():
    print(f'{key}={value}')
" "$extra_env_json")
fi

# --- Pre-flight checks ---

if [ ! -f "$JAR" ]; then
    echo "ERROR: JAR not found at $JAR. Run: ./mvnw package -DskipTests"
    exit 1
fi

mkdir -p "$LOG_DIR"

# --- Kernel TCP tuning for high-frequency localhost HTTP ---
# UWeb sends Connection:Close on every response, causing TIME_WAIT socket buildup.
# tcp_tw_reuse allows kernel to reuse TIME_WAIT sockets for new connections to the same dest.
if [ -w /proc/sys/net/ipv4/tcp_tw_reuse ]; then
    echo 1 > /proc/sys/net/ipv4/tcp_tw_reuse
    echo "[$(date)] tcp_tw_reuse enabled"
fi

# --- Cleanup handler ---

PIDS=()
SYNC_REPLAY_PID=""
SLEEP_PID=""
_shutdown_requested=false

cleanup() {
    _shutdown_requested=true
    echo "[$(date)] Shutting down all instances..."
    if [ -n "$SLEEP_PID" ]; then
        kill "$SLEEP_PID" 2>/dev/null || true
    fi
    if [ -n "$SYNC_REPLAY_PID" ]; then
        kill "$SYNC_REPLAY_PID" 2>/dev/null || true
    fi
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    killall -9 ucc-bin-amd64 2>/dev/null || true
    echo "[$(date)] Cleanup complete."
    exit 0
}

trap cleanup SIGINT SIGTERM

# --- Read heap + GC config from runtime.json (no fallbacks: missing key → crash) ---
RUNTIME_JSON="$BASE_DIR/resources/config/runtime.json"
read -r HEAP_BASE HEAP_PER_INSTANCE GC_NAME INIT_HEAP_FRACTION < <(python3 -c "
import json
with open('$RUNTIME_JSON') as f:
    cfg = json.load(f)['jvm']
print(cfg['heap_base_mb'], cfg['heap_per_instance_mb'], cfg['gc'], cfg['initial_heap_fraction'])
")

HEAP_MB=$(( HEAP_BASE + NUM_INSTANCES * HEAP_PER_INSTANCE ))

# Per-machine heap ceiling (servers.json capacity.jvm_heap_max_mb). The instance
# formula above is tuned on the 4070 (64GB); on RAM-tight machines it overshoots.
# Generational ZGC backs its heap with shared memory and multi-maps it, so the JVM's
# physical footprint (anon-rss + shmem-rss) is ~2x -Xmx. Uncapped, the bot-JVM on the
# 3070 (5 instances -> 16GB -Xmx, ~28-30GB RSS) is killed by the kernel OOM-killer on
# its 32GB RAM. No fallback: a missing key crashes the bot-start.
HEAP_MAX_MB=$(python3 -c "
import json
with open('$BASE_DIR/resources/config/servers.json') as f:
    machines = json.load(f)['machines']
m = next(x for x in machines if x['machine_id'] == '$machine_id')
print(m['capacity']['jvm_heap_max_mb'])
")
if (( HEAP_MB > HEAP_MAX_MB )); then
    echo "[$(date)] Heap formula ${HEAP_MB}m exceeds ${machine_id} ceiling ${HEAP_MAX_MB}m -- capping (ZGC RSS ~2x Xmx; kernel-OOM guard)"
    HEAP_MB=$HEAP_MAX_MB
fi
INIT_HEAP_MB=$(python3 -c "print(int($HEAP_MB * $INIT_HEAP_FRACTION))")

# All bots for a machine run in ONE JVM, so a single GC pause freezes every bot at
# once. Generational ZGC (Java 25) keeps pauses sub-ms and fully concurrent; G1's
# young-GC pauses were measured at ~24ms every ~3.8s on 4070, which at the 50Hz
# command loop drops >1 command cycle and shows as a visible stutter across all bots.
case "$GC_NAME" in
    zgc) GC_FLAGS=(-XX:+UseZGC) ;;
    g1)  GC_FLAGS=(-XX:+UseG1GC -XX:MaxGCPauseMillis=10) ;;
    *)   echo "ERROR: unknown jvm.gc '$GC_NAME' in runtime.json (expected: zgc | g1)"; exit 1 ;;
esac

# Check if any AI bot is active (active=true) — only sync experience for active bots
HAS_ACTIVE_RL_BOT=$(python3 -c "
import json, sys
with open('$BASE_DIR/resources/config/gameplay.json') as f:
    ai_bots = json.load(f).get('ai_bots', [])
print('true' if any(b.get('active') for b in ai_bots) else 'false')
")

# --- Start bots (JVM + sync processes) ---
start_bots() {
    echo "[$(date)] Starting $NUM_INSTANCES headless UT99 instances ($machine_id)"
    echo "[$(date)] Launching MultiInstanceLauncher (1 JVM, ${NUM_INSTANCES} bots, gc=${GC_NAME}, heap=${INIT_HEAP_MB}m..${HEAP_MB}m)"

    java \
        "${GC_FLAGS[@]}" \
        -Xmx${HEAP_MB}m -Xms${INIT_HEAP_MB}m \
        -DUT99_NOSOUND=true \
        -DUT99_HEADLESS=true \
        -cp "$JAR" aiplay.MultiInstanceLauncher \
        --instances=$NUM_INSTANCES \
        --gpu-instances=$GPU_INSTANCES \
        --display-base=$display_base \
        --web-port-base=$WEB_PORT_BASE \
        --game-port-base=$GAME_PORT_BASE \
        --game-port-step=$GAME_PORT_STEP \
        --udp-port-base=$UDP_PORT_BASE \
        --state-udp-port-base=$STATE_UDP_PORT_BASE \
        >> "$LOG_DIR/multi_instance.log" 2>&1 &
    PIDS+=($!)
    echo "[$(date)] JVM PID=${PIDS[-1]}"

    # Start replay buffer sync to trainer (only when active RL bots produce experience)
    if [ "$HAS_ACTIVE_RL_BOT" = "true" ] && [ -x "$SCRIPT_DIR/sync_replay.sh" ]; then
        bash "$SCRIPT_DIR/sync_replay.sh" >> "$LOG_DIR/sync_replay.log" 2>&1 &
        SYNC_REPLAY_PID=$!
        echo "[$(date)] sync_replay.sh started (PID $SYNC_REPLAY_PID)"
    fi
}

# --- Main loop ---
start_bots
echo "[$(date)] All $NUM_INSTANCES instances starting. Logs in $LOG_DIR/"

# UT99 itself cycles matches via DeathMatchPlus.RestartGame() → Level.ServerTravel("?Restart")
# after every EndGame (TimeLimit or score-cap). The end-screen pause + map reload + MinPlayers
# wait costs ~90s per match-grens (empirically observed 2026-05-18) — happens inside the
# running ucc process, no JVM/proces kill needed. We keep the JVM up and let UT99 do
# match-cycling indefinitely. Gate-eval runs on the trainer host, triggered by MATCH_ENDED
# log-tag count (see MatchEndLogger.java + matches_per_eval_cycle in export_gate.json).
echo "[$(date)] Match-cycling via UT99 ServerTravel; JVM stays up until shutdown."
echo "[$(date)] Press Ctrl+C to stop all instances."
wait
