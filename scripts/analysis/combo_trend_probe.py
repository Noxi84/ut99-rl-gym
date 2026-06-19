#!/usr/bin/env python3
"""Chronologische trend van shock-combo-skill uit multi_instance.log.

Waar shock_combo_probe.py een recent-aggregaat geeft, bucket dit script de HELE
log chronologisch (default 6 buckets) zodat je *leer-trends* ziet i.p.v. een
momentopname. Bedoeld om proxy-farming te detecteren:

  farm-handtekening = aimMean/w STIJGT + altMean/w stijgt, maar combo_pres% VLAK
  en conversie (comboSum/altMean) DAALT. Een echt self-play-equilibrium houdt de
  conversie juist stabiel; een dalende conversie bij stijgende aim = de policy
  oogst de dense aim-reward zonder de combo te voltooien.

Velden uit de RL_REWARD_rl_pawn-breakdown: shockComboEvent (geslaagde combo),
shockComboAim (dense aim-shaping), shotOnTargetAlt (alt-fire = shockballs).

Gebruik (vanaf dev) — filter eerst op de host (de log is groot):
  ssh <host> 'grep -a RL_REWARD_rl_pawn /tmp/ut99-multi/multi_instance.log' \\
    | python3 scripts/analysis/combo_trend_probe.py [n_buckets]
"""
import sys, re

B = int(sys.argv[1]) if len(sys.argv) > 1 else 6

FIELDS = ["shockComboEvent", "shockComboAim", "shotOnTargetAlt"]
pat = {f: re.compile(rf"\b{f}=(-?[0-9.]+)") for f in FIELDS}

rows = []
for line in sys.stdin:
    if "RL_REWARD_rl_pawn" not in line:
        continue
    rec = {}
    ok = True
    for f, p in pat.items():
        m = p.search(line)
        if not m:
            ok = False
            break
        rec[f] = float(m.group(1))
    if ok:
        rows.append(rec)

n = len(rows)
if n == 0:
    print("Geen RL_REWARD_rl_pawn windows gevonden.")
    sys.exit(0)

per = n // B
print(f"totaal windows={n}, {B} buckets van ~{per} (bucket1=vroegste, bucket{B}=nu)")
hdr = f"{'bucket':7}{'combo_pres%':>12}{'comboSum/w':>12}{'aimMean/w':>11}{'altMean/w':>11}{'conv=combo/alt':>16}"
print(hdr)
for b in range(B):
    lo = b * per
    hi = (b + 1) * per if b < B - 1 else n
    seg = rows[lo:hi]
    c = len(seg)
    cp = sum(1 for r in seg if r["shockComboEvent"] != 0.0) / c * 100
    cs = sum(r["shockComboEvent"] for r in seg) / c
    am = sum(r["shockComboAim"] for r in seg) / c
    al = sum(r["shotOnTargetAlt"] for r in seg) / c
    conv = (cs / al) if al > 1e-9 else 0.0
    print(f"{b+1:<7}{cp:12.1f}{cs:12.4f}{am:11.4f}{al:11.3f}{conv:16.5f}")
