#!/bin/bash
# Flag-first rebalance monitor: sampelt objectiveProgress-trend (recent window), flag-grab-count,
# SAC crit/act. Eindig: $SAMPLES samples met $INTERVAL sec ertussen, dan exit (roept Claude terug).
# Doel: kantelt de bot na de flag-first rebalance naar de flag-run? objProg ↑ (van −85 richting 0/+),
# grabs ↑.  Run: bash scripts/deploy/monitor-flagfirst.sh  (typisch via run_in_background).
set -u
cd /home/kris/projects/ut99neuralnet
PASS=$(jq -r .ssh_password resources/config/secrets.local.json)
SSHO="-o StrictHostKeyChecking=no -o ConnectTimeout=8 -o BatchMode=no"
PLAY="desktop-4070 desktop-3070 desktop-2070 LAPTOP-P15v"
SAMPLES=${1:-4}
INTERVAL=${2:-720}

echo "=== flag-first monitor start ($SAMPLES samples, ${INTERVAL}s interval) ==="
for i in $(seq 1 "$SAMPLES"); do
  TS=$(date '+%H:%M')
  # objectiveProgress: gemiddelde over de RECENTE ~400 episodes per machine (trend, niet bulk-sinds-restart)
  OP=$(for H in $PLAY; do
        sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
          "grep -hoP 'objectiveProgress=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -oP '[-0-9.]+' | tail -400" 2>/dev/null
       done | awk '{s+=$1;n++} END{if(n)printf "%.1f(n=%d)",s/n,n; else printf "na"}')
  # defenderPresence: mean over recente window per machine — proxy of defenders thuisblijven (>0 = eigen helft)
  DEF=$(for H in $PLAY; do
        sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
          "grep -hoP 'defenderPresence=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -oP '[-0-9.]+' | tail -400" 2>/dev/null
       done | awk '{s+=$1;n++} END{if(n)printf "%.2f",s/n; else printf "na"}')
  # flag-events cumulatief sinds restart (non-zero): grabs (halen), captures (thuisbrengen), drops (verlies)
  GR=0; CAP=0; DROP=0
  for H in $PLAY; do
        read -r g c d < <(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box \
            "g=\$(grep -hoP 'flagTaken=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -vcE '=0\.0+\$'); \
             c=\$(grep -hoP 'flagCaptured=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -vcE '=0\.0+\$'); \
             d=\$(grep -hoP 'flagDropped=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | grep -vcE '=0\.0+\$'); \
             echo \"\$g \$c \$d\"" 2>/dev/null)
        GR=$((GR+${g:-0})); CAP=$((CAP+${c:-0})); DROP=$((DROP+${d:-0}))
       done
  # proc-liveness (bekende crashmodes: virtual-thread OOM, ZGC heap-cap OOM, 2070 power-cap)
  PROCS=0; for H in $PLAY desktop-4090; do
        c=$(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box "ps aux|grep -E 'ucc-bin|trainSAC|train.*sac'|grep -v grep|wc -l" 2>/dev/null)
        PROCS=$((PROCS+${c:-0}))
       done
  # SAC stap/crit/act
  SAC=$(sshpass -p "$PASS" ssh $SSHO kris@desktop-4090.fritz.box \
        "grep -oP 'step=[0-9]+ crit=[-0-9.]+ act=[-0-9.]+' /home/kris/projects/ut99neuralnet-sessions/logs/rl_pawn_sac/trainer.log 2>/dev/null | tail -1" 2>/dev/null)
  CONV=$(awk "BEGIN{if($GR>0)printf \"%.0f%%\",100*$CAP/$GR; else printf \"na\"}")
  echo "[$TS] objProg=$OP defP=$DEF | grabs=$GR caps=$CAP conv=$CONV drops=$DROP | procs=$PROCS | $SAC"
  [ "$i" -lt "$SAMPLES" ] && sleep "$INTERVAL"
done
echo "=== flag-first monitor klaar ==="
