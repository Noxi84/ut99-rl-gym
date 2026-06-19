#!/bin/bash
# Source-only helper. Reads resources/config/deploy.json via load_deploy_config.py
# and exports bash variables / arrays consumed by deploy.sh.
#
# Exports:
#   DEPLOY_HOSTS=(...)                # filter list, empty = all servers
#   DEPLOY_RESTART_BOTS=true|false
#   DEPLOY_CLEAN_LOGS=true|false
#   DEPLOY_EXTRACT_MAP_BOUNDS=true|false
#   DEPLOY_RECORDINGS_SYNC=true|false  # global capture+sync mode
#   MODELS_TRAIN_BC=(...)
#   MODELS_TRAIN_SAC=(...)
#   MODELS_PREPARE_CSV=(...)
#   MODELS_KEEP_EXISTING=(...)
#   MODELS_CLEAN_EXPERIENCE=(...)
#   MODELS_RESET_SAC_BASELINE=(...)
#   MODELS_RESET_SAC_TO_BC_BASELINE=(...)
#   MODELS_REPLAY_EXPORT=(...)
#
# Implicit rules applied by load_deploy_config.py:
#   reset-sac-baseline: true        ⇒  train-sac: true
#   reset-sac-to-bc-baseline: true  ⇒  train-sac: true   AND  clean-experience: true
#   reset-current-to-last-champion: true ⇒ train-sac: true AND clean-experience: true
#                                          (mutually exclusive with reset-sac-baseline,
#                                           reset-sac-to-bc-baseline, reset-champions, train-bc)
#   reset-sac-baseline + reset-sac-to-bc-baseline both true ⇒ ERROR (mutually exclusive)
#   replay-export: true             ⇒  requires clean-experience=true AND
#                                       (reset-sac-baseline OR reset-sac-to-bc-baseline)
#
#   reset-sac-to-bc-baseline ⇒ clean-experience rationale:
#     A hard reset wipes the actor checkpoint, but the on-disk batch_*.npz that bots
#     wrote with the OLD rewards-config (before the deploy stop) are independent. At
#     trainer restart they get gretig ingested into the fresh in-memory ReplayBuffer
#     before any post-restart bot has written anything — so the BC-baseline actor
#     immediately trains on a critic fit to the old rewards, re-learning the failure
#     mode the hard reset was supposed to escape. clean-experience=true forces the
#     pending NPZ stash to be wiped before the trainer comes back up.
#
# Validation failures cause exit 1 with an error printed to stderr.

LOAD_DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_DEPLOY_PROJECT_DIR="$(cd "$LOAD_DEPLOY_DIR/../.." && pwd)"

DEPLOY_JSON="${DEPLOY_JSON:-$LOAD_DEPLOY_PROJECT_DIR/resources/config/deploy.json}"
DEPLOY_INDEX_JSON="$LOAD_DEPLOY_PROJECT_DIR/resources/models/index.json"
DEPLOY_LOADER_PY="$LOAD_DEPLOY_DIR/load_deploy_config.py"

DEPLOY_HOSTS=()
DEPLOY_RESTART_BOTS="true"
DEPLOY_CLEAN_LOGS="true"
DEPLOY_EXTRACT_MAP_BOUNDS="false"
DEPLOY_RECORDINGS_SYNC="false"
MODELS_TRAIN_BC=()
MODELS_TRAIN_SAC=()
MODELS_PREPARE_CSV=()
MODELS_KEEP_EXISTING=()
MODELS_CLEAN_EXPERIENCE=()
MODELS_RESET_SAC_BASELINE=()
MODELS_RESET_SAC_TO_BC_BASELINE=()
MODELS_RESET_CURRENT_TO_CHAMPION=()
MODELS_RESET_CHAMPIONS=()
MODELS_CONVERT_FROM_JSONS=()
MODELS_REPLAY_EXPORT=()

load_deploy_config() {
    if [ ! -f "$DEPLOY_JSON" ]; then
        echo "ERROR: $DEPLOY_JSON not found" >&2
        exit 1
    fi
    if [ ! -f "$DEPLOY_LOADER_PY" ]; then
        echo "ERROR: $DEPLOY_LOADER_PY not found" >&2
        exit 1
    fi

    local script_output
    script_output=$(python3 "$DEPLOY_LOADER_PY" "$DEPLOY_JSON" "$DEPLOY_INDEX_JSON")
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        exit "$rc"
    fi
    eval "$script_output"
}

# Returns 0 if value (arg 1) is in any of the remaining args.
deploy_list_contains() {
    local needle="$1"
    shift
    local item
    for item in "$@"; do
        [ "$item" = "$needle" ] && return 0
    done
    return 1
}

load_deploy_config
