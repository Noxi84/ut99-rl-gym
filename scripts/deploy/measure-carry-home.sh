#!/bin/bash
# Carry-home diagnostiek: parseert position-traces (kolommen ts,sid,x,y,z,hasFlag,team) en isoleert
# carry-segmenten (consecutieve hasFlag=1 per bot = van grab tot drop/death). Beantwoordt: sterft de
# carrier INSTANT bij enemy-base (korte duur + eind-X bij grab-punt) of ONDERWEG (langere duur + eind-X
# richting huis)? Dit bepaalt survival-fix vs reward-fix.
#   ALPHA: enemy-flag X~-1013, home X~6488 (carry = X stijgt).  BETA: enemy X~6488, home X~-1013 (X daalt).
# Run vanaf dev: bash scripts/deploy/measure-carry-home.sh
set -u
cd /home/kris/projects/ut99neuralnet
PASS=$(jq -r .ssh_password resources/config/secrets.local.json)
SSHO="-o StrictHostKeyChecking=no -o ConnectTimeout=8"
TRACEDIR=/home/kris/projects/ut99neuralnet-sessions/position-traces
TMP=/tmp/carry-traces; rm -rf "$TMP"; mkdir -p "$TMP"

for H in desktop-4070 desktop-3070 desktop-2070 LAPTOP-P15v; do
  # pak de 2 meest recente trace-files per host
  FILES=$(sshpass -p "$PASS" ssh $SSHO kris@$H.fritz.box "ls -t $TRACEDIR/pos_*.csv 2>/dev/null | head -2")
  for F in $FILES; do
    B=$(basename "$F")
    sshpass -p "$PASS" scp $SSHO "kris@$H.fritz.box:$F" "$TMP/${H}_${B}" 2>/dev/null
  done
done
echo "trace-files opgehaald: $(ls "$TMP" 2>/dev/null | wc -l)"

python3 - "$TMP" <<'PY'
import sys, os, glob, collections
tmp = sys.argv[1]
MID = 2737.0
# per-team geometrie: home-X-richting + drempels
# team bepaald via [ALPHA]/[BETA] in sessionId
def team_of(sid):
    if '[ALPHA]' in sid: return 'ALPHA'
    if '[BETA]'  in sid: return 'BETA'
    return '?'
# carry-segmenten per (file,sid)
segs = collections.defaultdict(list)  # team -> list of (durMs, startX, endX, nframes)
rows = collections.defaultdict(list)  # (file,sid) -> [(ts,x,hasFlag)]
for path in glob.glob(os.path.join(tmp,'*')):
    for ln in open(path, errors='ignore'):
        ln=ln.strip()
        if not ln or ln.startswith('#'): continue
        p=ln.split(',')
        if len(p) < 7: continue
        try:
            ts=int(p[0]); x=float(p[2]); hf=int(p[5])
        except: continue
        rows[(path,p[1])].append((ts,x,hf))
for (path,sid),seq in rows.items():
    seq.sort()
    t=team_of(sid)
    cur=None
    for ts,x,hf in seq:
        if hf==1:
            if cur is None: cur=[ts,x,ts,x,1]      # startTs,startX,endTs,endX,n
            else: cur[2]=ts; cur[3]=x; cur[4]+=1
        else:
            if cur is not None:
                segs[t].append((cur[2]-cur[0], cur[1], cur[3], cur[4])); cur=None
    if cur is not None:  # track eindigde tijdens carry (= death carrying)
        segs[t].append((cur[2]-cur[0], cur[1], cur[3], cur[4]))

for t in ('ALPHA','BETA'):
    S=segs.get(t,[])
    if not S:
        print(f"{t}: geen carry-segmenten"); continue
    n=len(S)
    durs=sorted(d for d,_,_,_ in S)
    med_dur=durs[n//2]
    avg_start=sum(s for _,s,_,_ in S)/n
    avg_end=sum(e for _,_,e,_ in S)/n
    # homeward progress + waar eindigt de carry (death/drop locatie)
    if t=='ALPHA':   # home hoog (6488), enemy laag (-1013); progress = end-start
        prog=[e-s for _,s,e,_ in S]
        reached_home=sum(1 for _,_,e,_ in S if e>5000)
        died_enemy=sum(1 for _,_,e,_ in S if e<MID)     # eindigt nog in enemy-helft (waar gegrabd)
    else:            # BETA home laag (-1013), enemy hoog (6488); progress = start-end
        prog=[s-e for _,s,e,_ in S]
        reached_home=sum(1 for _,_,e,_ in S if e<500)
        died_enemy=sum(1 for _,_,e,_ in S if e>MID)
    avg_prog=sum(prog)/n
    print(f"{t}: carries={n}  med_duur={med_dur}ms  start-X~{avg_start:.0f} eind-X~{avg_end:.0f}  "
          f"homeward~{avg_prog:.0f}UU  reach-home={100*reached_home/n:.0f}%  eindigt-in-enemy-helft={100*died_enemy/n:.0f}%")
PY
