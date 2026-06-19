#!/usr/bin/env python3
"""Meet server-side degradatie binnen een map (los van de Java-klok).

UdpStateReceiver logt elke 600 server-frames: "UdpStateReceiver[PORT]: frames=N ...
players=X projectiles=Y". Twee signalen vs map-positie (restart-cluster aligned):

1. SERVER-TICK: 600 frames / dt tussen opeenvolgende logs per port = server-frames/sec.
   Daalt dit richting map-eind -> de UT99-server zelf vertraagt (ondanks platte Java-rate).
2. ACTOR-LOAD: projectiles= en players= per map-positie. Stijgende projectiles =
   server-side actor-accumulatie (de klassieke UT99-decay-oorzaak).
"""
import sys, re
from collections import defaultdict
from datetime import datetime

ts_re = re.compile(r'^([A-Z][a-z]{2} \d{2}, \d{4} \d{1,2}:\d{2}:\d{2} [AP]M)')
rx_re = re.compile(r'UdpStateReceiver\[(\d+)\]: frames=(\d+).*?players=(\d+) projectiles=(\d+)')
restart_re = re.compile(r'(Server switch level|ProcessServerTravel): \?Restart')

cur_ts = None
last_restart = None
last_rx = {}                       # port -> (ts, frames)
tick = defaultdict(list)           # map-bin(30s) -> [frames_per_sec]
proj = defaultdict(list)           # map-bin(30s) -> [projectiles]
plyr = defaultdict(list)           # map-bin(30s) -> [players]
n = 0

def mp(ts):
    return None if last_restart is None else (ts - last_restart).total_seconds()

for line in sys.stdin:
    m = ts_re.match(line)
    if m:
        cur_ts = datetime.strptime(m.group(1), "%b %d, %Y %I:%M:%S %p"); continue
    if restart_re.search(line) and cur_ts is not None:
        if last_restart is None or (cur_ts - last_restart).total_seconds() > 120:
            last_restart = cur_ts
        continue
    r = rx_re.search(line)
    if r and cur_ts is not None:
        n += 1
        port = r.group(1); frames = int(r.group(2))
        players = int(r.group(3)); projectiles = int(r.group(4))
        pos = mp(cur_ts)
        if pos is not None and 0 <= pos <= 900:
            b = int(pos // 30)
            proj[b].append(projectiles)
            plyr[b].append(players)
            if port in last_rx:
                pts, pf = last_rx[port]
                dt = (cur_ts - pts).total_seconds()
                df = frames - pf
                if 0 < dt < 45 and 0 < df < 5000:
                    tick[b].append(df / dt)
        last_rx[port] = (cur_ts, frames)

print(f"UdpStateReceiver samples: {n}")
print(f"\n{'map_min':>7} {'n':>5} {'tick/s':>7} {'proj_avg':>8} {'proj_p90':>8} {'proj_max':>8} {'players':>7}")
print("-"*60)
for b in sorted(set(list(tick)+list(proj))):
    t = tick.get(b, []); p = proj.get(b, []); pl = plyr.get(b, [])
    if len(p) < 10: continue
    tick_s = sum(t)/len(t) if t else 0
    ps = sorted(p)
    print(f"{b*30/60.0:>7.1f} {len(p):>5} {tick_s:>7.1f} {sum(p)/len(p):>8.1f} "
          f"{ps[min(len(ps)-1,int(0.9*len(ps)))]:>8} {max(p):>8} {sum(pl)/len(pl):>7.1f}")
