#!/usr/bin/env bash
# Compile and install the NeuralNetWebserver UnrealScript package on the DEV machine.
# The compiled .u is copied to scripts/mutator/utfiles/ for distribution to all servers.
#
# This ensures all servers and the dev machine have an identical .u file,
# preventing "version mismatch" errors when spectating headless servers.
#
# Usage:
#   bash scripts/deploy/compile-ucc.sh

set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

MUTATOR_DIR="${SCRIPTS_DIR}/mutator"
SRC_CLASSES_DIR="${MUTATOR_DIR}/NeuralNetWebserver/Classes"
FALLBACK_U="${MUTATOR_DIR}/utfiles/NeuralNetWebserver.u"
FALLBACK_INT="${MUTATOR_DIR}/utfiles/NeuralNetWebserver.int"
RUNTIME_JSON="$(cd "$(dirname "$0")/../.." && pwd)/resources/config/runtime.json"

# Read state push fps (no fallback — must be present in runtime.json)
STATE_PUSH_FPS=$(python3 -c "import json; print(json.load(open('$RUNTIME_JSON'))['state_push']['fps'])")
STATE_PUSH_INTERVAL=$(python3 -c "print(f'{1.0/${STATE_PUSH_FPS}:.5f}')")

UT99_ROOT="${HOME}/.local/share/OldUnreal/UnrealTournament"
UT99_SYSTEM64="${UT99_ROOT}/System64"
UT99_UCC="${UT99_SYSTEM64}/ucc-bin-amd64"
UT99_INI="${UT99_SYSTEM64}/UnrealTournament.ini"

PREF_SYSTEM="${HOME}/.utpg/System"
# ucc may write .u to System64 or ~/.utpg/System depending on UT99 config
PKG_U_PREF="${PREF_SYSTEM}/NeuralNetWebserver.u"
PKG_U_SYS64="${UT99_SYSTEM64}/NeuralNetWebserver.u"
PKG_INT="${PREF_SYSTEM}/NeuralNetWebserver.int"
BUILDINFO="${PREF_SYSTEM}/NeuralNetWebserver.buildinfo"

# --- helpers ---

sha256_dir() {
    { find "$1" -type f | sort | xargs sha256sum; echo "state_push_fps=${STATE_PUSH_FPS}"; } | sha256sum | cut -d' ' -f1
}

patch_state_push_interval() {
    local file="$1"
    [[ -f "$file" ]] || return 0
    sed -i "s/^\(\s*\)SendIntervalSec=.*/\1SendIntervalSec=${STATE_PUSH_INTERVAL}/" "$file"
}

ensure_edit_packages() {
    local ini="$1"
    [[ -f "$ini" ]] || return 0
    if grep -qi 'EditPackages=NeuralNetWebserver' "$ini"; then
        local botpack_line neural_line
        botpack_line=$(grep -n -i '^EditPackages=Botpack$' "$ini" | head -1 | cut -d: -f1)
        neural_line=$(grep -n -i '^EditPackages=NeuralNetWebserver$' "$ini" | head -1 | cut -d: -f1)
        if [[ -n "$botpack_line" && -n "$neural_line" && "$neural_line" -gt "$botpack_line" ]]; then
            return 0
        fi
        sed -i '/^EditPackages=NeuralNetWebserver$/Id' "$ini"
        log_step "Removed misplaced EditPackages=NeuralNetWebserver from $(basename "$ini")"
    fi
    local last_edit_line
    last_edit_line=$(grep -n '^EditPackages=' "$ini" | tail -1 | cut -d: -f1)
    if [[ -n "$last_edit_line" ]]; then
        sed -i "${last_edit_line}a EditPackages=NeuralNetWebserver" "$ini"
    elif grep -qi '\[Editor\.EditorEngine\]' "$ini"; then
        sed -i '/\[Editor\.EditorEngine\]/a EditPackages=NeuralNetWebserver' "$ini"
    else
        printf '\n[Editor.EditorEngine]\nEditPackages=NeuralNetWebserver\n' >> "$ini"
    fi
    log_step "Added EditPackages=NeuralNetWebserver to $(basename "$ini")"
}

configure_uweb_ini() {
    local ini="$1"
    [[ -f "$ini" ]] || return 0
    if ! grep -qi '\[UWeb\.WebServer\]' "$ini"; then
        cat >> "$ini" <<'SECTION'

[UWeb.WebServer]
bEnabled=True
DefaultApplication=0
Applications[0]=NeuralNetWebserver.NeuralNetWebserver
ApplicationPaths[0]=/utneuralnet
SECTION
        log_step "Added [UWeb.WebServer] section to $(basename "$ini")"
        return 0
    fi
    if ! grep -qi 'NeuralNetWebserver\.NeuralNetWebserver' "$ini"; then
        sed -i '/\[UWeb\.WebServer\]/a Applications[2]=NeuralNetWebserver.NeuralNetWebserver\nApplicationPaths[2]=/utneuralnet' "$ini"
        log_step "Added NeuralNetWebserver app to [UWeb.WebServer] in $(basename "$ini")"
    fi
    sed -i 's/^bEnabled=.*/bEnabled=True/I' "$ini"
}

