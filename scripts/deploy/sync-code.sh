#!/bin/bash
# Sync code and pre-built JAR to a server.
# The JAR is built once on dev by deploy.sh — this script only rsyncs and configures.
#
# Usage:
#   bash scripts/deploy/sync-code.sh --host HOST --user USER --pass PASS

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

HOST="" USER="" PASS=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --user) USER="$2"; shift 2 ;;
        --pass) PASS="$2"; shift 2 ;;
        *) shift ;;
    esac
done

if [ -z "$HOST" ]; then
    echo "Usage: $0 --host HOST --user USER --pass PASS"
    exit 1
fi

# 1. Rsync project (including target/ with pre-built JAR from dev)
# baseline.json wordt door de SAC trainer geschreven bij elke PROMOTE
# (_persist_promoted_baselines). Zonder exclude wist rsync --delete die
# trainer-side updates terug naar de dev-versie en gaat de ratcheting
# baseline (combat ratchet 7.22 → 8.3 → 9.4 → ...) verloren bij elke deploy.
if ! rsync_to "$PASS" "$PROJECT_DIR/" "$HOST" "$USER" "$REMOTE_DIR/" \
    --delete \
    --exclude='.git/' --exclude='.idea/' --exclude='*.iml' \
    --exclude='.claude/' --exclude='.venv/' --exclude='node_modules/' --exclude='dependency-reduced-pom.xml' \
    --exclude='resources/models/rl_pawn/baseline.json' \
    >/dev/null 2>&1; then
    log_fail "$HOST — SYNC FAILED"
    exit 1
fi

# 2. Copy compiled .u to server (both ~/.utpg/System and System64 for spectating)
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "mkdir -p /home/kris/.utpg/System && \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/NeuralNetWebserver.u' /home/kris/.utpg/System/ && \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/NeuralNetWebserver.u' /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ 2>/dev/null; true" \
    || true

# 2b. Deploy SmartCTF mutator (.u + .int + .ini) — server-side scoring overlay
#     voor live-spec scoreboard (caps, returns, covers, seals, assists, defkills,
#     flak kills). bExtraStats=True in 4G geeft per-speler kolommen op overlay.
#     PRI.Score bonussen worden door RL rewards genegeerd via eigen counters
#     (frags, flagsCaptured) — zie CombatEventReward / FlagEventReward /
#     FlagCarrierKillReward. Oude 4F variant verwijderen om SmartCTF te forceren
#     terug op één versie (anders kan UT verwarren bij dual-package load).
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "rm -f /home/kris/.local/share/OldUnreal/UnrealTournament/System64/SmartCTF_4F_002.u \
           /home/kris/.local/share/OldUnreal/UnrealTournament/System64/SmartCTF_4F_002.int \
           /home/kris/.local/share/OldUnreal/UnrealTournament/System64/SmartCTF_4F.ini 2>/dev/null; \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/SmartCTF/SmartCTF_4G.u'   /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ && \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/SmartCTF/SmartCTF_4G.int' /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ && \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/SmartCTF/SmartCTF_4G.ini' /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ 2>/dev/null; true" \
    || true

# 2c. Deploy 2k4Combos mutator (.u only — class registration is in the package) —
#     UT2004-stijl Double/Multi/Mega/Ultra/Monster/Ludicrous/HolyShit kill broadcasts
#     via Combos (Mutator) + CombosSA (MessagingSpectator). Geen score-mutator, dus
#     geen invloed op PRI.Score of RL-rewards.
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "cp '$REMOTE_DIR/scripts/mutator/utfiles/2k4Combos/2k4Combos.u' /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ 2>/dev/null; true" \
    || true

# 2d. Deploy EnhancedFeedback mutator (.u + .int) — "you took the lead" /
#     "you lost the lead" / "N frags left" announcer-feedback voor live-spec.
#     v0.8 disabled frags-left & lead-change events in team-games (CTF), dus in
#     onze CTF setup geeft hij vooral kill/death-context. Geen score-mutator.
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "cp '$REMOTE_DIR/scripts/mutator/utfiles/EnhancedFeedback/EnhancedFeedback.u'   /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ && \
     cp '$REMOTE_DIR/scripts/mutator/utfiles/EnhancedFeedback/EnhancedFeedback.int' /home/kris/.local/share/OldUnreal/UnrealTournament/System64/ 2>/dev/null; true" \
    || true

# 3. Clean old .uc source dirs and buildinfo on server (legacy from compile-ucc.sh running per server)
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "rm -rf /home/kris/.local/share/OldUnreal/UnrealTournament/NeuralNetWebserver \
            /home/kris/.local/share/OldUnreal/UnrealTournament/System64/NeuralNetWebserver \
            /home/kris/.utpg/NeuralNetWebserver \
            /home/kris/.utpg/System/NeuralNetWebserver.buildinfo 2>/dev/null; true" \
    || true

# 4. Deploy INI files
local_ini_src="$REMOTE_DIR/scripts/mutator/utfiles/ini-recorder"
local_ini_dst="/home/kris/.local/share/OldUnreal/UnrealTournament/System64"
ssh_cmd_quiet "$HOST" "$USER" "$PASS" \
    "mkdir -p '$local_ini_dst' && cp '$local_ini_src/UnrealTournament.ini' '$local_ini_dst/' && cp '$local_ini_src/User.ini' '$local_ini_dst/'" \
    || true

log_ok "$HOST — SYNC OK"
