#!/usr/bin/env bash
# Bouw het geodesische afstandsveld voor een map uit gameplay-data en schrijf het naar
# resources/config/geodesic/<map>.geodesic.json (NIET in maps/ — alles daar wordt als per-map
# config gevalideerd met verplichte map_id). Draait op de dev-machine (vereist de gebouwde JAR
# en lokale recordings — sync eerst met scripts/deploy/sync-recordings.sh als de data op de
# play-machines staat).
#
# Het veld voedt ObjectiveProgressReward met afstanden langs de beloopbare ruimte (zie
# docs/rewards/geodesic-distance-field.md). Activeren: "geodesic_field": true in de per-map
# JSON + resources/config/ syncen naar de play-machines.
#
# Modes:
#   (geen args)       — actieve map uit gameplay.json's mapName
#   <MAP_NAME ...>    — elke genoemde map expliciet
#
# Vóór de build worden de position-traces (PositionTraceLogger, normale play) van alle
# servers uit servers.json naar $SESSIONS_DIR/position-traces/from-<host>/ gersync't,
# zodat het veld self-bootstrapping is: gespeelde minuten = dekking. Skip met --no-pull.
#
# Extra flags worden doorgegeven aan BuildGeodesicFieldMain (--voxel-uu, --min-transitions,
# --json-dir, --raw-dir, --positions-dir, --dry-run).
#
# Usage:
#   bash scripts/deploy/build-geodesic-field.sh
#   bash scripts/deploy/build-geodesic-field.sh CTF-Face
#   bash scripts/deploy/build-geodesic-field.sh CTF-Face --min-transitions 2 --dry-run
#   bash scripts/deploy/build-geodesic-field.sh CTF-Face --no-pull

set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

GAMEPLAY_JSON="$PROJECT_DIR/resources/config/gameplay.json"
JAR="$PROJECT_DIR/java-aiplay/target/java-aiplay-1.0.jar"

if [ ! -f "$JAR" ]; then
    log_fail "JAR not found at $JAR (run ./mvnw package -DskipTests)"
    exit 1
fi

MAPS=()
PASSTHROUGH=()
DO_PULL=1
for arg in "$@"; do
    if [ "$arg" = "--no-pull" ]; then
        DO_PULL=0
    elif [[ "$arg" == --* ]] || [ ${#PASSTHROUGH[@]} -gt 0 ]; then
        PASSTHROUGH+=("$arg")
    else
        MAPS+=("$arg")
    fi
done

pull_position_traces() {
    parse_servers_conf
    local dest_root="$SESSIONS_DIR/position-traces"
    mkdir -p "$dest_root"
    local i
    for i in "${!SERVERS[@]}"; do
        local host="${SERVERS[$i]}" user="${USERS[$i]}" pass="${PASSES[$i]}"
        local dest="$dest_root/from-${host}"
        mkdir -p "$dest"
        # Remote dir bestaat pas na de eerste run met position_trace.enabled — stil overslaan.
        if sshpass -p "$pass" rsync -az --timeout=20 -e "ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8" \
                "${user}@${host}:$SESSIONS_DIR/position-traces/" "$dest/" 2>/dev/null; then
            log_step "[pull] $host → $(find "$dest" -name 'pos_*.csv' | wc -l) trace file(s)"
        else
            log_step "[pull] $host: geen position-traces (dir ontbreekt of host offline) — overgeslagen"
        fi
    done
}

if [ "$DO_PULL" -eq 1 ]; then
    pull_position_traces
fi

if [ ${#MAPS[@]} -eq 0 ]; then
    MAP_NAME=$(python3 -c "
import json
with open('$GAMEPLAY_JSON') as f:
    raw = json.load(f)['mapName']
print(raw.split('?')[0].strip())
")
    MAPS+=("$MAP_NAME")
fi

FAIL=0
for m in "${MAPS[@]}"; do
    log_step "[$m] Building geodesic field..."
    if ! (cd "$PROJECT_DIR" && java -cp "$JAR" aiplay.BuildGeodesicFieldMain "$m" ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}); then
        log_fail "[$m] build failed"
        FAIL=$((FAIL + 1))
    fi
done

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
log_ok "Processed ${#MAPS[@]} map(s)."
