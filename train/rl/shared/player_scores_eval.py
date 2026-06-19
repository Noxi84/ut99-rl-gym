"""
Win-rate eval: parse PLAYER_SCORES log lines from all bot servers and compute
the average per-minute KPI delta between our RL bots and UT99 native bots over
a rolling time window.

Used by DeltaGate to gate per-model exports — promote when our RL policy
outperforms the UT99 baseline on the KPI of choice for that model.

KPI's per DeltaGate-instance (`compute_delta(window_minutes, kpi=...)`):
    "score":                 legacy — full UT99 score (kills + caps + returns + bonuses)
    "frags":                 pure kills (suicides excluded), excludes cap noise
    "flag_score":            movement — 1·taken + 7·captured + 3·returned weighted sum
    "aim_accuracy":          viewrotation legacy — ratio shots-on-target / shots
    "shots_on_target_rate":  viewrotation — shots-on-target per minuut (rate ipv
                             ratio: bot wordt niet beloond voor niet-vuren).
    "combat_score":          shooting — frags + (damageDealt - 0.3·damageTaken)/80, in
                             kill-equivalent units. Beloont damage zonder kill (deelnemen
                             aan combat) en straft passief-damage-eat strategie.

Rate-based KPI's (score, frags, flag_score, shots_on_target_rate, combat_score)
report per-minute gain. The ratio-based KPI (aim_accuracy) reports a fraction in [0, 1].

Log format (emitted by aiplay/runtime/PlayerScoresLogger.java, post Plan A):
    PLAYER_SCORES t=<unix_ms> self=<name>:<team>:<score>:<deaths>:<frags>:<flagsT>:<flagsC>:<flagsR>:<shots>:<shotsOn>:<dmgDealt>:<dmgTaken>:<rl>:<roleCode>:<weapon>
                                tm0=... tm1=... en0=... en1=... en2=...

Legacy 5-field format (`name:team:score:deaths:rl`) is still parsed for
backward-compat — counters default to 0, so `frags`/`flag_score`/`aim_accuracy`
will return 0 for sessions running an older .u file. Het 15e veld (weapon)
is optioneel; defaults naar "none" wanneer afwezig, gebruikt door
`compute_delta_per_weapon` voor per-weapon KPI attribution.

Each RL bot logs all 6 players in its match every 60 seconds. Multiple bots
in the same match log redundantly — we dedupe per (match_id, time_bucket).
"""

from __future__ import annotations

import re
import subprocess
import time
from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple

from train.common.ServerInventory import load_servers


SESSIONS_DIR = "/home/kris/projects/ut99neuralnet-sessions"
LOGS_DIR = f"{SESSIONS_DIR}/logs"


# Match vijf format-generaties (newest first):
#   15-field (per-weapon):         name:team:score:deaths:frags:fT:fC:fR:shots:sOn:dmgDealt:dmgTaken:rl:roleCode:weapon
#   14-field (champion-aware):     name:team:score:deaths:frags:fT:fC:fR:shots:sOn:dmgDealt:dmgTaken:rl:roleCode
#   13-field (Plan A+B+D2+damage): name:team:score:deaths:frags:fT:fC:fR:shots:sOn:dmgDealt:dmgTaken:rl
#   11-field (Plan A+B+D2):        name:team:score:deaths:frags:fT:fC:fR:shots:sOn:rl
#   5-field  (legacy):             name:team:score:deaths:rl
# De groepen 6-11 (frags/flag/shots) zijn optioneel; groepen 12-13 (dmg) zijn
# een eigen optionele tail; groep 15 (roleCode) en groep 16 (weapon) zijn
# beide eigen optionele tails. Hierdoor kan een trainer-host transient een
# mix van oude en nieuwe log-lines verwerken zonder crash.
_PLAYER_RE = re.compile(
    r'(self|tm\d|en\d)='
    r'([^:]+):(-?\d+):(-?\d+):(-?\d+)'              # name:team:score:deaths
    r'(?::(-?\d+):(-?\d+):(-?\d+):(-?\d+):(-?\d+):(-?\d+))?'  # frags:fT:fC:fR:shots:sOn
    r'(?::(-?\d+):(-?\d+))?'                        # dmgDealt:dmgTaken (newer)
    r':(\d)'                                        # rl flag
    r'(?::(\d+))?'                                  # roleCode (newest, 0 default)
    r'(?::([A-Za-z0-9._]+))?'                       # weapon class (newest, "none" when absent)
)
_TIMESTAMP_RE = re.compile(r'PLAYER_SCORES t=(\d+)')
_MATCH_ENDED_RE = re.compile(r'MATCH_ENDED t=(\d+)')


