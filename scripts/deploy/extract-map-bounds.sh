#!/usr/bin/env bash
# Extract map bounds, symmetry flag, and spawn points from UT99 .unr file(s) and write them
# to per-map JSON files under resources/config/maps/. Runs on the dev machine (requires UT99 +
# ucc-bin + the built JAR).
#
# Modes:
#   (no args)         — process the active map from gameplay.json's mapName
#   <MAP_NAME ...>    — process each named map explicitly
#   --all             — process every existing resources/config/maps/<map>.json file
#   --discover        — scan json-recording-sessions/, extract unique MapInfo.MapName from
#                       the first frame of every ZIP/folder, process each discovered map
#
# For each map:
#   1) Run `ucc batchexport <map>.unr level t3d <tmp>` to produce MyLevel.t3d.
#   2) Run aiplay.ExtractMapBoundsMain to parse T3D and write resources/config/maps/<map>.json.
#      Existing fields (edge/k tweaks, hand-curated spawn points) are left intact.
#
# Usage:
#   bash scripts/deploy/extract-map-bounds.sh
#   bash scripts/deploy/extract-map-bounds.sh CTF-andACTION CTF-Command
#   bash scripts/deploy/extract-map-bounds.sh --all
#   bash scripts/deploy/extract-map-bounds.sh --discover

set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

GAMEPLAY_JSON="$PROJECT_DIR/resources/config/gameplay.json"
MAPS_DIR="$PROJECT_DIR/resources/config/maps"
JAR="$PROJECT_DIR/java-aiplay/target/java-aiplay-1.0.jar"

UT99_ROOT="${HOME}/.local/share/OldUnreal/UnrealTournament"
UT99_UCC="${UT99_ROOT}/System64/ucc-bin-amd64"

if [ ! -x "$UT99_UCC" ]; then
    log_fail "ucc-bin not found at $UT99_UCC (run scripts/install-ut99.sh)"
    exit 1
fi

if [ ! -f "$JAR" ]; then
    log_fail "JAR not found at $JAR (run ./mvnw package -DskipTests)"
    exit 1
fi

# --- Helpers ---

# Resolve a requested map name to the actual filename casing in Maps/ (case-insensitive).
# Echoes the canonical name (without .unr) on success; returns non-zero if no match.
resolve_map_name() {
    local requested="$1"
    local maps_dir="$UT99_ROOT/Maps"
    if [ -f "$maps_dir/${requested}.unr" ]; then
        echo "$requested"
        return 0
    fi
    local found
    found=$(find "$maps_dir" -maxdepth 1 -type f -iname "${requested}.unr" 2>/dev/null | head -n1)
    if [ -n "$found" ]; then
        basename "$found" .unr
        return 0
    fi
    return 1
}

list_configured_maps() {
    [ -d "$MAPS_DIR" ] || return 0
    find "$MAPS_DIR" -maxdepth 1 -type f -name "*.json" -printf '%f\n' \
        | sed 's/\.json$//' | sort
}

# --- Resolve list of maps to process ---

