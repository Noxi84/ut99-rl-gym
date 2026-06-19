#!/usr/bin/env bash
# Leading-indicator voor movement-adaptatie: objectiveProgress + selfDamage trend uit de
# RL_REWARD-breakdown-logs op een play-machine. Logs zijn schoon sinds de laatste deploy →
# log-volgorde = chronologie, dus eerste-kwartiel vs laatste-kwartiel = adaptatie-trend.
#
#   objectiveProgress → positief = policy volgt de (geodesische) route richting objective
#   selfDamage minder negatief    = minder vallen
#
# Usage: bash scripts/deploy/measure-reward-trend.sh [HOST]   (default desktop-4070.fritz.box)
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"
HOST="${1:-desktop-4070.fritz.box}"
PASS=$(jq -r .ssh_password "$PROJECT_DIR/resources/config/secrets.local.json")

sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8 "kris@$HOST" \
  "grep -hoP 'objectiveProgress=[-0-9.]+|selfDamage=[-0-9.]+' /tmp/ut99-multi/*.log 2>/dev/null | paste - -" \
| python3 -c "
import sys
op=[]; sd=[]
for ln in sys.stdin:
    for p in ln.split():
        if p.startswith('objectiveProgress='): op.append(float(p.split('=')[1]))
        elif p.startswith('selfDamage='): sd.append(float(p.split('=')[1]))
def avg(a): return sum(a)/len(a) if a else float('nan')
n=len(op)
print(f'=== reward-trend op $HOST ({n} windows sinds deploy) ===')
if n < 8:
    print('te weinig windows'); sys.exit(0)
q=n//4
print(f'objectiveProgress  Q1(vroeg)={avg(op[:q]):7.1f}  Q4(laat)={avg(op[-q:]):7.1f}  Δ={avg(op[-q:])-avg(op[:q]):+7.1f}  (→positief = route-following)')
print(f'selfDamage(vallen) Q1(vroeg)={avg(sd[:q]):7.1f}  Q4(laat)={avg(sd[-q:]):7.1f}  Δ={avg(sd[-q:])-avg(sd[:q]):+7.1f}  (→0 = minder vallen)')
print(f'objectiveProgress totaal-gemiddelde={avg(op):.1f}')
"