# Available KPI modes — passed to compute_delta(kpi=...) and to DualKPIDeltaGateConfig.
KPI_SCORE = "score"
KPI_FRAGS = "frags"
KPI_FLAG_SCORE = "flag_score"
KPI_AIM_ACCURACY = "aim_accuracy"
KPI_SHOTS_ON_TARGET_RATE = "shots_on_target_rate"
KPI_COMBAT_SCORE = "combat_score"

KPI_RATE_BASED = {KPI_SCORE, KPI_FRAGS, KPI_FLAG_SCORE,
                  KPI_SHOTS_ON_TARGET_RATE, KPI_COMBAT_SCORE}
KPI_RATIO_BASED = {KPI_AIM_ACCURACY}
KPI_ALL = KPI_RATE_BASED | KPI_RATIO_BASED

# combat_score weegt damage als fractie van een "average kill" (80 HP) en
# straft incoming damage met factor 0.3. Resultaat in dezelfde unit als frags
# (kill-equivalents per minuut). Een 80HP-hit telt als 1 unit; een full kill
# telt voor 1 frag + 1 unit damage = 2 units. Damage_taken trekt af zodat
# passieve damage-eat policies niet trivially scoren.
COMBAT_SCORE_HP_PER_KILL = 80.0
COMBAT_SCORE_TAKEN_PENALTY = 0.3

# Movement flag-event KPI weights — analoog aan UT99 stock CTF score-bonussen
# (cap +7, return +3-5 mod-afhankelijk, taken +1). Geeft een gewogen rate die
# specifiek movement's verantwoordelijkheid (flag-handling) capteert zonder
# kill/shooting-gerelateerde noise.
FLAG_SCORE_W_TAKEN = 1.0
FLAG_SCORE_W_CAPTURED = 7.0
FLAG_SCORE_W_RETURNED = 3.0


@dataclass(frozen=True)
class PlayerSnapshot:
    """One bot's per-match counters at a moment in time."""
    name: str
    team: int
    score: int
    deaths: int
    frags: int
    flags_taken: int
    flags_captured: int
    flags_returned: int
    shots: int
    shots_on_target: int
    damage_dealt: int
    damage_taken: int
    is_rl: bool
    # Current runtime uses bit 3 for full-joint rl_pawn champion.
    # Older bit positions may still appear in historical logs. 0 = all-current
    # or UT99 stock. Defaults to 0 for legacy logs without the field. DeltaGate
    # uses (policy_role_mask != 0) to exclude any champion-running bot from
    # rl_avg vs ut99_avg.
    policy_role_mask: int = 0
    # Canonical weapon key at snapshot-emit moment ("PulseGun", "Eightball",
    # "ShockRifle", …, or "none" wanneer player geen wapen droeg). Defaults
    # naar "none" voor legacy logs zonder het 15e veld. Gebruikt door
    # compute_delta_per_weapon voor per-weapon attribution: een segment-delta
    # tussen prev/curr snapshots wordt toegerekend aan curr.weapon. Voor
    # 60s-emit-cadence is dit een redelijke approximatie zolang het wapen
    # >30s actief blijft.
    weapon: str = "none"

def _canonical_weapon(raw: Optional[str]) -> str:
    """Normaliseer raw weaponClass tot canonical key voor per-weapon attribution.

    Strips package prefixen ("Botpack.", "NeuralNetWebserver.") en het
    "RL"-prefix van runtime-overrides, plus de "UT_"-naming convention.
    Resultaat: stabiel wapen-key zoals "PulseGun", "Eightball", "ShockRifle",
    "FlakCannon", onafhankelijk van of de bot een stock of RL-override class
    droeg. Onbekend/leeg → "none" (apart bucket; DeltaGate skipt die voor
    promotion-evaluation).
    """
    if not raw or raw == "none":
        return "none"
    s = raw
    if s.startswith("Botpack."):
        s = s[len("Botpack."):]
    elif s.startswith("NeuralNetWebserver."):
        s = s[len("NeuralNetWebserver."):]
        if s.startswith("RL"):
            s = s[len("RL"):]
    if s.startswith("UT_"):
        s = s[len("UT_"):]
    return s or "none"


