#!/bin/bash
# Overnight stable-training watch (Round-14 baseline, geen reward-tweaks). Volgt of captures NATUURLIJK
# groeien met training-tijd — de hypothese is dat carrier-home onder-getraind is (zeldzame carrier-states),
# niet onder-beloond. Per cyclus de BETROUWBARE signalen (de oude monitor's grab/cap-count was
# window-deflated): echte capture-events (raw flagCaptured cumulatief), carrier-homeward (measure-carry-home),
# defenderPresence, SAC crit, proc-liveness. 2070 ligt plat → uit PLAY.
#   Run: bash scripts/deploy/monitor-captures-overnight.sh [SAMPLES] [INTERVAL_SEC]
set -u
cd /home/kris/projects/ut99neuralnet
PASS=$(jq -r .ssh_password resources/config/secrets.local.json)
SSHO="-o StrictHostKeyChecking=no -o ConnectTimeout=8"
PLAY="desktop-4070 desktop-3070 LAPTOP-P15v"
SAMPLES=${1:-5}
INTERVAL=${2:-1800}

echo "=== overnight captures-watch ($SAMPLES × ${INTERVAL}s) — Round-14 baseline ==="
for i in $(seq 1 "$SAMPLES"); do
  TS=$(date '+%H:%M')
  # echte capture-events cumulatief: flagCaptured ≈ #caps (1 carrier-regel/event); flagEnemyCaptured = team-wide (~×5)
  CAP=0; ECAP=0
  for H in $PLAY; do
    read -r c e < <(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
      "c=\$(grep -hoP 'flagCaptured=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -vcE '=0\.0+\$'); \
       e=\$(grep -hoP 'flagEnemyCaptured=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -vcE '=0\.0+\$'); \
       echo \"\$c \$e\"" 2>/dev/null)
    CAP=$((CAP+${c:-0})); ECAP=$((ECAP+${e:-0}))
  done
  # defenderPresence mean (recent) — teken (>0 = thuis), magnitude niet over reward-changes vergelijkbaar
  DEF=$(for H in $PLAY; do sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
        "grep -hoP 'defenderPresence=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -oP '[-0-9.]+' | tail -300"; \
       done | awk '{s+=$1;n++} END{if(n)printf "%.2f",s/n; else printf "na"}')
  # proc-liveness (3 play-hosts ucc + 4090 SAC) + crit
  PROCS=0; for H in $PLAY desktop-4090; do
    c=$(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box "ps aux|grep -E 'ucc-bin|trainSAC'|grep -v grep|wc -l" 2>/dev/null)
    PROCS=$((PROCS+${c:-0})); done
  CRIT=$(sshpass -p "$PASS" ssh $SSHO kris@desktop-4090.fritz.box \
        "grep -oP 'step=[0-9]+ crit=[-0-9.]+' /home/kris/projects/ut99neuralnet-sessions/logs/rl_pawn_sac/trainer.log 2>/dev/null | tail -1" 2>/dev/null)
  # BETROUWBARE recent-carry-volume: hasFlag=1 frames in de staart (laatste 150k regels) van de recentste trace/host
  CF=0; for H in $PLAY; do
    f=$(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
      "F=\$(ls -t /home/kris/projects/ut99neuralnet-sessions/position-traces/pos_*.csv 2>/dev/null | head -1); \
       tail -150000 \"\$F\" 2>/dev/null | awk -F, '\$6==1' | wc -l" 2>/dev/null)
    CF=$((CF+${f:-0})); done
  echo "[$TS] caps=$CAP ecap=$ECAP carryFrames=$CF defP=$DEF procs=$PROCS | $CRIT"
  # carrier-homeward (de kern-metric: gaan carriers naar huis?)
  CH=$(timeout 120 bash scripts/deploy/measure-carry-home.sh 2>/dev/null | grep -E 'ALPHA|BETA')
  echo "$CH" | sed 's/^/       /'
  [ "$i" -lt "$SAMPLES" ] && sleep "$INTERVAL"
done
echo "=== overnight watch klaar ==="
