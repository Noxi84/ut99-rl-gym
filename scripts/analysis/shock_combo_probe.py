#!/usr/bin/env python3
"""Meet shock-combo-skill uit multi_instance.log (RL_REWARD_rl_pawn windows).

Leest multi_instance.log van stdin. Rapporteert over de laatste N windows
(default 4000, ~recent) per-window-gemiddelden + combo-rate. De combo-skill-
metrics zijn opponent-onafhankelijk (absolute skill), in tegenstelling tot
self-play frags die per constructie ~50/50 blijven.

Velden uit de RL_REWARD-breakdown:
  shockComboEvent = geslaagde combo (bal vanished + enemy in splash). Sinds
                    2026-06-02 Round 1 is event_bonus 2.0, dus combos = waarde/2.0.
                    We tellen NONZERO-windows (presence) bonus-agnostisch.
  shockComboAim   = dense beam-op-eigen-bal-nabij-enemy shaping (discovery-gradient).
  shotOnTarget/shotOnTargetAlt/damageDealt = combat-context.

Gebruik (vanaf dev):
  ssh <host> 'cat /tmp/ut99-multi/multi_instance.log' | python3 shock_combo_probe.py [N]
  of:  ... tail -c 8000000 /tmp/ut99-multi/multi_instance.log | python3 shock_combo_probe.py
"""
import sys, re
from collections import deque

N = int(sys.argv[1]) if len(sys.argv) > 1 else 4000

FIELDS = ["shockComboEvent", "shockComboAim", "shotOnTarget", "shotOnTargetAlt",
          "damageDealt", "frag", "viewAlignment", "objectiveProgress",
          "selfDamage", "damageTaken"]
pat = {f: re.compile(rf"\b{f}=(-?[0-9.]+)") for f in FIELDS}
win_re = re.compile(r"RL_REWARD_rl_pawn window=(\d+)")

rows = deque(maxlen=N)
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

def mean(f):
    return sum(r[f] for r in rows) / n

combo_present = sum(1 for r in rows if r["shockComboEvent"] != 0.0)
aim_present = sum(1 for r in rows if r["shockComboAim"] != 0.0)
# combos/window: bonus-agnostisch via afronding op event-grid (0.5 oud / 2.0 nieuw).
# We rapporteren rauwe som + presence-rate; combos≈som/bonus.
combo_sum = sum(r["shockComboEvent"] for r in rows)

print(f"windows={n} (laatste {N})")
print(f"  COMBO presence-rate : {100*combo_present/n:5.1f}%  ({combo_present}/{n} windows met combo)")
print(f"  COMBO sum/window    : {combo_sum/n:7.4f}   (combos≈som/event_bonus; 2.0 sinds R1)")
print(f"  COMBO-AIM presence  : {100*aim_present/n:5.1f}%  (dense discovery-gradient actief)")
print(f"  COMBO-AIM mean/win  : {mean('shockComboAim'):7.4f}")
print(f"  shotOnTarget  mean  : {mean('shotOnTarget'):7.3f}")
print(f"  shotOnTgtAlt  mean  : {mean('shotOnTargetAlt'):7.3f}")
print(f"  damageDealt   mean  : {mean('damageDealt'):7.3f}")
print(f"  frag          mean  : {mean('frag'):7.4f}")
print(f"  selfDamage    mean  : {mean('selfDamage'):7.4f}   (degeneratie-check: combo te dichtbij)")
print(f"  damageTaken   mean  : {mean('damageTaken'):7.3f}")
print(f"  viewAlignment mean  : {mean('viewAlignment'):7.2f}")
print(f"  objectiveProg mean  : {mean('objectiveProgress'):7.2f}")