# Wire-format bit positions. Java currently emits only the joint bit; legacy
@dataclass
class DeltaResult:
    """Result of a single eval call. Units depend on the KPI mode:
       - rate-based KPI's: rl_avg_gain in units-per-minute
       - ratio-based KPI: rl_avg_gain in [0, 1]
       The DeltaGate uses these as opaque numbers — promote/rollback margins
       are configured per KPI by the caller."""
    delta: float            # rl_avg_gain - ut99_avg_gain
    rl_avg_gain: float
    ut99_avg_gain: float
    rl_n: int               # number of RL bot observations
    ut99_n: int             # number of UT99 bot observations
    minutes_observed: float # actual time span covered
    matches: int            # unique instance dirs sampled
    kpi: str = KPI_SCORE    # which metric was computed

    def is_significant(self, min_observations: int = 20) -> bool:
        return self.rl_n >= min_observations and self.ut99_n >= min_observations

    def __str__(self) -> str:
        return (f"kpi={self.kpi} delta={self.delta:+.3f}"
                f" rl_avg={self.rl_avg_gain:+.3f}"
                f" ut99_avg={self.ut99_avg_gain:+.3f}"
                f" n_rl={self.rl_n} n_ut99={self.ut99_n}"
                f" matches={self.matches} minutes={self.minutes_observed:.1f}")


def load_server_hosts() -> List[Tuple[str, str, str]]:
    # SSH password is resolved centrally (env var / secrets.local.json),
    # never read from servers.json directly. See ServerInventory.
    return [(s["machine_id"], s["hostname"], s["password"]) for s in load_servers()]


def _ssh_exec(host: str, password: str, cmd: str, timeout_s: int = 30) -> str:
    try:
        result = subprocess.run(
            [
                "sshpass", "-p", password,
                "ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=5",
                f"kris@{host}", cmd,
            ],
            capture_output=True,
            text=True,
            timeout=timeout_s,
        )
        return result.stdout
    except subprocess.TimeoutExpired:
        return ""
    except Exception:
        return ""


def _parse_line(line: str) -> Optional[Tuple[int, List[PlayerSnapshot]]]:
    """Parse one PLAYER_SCORES line. Tolerates 5/11/13/14/15-field per-player
    tuples — counters, policy_role en weapon default to safe values when absent."""
    m = _TIMESTAMP_RE.search(line)
    if not m:
        return None
    ts_ms = int(m.group(1))
    snapshots = []
    for match in _PLAYER_RE.finditer(line):
        # Group indices:
        #  1=role, 2=name, 3=team, 4=score, 5=deaths
        #  6..11 optional (frags, flagsT, flagsC, flagsR, shots, shotsOn)
        #  12..13 optional (dmgDealt, dmgTaken) — newer
        #  14=rl flag (always present)
        #  15 optional (roleCode — newer)
        #  16 optional (weapon class — newest)
        name = match.group(2)
        team = int(match.group(3))
        score = int(match.group(4))
        deaths = int(match.group(5))
        if match.group(6) is not None:
            frags = int(match.group(6))
            flags_taken = int(match.group(7))
            flags_captured = int(match.group(8))
            flags_returned = int(match.group(9))
            shots = int(match.group(10))
            shots_on_target = int(match.group(11))
        else:
            frags = flags_taken = flags_captured = flags_returned = 0
            shots = shots_on_target = 0
        if match.group(12) is not None:
            damage_dealt = int(match.group(12))
            damage_taken = int(match.group(13))
        else:
            damage_dealt = damage_taken = 0
        is_rl = match.group(14) == "1"
        # Per-model bitmask (0..15). Current Java emits roleCode = 10 + mask
        # to avoid ambiguity with legacy pre-mask logs, where a single value
        # of 1 meant "some/all champion". For those legacy logs we map 1 to
        # mask=15 so they stay excluded from every current bucket.
        if match.group(15) is None:
            policy_role_mask = 0
        else:
            raw = int(match.group(15))
            if raw >= 10:
                policy_role_mask = raw - 10
            elif raw == 1:
                policy_role_mask = 15
            else:
                policy_role_mask = raw
        weapon = _canonical_weapon(match.group(16))
        snapshots.append(PlayerSnapshot(
            name=name, team=team, score=score, deaths=deaths,
            frags=frags, flags_taken=flags_taken, flags_captured=flags_captured,
            flags_returned=flags_returned, shots=shots, shots_on_target=shots_on_target,
            damage_dealt=damage_dealt, damage_taken=damage_taken,
            is_rl=is_rl, policy_role_mask=policy_role_mask,
            weapon=weapon,
        ))
    if not snapshots:
        return None
    return ts_ms, snapshots