# --- main ---

echo "=========================================="
echo "  COMPILE UCC (on dev machine)"
echo "=========================================="

# Check UT99 is installed on dev
if [[ ! -d "$UT99_ROOT" || ! -d "$UT99_SYSTEM64" ]]; then
    echo "  ERROR: UT99 not installed on dev at $UT99_ROOT"
    echo "  Run scripts/install-ut99.sh first."
    exit 1
fi

if [[ ! -x "$UT99_UCC" ]]; then
    echo "  ERROR: ucc-bin not found at $UT99_UCC"
    exit 1
fi

log_step "UT99 root:  ${UT99_ROOT}"
log_step "Sources:    ${SRC_CLASSES_DIR}"
log_step "State push: ${STATE_PUSH_FPS} Hz (SendIntervalSec=${STATE_PUSH_INTERVAL})"

# Helper to find compiled .u (could be in either location)
find_pkg_u() {
    if [[ -f "$PKG_U_SYS64" ]]; then echo "$PKG_U_SYS64"
    elif [[ -f "$PKG_U_PREF" ]]; then echo "$PKG_U_PREF"
    else echo ""
    fi
}

# 1) Check if already up-to-date
EXISTING_U=$(find_pkg_u)
if [[ -n "$EXISTING_U" && -f "$BUILDINFO" ]]; then
    current_hash=$(sha256_dir "$SRC_CLASSES_DIR")
    built_hash=$(cat "$BUILDINFO" 2>/dev/null || echo "")
    if [[ "$current_hash" == "$built_hash" ]]; then
        log_ok "Already up-to-date (hash=${current_hash:0:12}...)"
        # Still ensure fallback is current
        cp "$EXISTING_U" "$FALLBACK_U"
        exit 0
    fi
fi

# 2) Sync .uc sources to UT99 directories, patching SendIntervalSec per runtime.json
for dest in \
    "${UT99_ROOT}/NeuralNetWebserver/Classes" \
    "${UT99_SYSTEM64}/NeuralNetWebserver/Classes" \
    "${HOME}/.utpg/NeuralNetWebserver/Classes"; do
    mkdir -p "$dest"
    rm -f "$dest"/*.uc
    cp "${SRC_CLASSES_DIR}"/*.uc "$dest/"
    patch_state_push_interval "$dest/RLUdpStateSender.uc"
done
log_step "Synced .uc sources (state push ${STATE_PUSH_FPS} Hz)"

# 3) Ensure EditPackages in INI files
ensure_edit_packages "$UT99_INI"
[[ -f "${UT99_ROOT}/System/UnrealTournament.ini" ]] && \
    ensure_edit_packages "${UT99_ROOT}/System/UnrealTournament.ini"

# 4) Compile
mkdir -p "$PREF_SYSTEM"
rm -f "$PKG_U_PREF" "$PKG_U_SYS64"
log_step "Running ucc make..."
if (cd "$UT99_ROOT" && "$UT99_UCC" make Silent 2>&1 | tail -5); then
    COMPILED_U=$(find_pkg_u)
    if [[ -n "$COMPILED_U" ]]; then
        log_ok "ucc make OK → $COMPILED_U"
    else
        log_fail "ucc make produced no .u file"
        exit 1
    fi
else
    log_fail "ucc make failed"
    exit 1
fi

# 5) Install .int file
cp "$FALLBACK_INT" "$PKG_INT"

# 6) Configure UWeb.WebServer in INI
configure_uweb_ini "$UT99_INI"

# 7) Write buildinfo hash
sha256_dir "$SRC_CLASSES_DIR" > "$BUILDINFO"

# 8) Copy compiled .u to fallback location (used by sync-code.sh for distribution)
cp "$COMPILED_U" "$FALLBACK_U"
log_ok "Updated fallback .u at $FALLBACK_U"

# 9) Also install in both dev locations (for spectating)
mkdir -p "$PREF_SYSTEM"
cp "$COMPILED_U" "$PKG_U_PREF" 2>/dev/null || true
cp "$COMPILED_U" "$PKG_U_SYS64" 2>/dev/null || true
log_ok ".u installed in dev System64 + ~/.utpg/System"

log_ok "Done."
