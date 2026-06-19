#!/usr/bin/env python3
"""Meet of de bot-frame-rate degradeert binnen een 10-min map (map-aligned).

Leest multi_instance.log van stdin. Twee signalen:

1. FRAME-RATE vs MAP-POSITIE: RL_VRS_ACTION_RATE (elke 1000 action-frames per
   bot, met java.util.logging timestamp op de regel ervoor) is de frame-rate-sonde.
   Tijd tussen twee opeenvolgende logs voor dezelfde sid = duur van 1000 frames.
   Map-positie wordt afgeleid uit restart-clusters ("Server switch level/Process
   ServerTravel: ?Restart"): de 7 instances restarten geclusterd, dus het eerste
   restart-event na >120s rust markeert een nieuwe map. Bucket de frame-interval
   tegen seconden-sinds-restart -> stijging = simulatie vertraagt binnen de map.

2. GAP-HISTOGRAM: distributie van inter-arrival deltas, om grove freezes (grote
   gaps) los te zien van de gemiddelde rate.
"""
import sys, re
from collections import defaultdict
from datetime import datetime

ts_re = re.compile(r'^([A-Z][a-z]{2} \d{2}, \d{4} \d{1,2}:\d{2}:\d{2} [AP]M)')
vrs_re = re.compile(r'RL_VRS_ACTION_RATE sid=(\S+)')
restart_re = re.compile(r'(Server switch level|ProcessServerTravel): \?Restart')

cur_ts = None
last_vrs = {}                   # sid -> ts
last_restart_cluster = None     # ts van begin huidige map
buckets = defaultdict(list)     # map-positie-bin (30s) -> [delta_s,...]
gaphist = defaultdict(int)
n_events = 0
n_clusters = 0
first_ts = last_ts = None

for line in sys.stdin:
    m = ts_re.match(line)
    if m:
        cur_ts = datetime.strptime(m.group(1), "%b %d, %Y %I:%M:%S %p")
        if first_ts is None: first_ts = cur_ts
        last_ts = cur_ts
        continue
    if restart_re.search(line) and cur_ts is not None:
        if last_restart_cluster is None or (cur_ts - last_restart_cluster).total_seconds() > 120:
            last_restart_cluster = cur_ts
            n_clusters += 1
        continue
    v = vrs_re.search(line)
    if v and cur_ts is not None:
        n_events += 1
        sid = v.group(1)
        if sid in last_vrs:
            delta = (cur_ts - last_vrs[sid]).total_seconds()
            # gap-histogram (grove freezes)
            if   delta < 40:  gaphist['<40s (normaal)'] += 1
            elif delta < 60:  gaphist['40-60s'] += 1
            elif delta < 120: gaphist['60-120s'] += 1
            else:             gaphist['>120s (restart/freeze)'] += 1
            # frame-interval vs map-positie (alleen binnen-map samples)
            if delta < 45 and last_restart_cluster is not None:
                map_pos = (cur_ts - last_restart_cluster).total_seconds()
                if 0 <= map_pos <= 900:
                    buckets[int(map_pos // 30)].append(delta)
        last_vrs[sid] = cur_ts

def pct(a, p):
    s = sorted(a); return s[min(len(s)-1, int(p/100.0*len(s)))]

span = (last_ts - first_ts).total_seconds() if first_ts and last_ts else 0
print(f"log span: {span/3600:.1f}h   VRS events: {n_events}   restart-clusters(maps): {n_clusters}")
print("\n=== GAP-HISTOGRAM (inter-arrival per 1000 frames) ===")
for k in ['<40s (normaal)','40-60s','60-120s','>120s (restart/freeze)']:
    print(f"  {k:>24}: {gaphist.get(k,0)}")
print("\n=== FRAME-INTERVAL vs MAP-POSITIE (30s-bins) ===")
print(f"{'map_min':>7} {'n':>5} {'avg_s':>7} {'med_s':>7} {'p90_s':>7} {'p99_s':>7} {'max_s':>6}")
print("-"*52)
for b in sorted(buckets):
    a = buckets[b]
    if len(a) < 20: continue
    print(f"{b*30/60.0:>7.1f} {len(a):>5} {sum(a)/len(a):>7.2f} {pct(a,50):>7.2f} {pct(a,90):>7.2f} {pct(a,99):>7.2f} {max(a):>6.0f}")