def _gather_lines_from_host(hostname: str, password: str,
                             window_minutes: int) -> List[Tuple[str, str]]:
    cmd = (
        f"find {LOGS_DIR}/instance-*/features/PlayerPawn/ -name 'PlayerPawn.log*' "
        f"-mmin -{window_minutes + 2} 2>/dev/null | "
        f"while read f; do "
        f"  inst=$(echo \"$f\" | sed -E 's|.*/(instance-[^/]+)/.*|\\1|'); "
        f"  grep 'PLAYER_SCORES' \"$f\" 2>/dev/null | "
        f"     awk -v inst=\"$inst\" '{{print inst \"|\" $0}}' || true; "
        f"done"
    )
    output = _ssh_exec(hostname, password, cmd, timeout_s=20)
    out = []
    for line in output.splitlines():
        if "|" not in line:
            continue
        inst, _, log_line = line.partition("|")
        if "PLAYER_SCORES" not in log_line:
            continue
        match_id = f"{hostname}:{inst}"
        out.append((match_id, log_line))
    return out


def _effective_window_minutes(window_minutes: int,
                                since_ts_unix_s: Optional[float]) -> int:
    """Resolve the file mtime-filter window for SSH ``find -mmin``.

    With ``since_ts`` given, the window must cover (now − since_ts) plus a
    small slack for log rotation; otherwise the caller's explicit
    ``window_minutes`` is used as-is.
    """
    if since_ts_unix_s is None:
        return window_minutes
    minutes_since = (time.time() - since_ts_unix_s) / 60.0
    return max(2, int(minutes_since) + 2)


def _gather_match_end_lines_from_host(hostname: str, password: str,
                                       window_minutes: int) -> List[Tuple[str, str]]:
    """SSH grep MATCH_ENDED lines across all instances on a host.

    Returns ``[(instance_key, raw_line), …]`` with ``instance_key`` of the
    form ``"<hostname>:<instance-NN>"`` so the caller can dedup per-instance
    across machines.
    """
    cmd = (
        f"find {LOGS_DIR}/instance-*/features/PlayerPawn/ -name 'PlayerPawn.log*' "
        f"-mmin -{window_minutes + 2} 2>/dev/null | "
        f"while read f; do "
        f"  inst=$(echo \"$f\" | sed -E 's|.*/(instance-[^/]+)/.*|\\1|'); "
        f"  grep 'MATCH_ENDED' \"$f\" 2>/dev/null | "
        f"     awk -v inst=\"$inst\" '{{print inst \"|\" $0}}' || true; "
        f"done"
    )
    output = _ssh_exec(hostname, password, cmd, timeout_s=20)
    out = []
    for line in output.splitlines():
        if "|" not in line:
            continue
        inst, _, log_line = line.partition("|")
        if "MATCH_ENDED" not in log_line:
            continue
        out.append((f"{hostname}:{inst}", log_line))
    return out


