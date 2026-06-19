#!/bin/bash
# Push dev's local from-dev/ recordings (human gameplay, converted to .rec.gz)
# to the recording_server. Servers in CAPTURE-mode push their own from-servers/
# content via sync_replay.sh; this script only handles the dev side.
#
# Run by deploy.sh when recordings_sync.enabled=true. Idempotent — silently
# does nothing if dev has no from-dev/ recordings yet.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$(dirname "$SCRIPTS_DIR")"

source "$SCRIPT_DIR/common.sh"
parse_servers_conf
find_recording_server || exit 1

read_files_path() {
    python3 -c "
import json
with open('$BASE_DIR/resources/config/files.json') as f:
    print(json.load(f).get('$1', ''), end='')
"
}

RECORDINGS_DIR=$(read_files_path "recordings_dir")
if [ -z "$RECORDINGS_DIR" ]; then
    echo "ERROR: files.json missing 'recordings_dir'" >&2
    exit 1
fi

LOCAL_FROM_DEV="$RECORDINGS_DIR/from-dev"
REMOTE_FROM_DEV="${RECORDING_USER}@${RECORDING_HOST}:${RECORDINGS_DIR}/from-dev/"

if [ ! -d "$LOCAL_FROM_DEV" ] || [ -z "$(find "$LOCAL_FROM_DEV" -type f -name '*.rec.gz' 2>/dev/null | head -1)" ]; then
    echo "[sync-recordings] No human recordings under $LOCAL_FROM_DEV — nothing to push."
    exit 0
fi

echo "[sync-recordings] Pushing dev's from-dev/ → ${RECORDING_USER}@${RECORDING_HOST}:${RECORDINGS_DIR}/from-dev/"

# Ensure remote directory exists
sshpass -p "$RECORDING_PASS" ssh -o StrictHostKeyChecking=no \
    "${RECORDING_USER}@${RECORDING_HOST}" \
    "mkdir -p '${RECORDINGS_DIR}/from-dev'"

# Move (not copy) so dev's local buffer drains. Recursive: per-model subdirs
# are preserved as-is.
rsync -az --remove-source-files --include='*/' --include='*.rec.gz' --exclude='*' \
    -e "sshpass -p $RECORDING_PASS ssh -o StrictHostKeyChecking=no" \
    "$LOCAL_FROM_DEV/" "$REMOTE_FROM_DEV"

# Clean up empty subdirectories left by --remove-source-files.
find "$LOCAL_FROM_DEV" -mindepth 1 -type d -empty -delete 2>/dev/null || true

echo "[sync-recordings] Done."
