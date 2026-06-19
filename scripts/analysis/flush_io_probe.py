#!/usr/bin/env python3
"""Meet of de experience-flush I/O-latency (write_ms) oploopt binnen een map.

RL_FLUSH-regels bevatten write_ms = tijd om een NPZ-batch (512 transitions) naar de
replay-buffer te schrijven. Als experience-sync/I/O-druk accumuleert over een map,
zou write_ms stijgen richting map-eind en resetten bij restart. Map-positie wordt
afgeleid uit restart-clusters (zelfde methode als map_decay_probe).
"""
import sys, re
from collections import defaultdict
from datetime import datetime

ts_re = re.compile(r'^([A-Z][a-z]{2} \d{2}, \d{4} \d{1,2}:\d{2}:\d{2} [AP]M)')
flush_re = re.compile(r'RL_FLUSH batch=\d+ transitions=(\d+) policy_role=\d+ write_ms=(\d+)')
restart_re = re.compile(r'(Server switch level|ProcessServerTravel): \?Restart')

cur_ts = None
last_restart = None
buckets = defaultdict(list)     # map-positie-bin (60s) -> [write_ms,...]
allvals = []
hourly = defaultdict(list)      # uur-index -> [write_ms] (absolute-tijd trend)
first_ts = None

for line in sys.stdin:
    m = ts_re.match(line)
    if m:
        cur_ts = datetime.strptime(m.group(1), "%b %d, %Y %I:%M:%S %p")
        if first_ts is None: first_ts = cur_ts
        continue
    if restart_re.search(line) and cur_ts is not None:
        if last_restart is None or (cur_ts - last_restart).total_seconds() > 120:
            last_restart = cur_ts
        continue
    f = flush_re.search(line)
    if f and cur_ts is not None:
        wms = int(f.group(2))
        allvals.append(wms)
        hourly[int((cur_ts - first_ts).total_seconds() // 3600)].append(wms)
        if last_restart is not None:
            mp = (cur_ts - last_restart).total_seconds()
            if 0 <= mp <= 900:
                buckets[int(mp // 60)].append(wms)

def stats(a):
    s = sorted(a); n = len(s)
    return (sum(a)/n, s[n//2], s[min(n-1,int(0.9*n))], s[min(n-1,int(0.99*n))], max(a))

print(f"RL_FLUSH events: {len(allvals)}")
if allvals:
    avg, med, p90, p99, mx = stats(allvals)
    print(f"write_ms overall: avg={avg:.0f} med={med} p90={p90} p99={p99} max={mx}")
print("\n=== write_ms vs MAP-POSITIE (60s-bins) ===")
print(f"{'map_min':>7} {'n':>5} {'avg':>6} {'med':>5} {'p90':>5} {'p99':>6} {'max':>6}")
for b in sorted(buckets):
    a = buckets[b]
    if len(a) < 10: continue
    avg, med, p90, p99, mx = stats(a)
    print(f"{b:>7} {len(a):>5} {avg:>6.0f} {med:>5.0f} {p90:>5.0f} {p99:>6.0f} {mx:>6.0f}")
print("\n=== write_ms vs ABSOLUTE TIJD (per uur, ziet sync-bursts/drift) ===")
print(f"{'hour':>5} {'n':>5} {'avg':>6} {'p99':>6} {'max':>6}")
for h in sorted(hourly):
    a = hourly[h]
    avg, med, p90, p99, mx = stats(a)
    print(f"{h:>5} {len(a):>5} {avg:>6.0f} {p99:>6.0f} {mx:>6.0f}")