def _is_counter_reset(prev: PlayerSnapshot, curr: PlayerSnapshot) -> bool:
    """True when any cumulative counter dropped between prev and curr — signals
    that the UT99/UC game state was reinitialized between the two snapshots.

    Why this matters: ``PlayerScoresLogger`` emits the raw per-match counters
    from UC binary frames. Counters reset to 0 on every match-end — UT99
    DeathMatchPlus.RestartGame() calls Level.ServerTravel("?Restart") which
    reloads the map. Without reset detection, ``compute_delta`` would compare
    a pre-reset ``first`` snapshot against a post-reset ``last`` snapshot.
    ``max(0, last - first)`` then collapses to 0, even though both segments
    individually contained real gameplay — turning a normal cycle into a
    near-zero KPI reading whenever a window straddles a reset. Detected
    resets cause the caller to discard the pre-reset segment and restart
    accumulation from the post-reset snapshot.
    """
    return (curr.frags < prev.frags
            or curr.score < prev.score
            or curr.deaths < prev.deaths
            or curr.flags_taken < prev.flags_taken
            or curr.flags_captured < prev.flags_captured
            or curr.flags_returned < prev.flags_returned
            or curr.shots < prev.shots
            or curr.shots_on_target < prev.shots_on_target
            or curr.damage_dealt < prev.damage_dealt
            or curr.damage_taken < prev.damage_taken)


def _kpi_value(snap: PlayerSnapshot, kpi: str) -> float:
    """Extract a single accumulator from a snapshot voor de gegeven KPI.
    Wordt door _kpi_per_player gebruikt om first/last deltas te berekenen.
    Voor `aim_accuracy` worden zowel `shots` als `shots_on_target` gebruikt — die
    case wordt apart afgehandeld in _kpi_per_player."""
    if kpi == KPI_SCORE:
        return float(snap.score)
    if kpi == KPI_FRAGS:
        return float(snap.frags)
    if kpi == KPI_FLAG_SCORE:
        return (FLAG_SCORE_W_TAKEN * snap.flags_taken
                + FLAG_SCORE_W_CAPTURED * snap.flags_captured
                + FLAG_SCORE_W_RETURNED * snap.flags_returned)
    if kpi == KPI_SHOTS_ON_TARGET_RATE:
        return float(snap.shots_on_target)
    if kpi == KPI_COMBAT_SCORE:
        # Frags + (dealt - 0.3*taken)/80, in kill-equivalent units. Geeft
        # credit voor damage zonder kill (bot doet mee in gevechten) en
        # straft passieve damage-eat strategie.
        return (float(snap.frags)
                + snap.damage_dealt / COMBAT_SCORE_HP_PER_KILL
                - COMBAT_SCORE_TAKEN_PENALTY * snap.damage_taken / COMBAT_SCORE_HP_PER_KILL)
    raise ValueError(f"_kpi_value not applicable for kpi='{kpi}' (use _aim_ratio for ratio KPI's)")


def _aim_ratio_for_player(first: PlayerSnapshot, last: PlayerSnapshot) -> Optional[float]:
    """Compute shots-on-target ratio over [first, last]. Returns None when there
    were no shots in the window — those samples don't carry aim-quality info."""
    shots_delta = last.shots - first.shots
    on_delta = last.shots_on_target - first.shots_on_target
    if shots_delta <= 0:
        return None
    # Clamp negative on-target delta (would only happen on counter reset; treat
    # as "no info").
    if on_delta < 0:
        return None
    ratio = on_delta / shots_delta
    return max(0.0, min(1.0, ratio))


