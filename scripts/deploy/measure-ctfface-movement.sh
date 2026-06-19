#!/usr/bin/env bash
# Meet CTF-Face movement-gezondheid uit position-traces (PositionTraceLogger).
# Pull't verse traces van alle play-machines en rapporteert per team:
#   * void-fractie (Z<-2800 = onder de map / gevallen) — journal-baseline 06-13 = 23.3%
#   * X-spread + enemy-base-reach (BETA=team1 valt aan naar hoog-X ~6488; ALPHA=team0 naar laag-X ~-1013)
#   * freeze-detectie (bots met X-spread < 500 = staan stil)
#   * objectiveProgress is NIET hier (zit in reward-logs) — dit is puur positie-gedrag.
#
# Usage:  bash scripts/deploy/measure-ctfface-movement.sh [WINDOW_MIN]   (default 45)
#         bash scripts/deploy/measure-ctfface-movement.sh 30 --no-pull
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

WINDOW_MIN=45
DO_PULL=1
for a in "$@"; do
    if [ "$a" = "--no-pull" ]; then DO_PULL=0
    elif [[ "$a" =~ ^[0-9]+$ ]]; then WINDOW_MIN="$a"; fi
done

if [ "$DO_PULL" -eq 1 ]; then
    parse_servers_conf
    dest_root="$SESSIONS_DIR/position-traces"
    mkdir -p "$dest_root"
    for i in "${!SERVERS[@]}"; do
        host="${SERVERS[$i]}"; user="${USERS[$i]}"; pass="${PASSES[$i]}"
        dest="$dest_root/from-${host}"; mkdir -p "$dest"
        sshpass -p "$pass" rsync -az --timeout=20 -e "ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8" \
            "${user}@${host}:$SESSIONS_DIR/position-traces/" "$dest/" 2>/dev/null \
            && echo "[pull] $host ok" || echo "[pull] $host overgeslagen"
    done
fi

python3 - "$SESSIONS_DIR/position-traces" "$WINDOW_MIN" <<'PY'
import sys, os, glob, time, statistics
root, win_min = sys.argv[1], float(sys.argv[2])
mtime_cut = time.time() - (win_min*60 + 1800)   # skip stale files (ruime marge t.o.v. line-filter)
files = [f for f in glob.glob(os.path.join(root, "**", "pos_*.csv"), recursive=True)
         if os.path.getmtime(f) >= mtime_cut]

# Eerst alle (sid,x,z,t) inlezen, dan filteren op line-tMillis relatief aan de MAX timestamp
# (robuust tegen cross-machine klok-skew): venster = [max_t - win, max_t].
rows = []   # (tMillis, sid, x, z)
for fp in files:
    try: lines = open(fp, errors="replace").read().splitlines()
    except OSError: continue
    on = False
    for ln in lines:
        if ln.startswith("# map="):
            on = ln.strip().endswith("CTF-Face"); continue
        if not on or not ln or ln[0] == "#": continue
        p = ln.split(",")
        if len(p) < 5: continue
        try: tms, x, z = float(p[0]), float(p[2]), float(p[4])
        except ValueError: continue
        rows.append((tms, p[1], x, z))
print(f"=== CTF-Face movement (laatste {win_min:.0f} min, {len(files)} trace-files, {len(rows)} ruwe samples) ===")
if not rows:
    print("GEEN traces — bots nog niet (her)gestart of geen position_trace? Probeer grotere WINDOW.")
    sys.exit(0)
max_t = max(r[0] for r in rows)
lo_t = max_t - win_min*60*1000
rows = [r for r in rows if r[0] >= lo_t]

team_z = {"ALPHA": [], "BETA": [], "?": []}
team_x = {"ALPHA": [], "BETA": [], "?": []}
bot_x = {}
bot_z = {}
def team_of(sid):
    if "[ALPHA]" in sid: return "ALPHA"
    if "[BETA]" in sid:  return "BETA"
    return "?"
for tms, sid, x, z in rows:
    t = team_of(sid)
    team_z[t].append(z); team_x[t].append(x)
    bot_x.setdefault(sid, []).append(x)
    bot_z.setdefault(sid, []).append(z)

def pct(v, q):
    v = sorted(v); return v[min(len(v)-1, int(q/100*len(v)))] if v else float("nan")
def frac(v, pred): return sum(1 for e in v if pred(e))/len(v) if v else float("nan")

tot_z = team_z["ALPHA"]+team_z["BETA"]+team_z["?"]
print(f"samples: {len(tot_z)}  | VOID Z<-2800: {100*frac(tot_z, lambda z:z<-2800):.1f}%  "
      f"Z<-2250: {100*frac(tot_z, lambda z:z<-2250):.1f}%")
for t in ("ALPHA","BETA"):
    zs, xs = team_z[t], team_x[t]
    if not zs: continue
    void = 100*frac(zs, lambda z:z<-2800)
    # enemy-base: BETA naar hoog-X (~6488), ALPHA naar laag-X (~-1013)
    if t=="BETA": reach = 100*frac(xs, lambda x:x>5000); rdesc=f"X>5000 (enemy@6488)"; ext=max(xs)
    else:         reach = 100*frac(xs, lambda x:x<500);  rdesc=f"X<500 (enemy@-1013)"; ext=min(xs)
    print(f"  {t}: n={len(zs):6d} void={void:4.1f}%  X p5/p50/p95={pct(xs,5):.0f}/{pct(xs,50):.0f}/{pct(xs,95):.0f}  "
          f"reach[{rdesc}]={reach:4.1f}%  extreme={ext:.0f}")

# freeze: bots met kleine X-spread; onderscheid ECHTE freeze (op walkable) van dood-in-void
real_freeze, dead_void = [], 0
for sid, v in bot_x.items():
    if len(v) <= 20: continue
    if max(v) - min(v) >= 500: continue
    zs = bot_z[sid]; meanz = sum(zs)/len(zs)
    if meanz < -2400: dead_void += 1          # dood/gevallen, logt death-spot
    else: real_freeze.append(sid)             # spread-0 op walkable = echte freeze
nbots = sum(1 for v in bot_x.values() if len(v)>20)
print(f"bots: {nbots} | ECHTE FREEZE (spread<500, walkable): {len(real_freeze)} | dood-in-void(spread<500): {dead_void}",
      [sid.split(']')[-1] for sid in real_freeze[:8]])
PY