MAPS=()
if [ $# -eq 0 ]; then
    MAP_NAME=$(python3 -c "
import json
with open('$GAMEPLAY_JSON') as f:
    raw = json.load(f)['mapName']
print(raw.split('?')[0].strip())
")
    MAPS+=("$MAP_NAME")
elif [ "$1" = "--all" ]; then
    while IFS= read -r m; do
        [ -n "$m" ] && MAPS+=("$m")
    done < <(list_configured_maps)
    if [ ${#MAPS[@]} -eq 0 ]; then
        log_fail "--all: no resources/config/maps/*.json files found"
        exit 1
    fi
elif [ "$1" = "--discover" ]; then
    while IFS= read -r m; do
        [ -n "$m" ] && MAPS+=("$m")
    done < <(java -cp "$JAR" aiplay.DiscoverMapsInRecordingsMain 2>/dev/null)
    if [ ${#MAPS[@]} -gt 0 ]; then
        log_step "Discovered maps from recordings: ${MAPS[*]}"
    fi
    # Always union in maps already present under resources/config/maps/ — they may need bounds/spawns
    # filled in even when no recordings exist for them yet (e.g. fresh map stub).
    CONFIGURED=()
    while IFS= read -r m; do
        [ -n "$m" ] && CONFIGURED+=("$m")
    done < <(list_configured_maps)
    if [ ${#CONFIGURED[@]} -gt 0 ]; then
        log_step "Maps configured in resources/config/maps/: ${CONFIGURED[*]}"
        MAPS+=("${CONFIGURED[@]}")
    fi
    if [ ${#MAPS[@]} -eq 0 ]; then
        # Nothing from recordings or resources/config/maps/ — fall back to the active map. Keeps
        # deploy.sh safe on fresh/empty setups.
        MAP_NAME=$(python3 -c "
import json
with open('$GAMEPLAY_JSON') as f:
    raw = json.load(f)['mapName']
print(raw.split('?')[0].strip())
")
        log_step "--discover: no recordings or configured maps, falling back to active map: $MAP_NAME"
        MAPS+=("$MAP_NAME")
    fi
else
    for arg in "$@"; do
        [ -n "$arg" ] && MAPS+=("$arg")
    done
fi

# --- Resolve to canonical filename casing + dedupe ---

declare -A SEEN_RESOLVED
RESOLVED_MAPS=()
RESOLVE_FAIL_COUNT=0
for m in "${MAPS[@]}"; do
    if canonical=$(resolve_map_name "$m"); then
        if [ -z "${SEEN_RESOLVED[$canonical]:-}" ]; then
            SEEN_RESOLVED[$canonical]=1
            RESOLVED_MAPS+=("$canonical")
            if [ "$canonical" != "$m" ]; then
                log_step "Resolved $m → $canonical (case-insensitive match)"
            fi
        fi
    else
        log_fail "Skipping $m: no .unr file matches (case-insensitive) in $UT99_ROOT/Maps/"
        RESOLVE_FAIL_COUNT=$((RESOLVE_FAIL_COUNT + 1))
    fi
done
MAPS=("${RESOLVED_MAPS[@]}")

# --- Process each map ---

process_one() {
    local map_name="$1"
    local unr_file="$UT99_ROOT/Maps/${map_name}.unr"
    if [ ! -f "$unr_file" ]; then
        log_fail "Skipping $map_name: map file not found at $unr_file"
        return 1
    fi

    local t3d_dir
    t3d_dir=$(mktemp -d -t "ut99-bounds-${map_name}-XXXX")
    # shellcheck disable=SC2064
    trap "rm -rf '$t3d_dir'" RETURN

    log_step "[$map_name] Exporting T3D..."
    if ! (cd "$UT99_ROOT" && "$UT99_UCC" batchexport "${map_name}.unr" level t3d "$t3d_dir" 2>&1 | tail -2); then
        log_fail "[$map_name] ucc batchexport failed"
        return 1
    fi

    local t3d_file="$t3d_dir/MyLevel.t3d"
    if [ ! -f "$t3d_file" ]; then
        log_fail "[$map_name] no MyLevel.t3d produced in $t3d_dir"
        return 1
    fi

    log_step "[$map_name] Extracting bounds..."
    java -cp "$JAR" aiplay.ExtractMapBoundsMain "$t3d_file" "$map_name" "$MAPS_DIR"
}

FAIL_COUNT=$RESOLVE_FAIL_COUNT
TOTAL=$(( ${#MAPS[@]} + RESOLVE_FAIL_COUNT ))
for m in "${MAPS[@]}"; do
    if ! process_one "$m"; then
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
done

if [ "$FAIL_COUNT" -gt 0 ]; then
    log_fail "$FAIL_COUNT of $TOTAL map(s) failed"
    exit 1
fi
log_ok "Processed ${#MAPS[@]} map(s)."