def compute_delta(window_minutes: int = 10, kpi: str = KPI_SCORE,
                   rl_only_current: bool = True,
                   since_ts_unix_s: Optional[float] = None) -> DeltaResult:
    """Gather PLAYER_SCORES logs from all servers, compute delta over window.

    Args:
        window_minutes: rolling window size (also used as log mtime-filter slack).
            Ignored when ``since_ts_unix_s`` is given.
        kpi: one of KPI_SCORE / KPI_FRAGS / KPI_FLAG_SCORE / KPI_AIM_ACCURACY.
        rl_only_current: when True (default), the RL bucket excludes bots that
            run a frozen champion snapshot for any model. Used by DeltaGate so
            its vloer-meting (RL vs UT99) is not polluted by champion bots
            during self-play matches. Set False when you genuinely want to
            include all RL bots.
        since_ts_unix_s: when given, only snapshots with emit-time ≥ this
            timestamp contribute to the delta. Used by the match-aligned gate
            in training_loop.py to compute KPIs exactly over the matches
            played since the previous gate-fire (the gate triggers on
            MATCH_ENDED count and passes its anchor timestamp here).

    Returns DeltaResult; use `.is_significant(min_obs)` to check sample size.
    """
    if kpi not in KPI_ALL:
        raise ValueError(f"unknown kpi='{kpi}', choose from {sorted(KPI_ALL)}")

    hosts = load_server_hosts()
    effective_window = _effective_window_minutes(window_minutes, since_ts_unix_s)
    since_ms = int(since_ts_unix_s * 1000) if since_ts_unix_s is not None else None
    by_match: Dict[str, List[Tuple[int, List[PlayerSnapshot]]]] = defaultdict(list)

    for _machine_id, hostname, password in hosts:
        for match_id, line in _gather_lines_from_host(hostname, password, effective_window):
            parsed = _parse_line(line)
            if parsed is None:
                continue
            ts_ms, _snaps = parsed
            if since_ms is not None and ts_ms < since_ms:
                continue
            by_match[match_id].append(parsed)

    cleaned: Dict[str, List[Tuple[int, List[PlayerSnapshot]]]] = {}
    for match_id, entries in by_match.items():
        entries.sort(key=lambda e: e[0])
        bucket_best: Dict[int, Tuple[int, List[PlayerSnapshot]]] = {}
        for ts_ms, snaps in entries:
            bucket = ts_ms // 30_000
            best = bucket_best.get(bucket)
            if best is None or len(snaps) > len(best[1]):
                bucket_best[bucket] = (ts_ms, snaps)
        unique = [pair for pair in bucket_best.values() if len(pair[1]) >= 4]
        unique.sort(key=lambda e: e[0])
        if len(unique) >= 2:
            cleaned[match_id] = unique

    rl_values: List[float] = []
    ut99_values: List[float] = []
    total_minutes = 0.0

    for match_id, entries in cleaned.items():
        match_first_ts = entries[0][0]
        match_last_ts = entries[-1][0]
        match_span_min = (match_last_ts - match_first_ts) / 60_000.0
        if match_span_min < 1.0:
            continue
        total_minutes += match_span_min

        # Per-player observatie: bewaar EERSTE en LAATSTE volledige snapshot
        # zodat per-counter delta's kloppen ongeacht KPI-keuze.
        per_player: Dict[str, Dict] = {}
        for ts_ms, snaps in entries:
            for s in snaps:
                rec = per_player.get(s.name)
                if rec is None:
                    per_player[s.name] = {
                        'first_ts': ts_ms, 'first': s,
                        'last_ts': ts_ms, 'last': s,
                        'is_rl': s.is_rl,
                    }
                else:
                    if _is_counter_reset(rec['last'], s):
                        # UC counters reset between prev and current snapshot
                        # (hourly bot-restart in multi_instance.sh, or a UT99
                        # match-end). Discard the pre-restart segment so the
                        # gain reflects only the post-restart steady-state,
                        # not a phantom drop from cumulative-counter collapse.
                        rec['first_ts'] = ts_ms
                        rec['first'] = s
                    rec['last_ts'] = ts_ms
                    rec['last'] = s
                    rec['is_rl'] = s.is_rl

        for name, rec in per_player.items():
            span_min = (rec['last_ts'] - rec['first_ts']) / 60_000.0
            if span_min < 1.0:
                continue

            if kpi == KPI_AIM_ACCURACY:
                ratio = _aim_ratio_for_player(rec['first'], rec['last'])
                if ratio is None:
                    continue  # geen shots in window → geen aim-info
                value = ratio
            else:
                gain = max(0.0, _kpi_value(rec['last'], kpi) - _kpi_value(rec['first'], kpi))
                value = gain / span_min

            if rec['is_rl']:
                if rl_only_current and rec['last'].policy_role_mask != 0:
                    # Champion bot for any model — skip from rl_avg so
                    # DeltaGate's vloer is RL_CURRENT vs UT99, not muddied
                    # by frozen policies.
                    continue
                rl_values.append(value)
            else:
                ut99_values.append(value)

    rl_avg = sum(rl_values) / len(rl_values) if rl_values else 0.0
    ut99_avg = sum(ut99_values) / len(ut99_values) if ut99_values else 0.0

    return DeltaResult(
        delta=rl_avg - ut99_avg,
        rl_avg_gain=rl_avg,
        ut99_avg_gain=ut99_avg,
        rl_n=len(rl_values),
        ut99_n=len(ut99_values),
        minutes_observed=total_minutes,
        matches=len(cleaned),
        kpi=kpi,
    )


def compute_delta_per_weapon(window_minutes: int = 10, kpi: str = KPI_SCORE,
                              rl_only_current: bool = True,
                              since_ts_unix_s: Optional[float] = None) -> Dict[str, DeltaResult]:
    """Per-weapon variant van compute_delta — partitioneert deltas op het
    wapen dat aan het EIND van elk consecutive (prev, curr) snapshot-paar
    werd gedragen.

    Returnt Dict[canonical_weapon_key, DeltaResult] voor elk wapen met ≥1
    RL én ≥1 UT99 observatie. De "none"-bucket (geen wapen) wordt overgeslagen.
    Wapens met te weinig samples herken je via `result.is_significant(min_obs)`.

    Attributie-heuristiek: een counter-delta tussen snapshots op T1 en T2
    wordt volledig toegerekend aan het wapen dat op T2 gedragen werd. Met
    de 60s emit-cadans middelt dit zich uit over meerdere windows zolang
    de bot niet sneller dan ~30s gemiddeld wisselt. Bij snel wapen-wisselen
    skewt deze approximatie naar end-of-window wapens — voor exacte
    per-weapon counters moet de UC-mod per-weapon tellers bijhouden
    (PlayerDto extension), buiten scope voor v1.

    Used by DualKPIDeltaGate.evaluate() voor AND-promotion over alle
    actieve wapens binnen het meet-window.
    """
    if kpi not in KPI_ALL:
        raise ValueError(f"unknown kpi='{kpi}', choose from {sorted(KPI_ALL)}")

    hosts = load_server_hosts()
    effective_window = _effective_window_minutes(window_minutes, since_ts_unix_s)
    since_ms = int(since_ts_unix_s * 1000) if since_ts_unix_s is not None else None
    by_match: Dict[str, List[Tuple[int, List[PlayerSnapshot]]]] = defaultdict(list)
    for _machine_id, hostname, password in hosts:
        for match_id, line in _gather_lines_from_host(hostname, password, effective_window):
            parsed = _parse_line(line)
            if parsed is None:
                continue
            ts_ms, _snaps = parsed
            if since_ms is not None and ts_ms < since_ms:
                continue
            by_match[match_id].append(parsed)

    cleaned: Dict[str, List[Tuple[int, List[PlayerSnapshot]]]] = {}
    for match_id, entries in by_match.items():
        entries.sort(key=lambda e: e[0])
        bucket_best: Dict[int, Tuple[int, List[PlayerSnapshot]]] = {}
        for ts_ms, snaps in entries:
            bucket = ts_ms // 30_000
            best = bucket_best.get(bucket)
            if best is None or len(snaps) > len(best[1]):
                bucket_best[bucket] = (ts_ms, snaps)
        unique = [pair for pair in bucket_best.values() if len(pair[1]) >= 4]
        unique.sort(key=lambda e: e[0])
        if len(unique) >= 2:
            cleaned[match_id] = unique

    per_weapon_rl: Dict[str, List[float]] = defaultdict(list)
    per_weapon_ut99: Dict[str, List[float]] = defaultdict(list)
    per_weapon_minutes: Dict[str, float] = defaultdict(float)

    for match_id, entries in cleaned.items():
        # Per-player chronologische sequence van (ts, snap) ipv enkel first/last,
        # zodat we per consecutive pair een weapon-attribution kunnen doen.
        per_player: Dict[str, List[Tuple[int, PlayerSnapshot]]] = defaultdict(list)
        for ts_ms, snaps in entries:
            for s in snaps:
                per_player[s.name].append((ts_ms, s))

        for name, seq in per_player.items():
            seq.sort(key=lambda e: e[0])
            for i in range(1, len(seq)):
                prev_ts, prev = seq[i - 1]
                curr_ts, curr = seq[i]
                if _is_counter_reset(prev, curr):
                    # Restart tussen prev en curr — pre-restart segment is
                    # niet attribueerbaar; skip de pair.
                    continue
                weapon = curr.weapon
                if weapon == "none":
                    continue  # geen valide attribution
                span_min = (curr_ts - prev_ts) / 60_000.0
                if span_min < 0.5:
                    continue  # te kort segment

                if kpi == KPI_AIM_ACCURACY:
                    ratio = _aim_ratio_for_player(prev, curr)
                    if ratio is None:
                        continue
                    value = ratio
                else:
                    gain = max(0.0, _kpi_value(curr, kpi) - _kpi_value(prev, kpi))
                    value = gain / span_min

                if curr.is_rl:
                    if rl_only_current and curr.policy_role_mask != 0:
                        # Champion bot — uitgesloten van rl_avg zodat de
                        # gate niet vergelijkt met eigen frozen snapshots.
                        continue
                    per_weapon_rl[weapon].append(value)
                else:
                    per_weapon_ut99[weapon].append(value)
                per_weapon_minutes[weapon] += span_min

    results: Dict[str, DeltaResult] = {}
    for weapon in set(per_weapon_rl.keys()) | set(per_weapon_ut99.keys()):
        rl_vals = per_weapon_rl.get(weapon, [])
        ut99_vals = per_weapon_ut99.get(weapon, [])
        rl_avg = sum(rl_vals) / len(rl_vals) if rl_vals else 0.0
        ut99_avg = sum(ut99_vals) / len(ut99_vals) if ut99_vals else 0.0
        results[weapon] = DeltaResult(
            delta=rl_avg - ut99_avg,
            rl_avg_gain=rl_avg,
            ut99_avg_gain=ut99_avg,
            rl_n=len(rl_vals),
            ut99_n=len(ut99_vals),
            minutes_observed=per_weapon_minutes[weapon],
            matches=len(cleaned),
            kpi=kpi,
        )
    return results


def count_match_ends_since(since_ts_unix_s: float) -> int:
    """Count MATCH_ENDED log emits across all servers since a timestamp.

    Returns ``max(per_instance_count)`` — represents how many matches the most
    progressive instance has completed since ``since_ts_unix_s``. Matches run
    synchronously across instances (identical TimeLimit, GameSpeed=1.0), so
    the max-instance count is the actual match-progress; using min() would
    let one single hung/slow instance gate the entire cluster.

    Dedup: per (instance, 5s timestamp-bucket) — multiple RL-bots within a
    single UT99 instance each emit MATCH_ENDED on the same transition, but
    they fire within tight wall-clock proximity. A 5s bucket collapses those
    redundant emits to one event per match-grens per instance.
    """
    hosts = load_server_hosts()
    since_ms = int(since_ts_unix_s * 1000)
    minutes_since = (time.time() - since_ts_unix_s) / 60.0
    window_minutes = max(2, int(minutes_since) + 2)
    per_instance: Dict[str, Set[int]] = defaultdict(set)

    for _machine_id, hostname, password in hosts:
        for instance_key, line in _gather_match_end_lines_from_host(
                hostname, password, window_minutes):
            m = _MATCH_ENDED_RE.search(line)
            if not m:
                continue
            ts_ms = int(m.group(1))
            if ts_ms < since_ms:
                continue
            per_instance[instance_key].add(ts_ms // 5000)

    if not per_instance:
        return 0
    return max(len(buckets) for buckets in per_instance.values())


if __name__ == "__main__":
    """CLI: print delta over last N minutes (default 10) per KPI.

    Usage: python -m train.rl.shared.player_scores_eval [window_minutes] [kpi]
    """
    import sys
    window = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    kpi = sys.argv[2] if len(sys.argv) > 2 else KPI_SCORE
    result = compute_delta(window_minutes=window, kpi=kpi)
    print(result)
    if result.is_significant():
        unit = "/min" if kpi in KPI_RATE_BASED else ""
        print(f"verdict: RL bots are {'BETTER' if result.delta > 0 else 'WORSE'} "
              f"than UT99 baseline on {kpi} (delta={result.delta:+.3f}{unit})")
    else:
        print(f"verdict: insufficient data (need >=20 obs each, got rl={result.rl_n}, ut99={result.ut99_n})")
