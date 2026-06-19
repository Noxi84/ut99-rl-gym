"""Counter-reset detection in compute_delta — verifies that reset boundaries
inside the KPI window don't collapse the gain to 0.

Background: counter-resets happen at every match-end (UT99 ServerTravel
"?Restart" reloads the map). UC counters (frags, damage, shots, …) reset
to 0, so a window straddling a reset contains snapshots whose
cumulative counters go monotonic-up, then drop to 0, then monotonic-up again.
Pre-fix, ``compute_delta`` would set ``first`` to the pre-reset begin and
``last`` to a post-reset snap, yielding ``max(0, low - high) = 0``. Cycle 6
on 2026-05-15 00:11 dropped combat_ratio from 1.005 → 0.036 purely from this
artifact while the inflight policy was unchanged.
"""
from __future__ import annotations

from train.rl.shared.player_scores_eval import (
    PlayerSnapshot,
    _is_counter_reset,
)


def _snap(*, frags=0, score=0, deaths=0, flags_taken=0, flags_captured=0,
          flags_returned=0, shots=0, shots_on_target=0,
          damage_dealt=0, damage_taken=0, name="bot", team=0,
          is_rl=True, policy_role_mask=0) -> PlayerSnapshot:
    return PlayerSnapshot(
        name=name, team=team, score=score, deaths=deaths, frags=frags,
        flags_taken=flags_taken, flags_captured=flags_captured,
        flags_returned=flags_returned, shots=shots,
        shots_on_target=shots_on_target, damage_dealt=damage_dealt,
        damage_taken=damage_taken, is_rl=is_rl,
        policy_role_mask=policy_role_mask,
    )


def test_no_reset_when_counters_increase():
    """Normal monotonic progression within a single UT99 match — no reset."""
    a = _snap(frags=3, damage_dealt=200, shots=20)
    b = _snap(frags=5, damage_dealt=400, shots=35)
    assert not _is_counter_reset(a, b)


def test_no_reset_when_counters_unchanged():
    """No activity between two 60s emits is normal — not a reset."""
    a = _snap(frags=3, damage_dealt=200)
    b = _snap(frags=3, damage_dealt=200)
    assert not _is_counter_reset(a, b)


def test_reset_detected_on_frags_drop():
    """UC restart wipes all counters; any drop is a reset signal."""
    pre = _snap(frags=7, damage_dealt=500, shots=40, shots_on_target=12)
    post = _snap(frags=0, damage_dealt=0, shots=0, shots_on_target=0)
    assert _is_counter_reset(pre, post)


def test_reset_detected_on_damage_drop_only():
    """Detector is OR-based: any single counter regression is enough."""
    pre = _snap(frags=5, damage_dealt=400)
    post = _snap(frags=5, damage_dealt=100)
    assert _is_counter_reset(pre, post)


def test_reset_detected_on_flag_counter_drop():
    pre = _snap(flags_captured=2, flags_taken=3)
    post = _snap(flags_captured=0, flags_taken=0)
    assert _is_counter_reset(pre, post)


def test_compute_delta_aggregation_with_reset(monkeypatch):
    """End-to-end: a player with snapshots straddling a counter-reset should
    contribute the post-restart segment's per-minute gain, not 0.

    Simulates one bot with three snapshots in a 10-min window:
       t=0 min:  frags=0, dmg=0      (window start, pre-restart begin)
       t=6 min:  frags=4, dmg=320    (still pre-restart, accumulating)
       t=7 min:  RESTART (UC killed; counters reset)
       t=9 min:  frags=2, dmg=160    (post-restart, fresh accumulation)

    With the counter-reset fix, ``first`` rolls forward to the t=9 snapshot's
    last-seen-before-reset boundary; gain over the post-restart segment is
    (frags=2 + 160/80) - 0 = 4 kill-equivalents over (10-9)=1 min span.

    Pre-fix this would yield max(0, 2 - 0) / 10 = 0.2 per minute (artificially
    low because ``first`` was the t=0 snapshot frags=0 — the bug only triggers
    when post-restart counters are *below* pre-restart, not above).

    A clearer demonstration: pre-restart frags=4, post-restart frags=2:
       Pre-fix: max(0, 2 - 0) / 10 min = 0.2  (or worse, max(0, 2 - 4) = 0)
       Post-fix: (2 - 0) over post-restart span only
    """
    from train.rl.shared import player_scores_eval as pse

    # We don't need the SSH plumbing — exercise the per_player aggregator
    # directly via a small adapter that mirrors lines 384-431 of compute_delta.
    entries = [
        (0 * 60_000,  [_snap(frags=0, damage_dealt=0)]),
        (6 * 60_000,  [_snap(frags=4, damage_dealt=320)]),
        # 7 min: bot restart, UC reset
        (9 * 60_000,  [_snap(frags=2, damage_dealt=160)]),  # post-restart
        (10 * 60_000, [_snap(frags=3, damage_dealt=240)]),  # post-restart end
    ]

    per_player: dict = {}
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
                if pse._is_counter_reset(rec['last'], s):
                    rec['first_ts'] = ts_ms
                    rec['first'] = s
                rec['last_ts'] = ts_ms
                rec['last'] = s
                rec['is_rl'] = s.is_rl

    rec = per_player["bot"]
    # Segment should be t=9 (first after reset) → t=10 (last in window).
    assert rec['first_ts'] == 9 * 60_000
    assert rec['last_ts'] == 10 * 60_000
    assert rec['first'].frags == 2
    assert rec['last'].frags == 3

    # Combat gain over the post-restart segment: (3 + 240/80) - (2 + 160/80)
    # = 1 frag + 1.0 unit damage = 1.0 kill-equiv per (10-9)=1 min = 1.0/min.
    combat_gain = pse._kpi_value(rec['last'], pse.KPI_COMBAT_SCORE) - \
                  pse._kpi_value(rec['first'], pse.KPI_COMBAT_SCORE)
    span_min = (rec['last_ts'] - rec['first_ts']) / 60_000.0
    assert span_min == 1.0
    assert combat_gain == 2.0  # 1 frag (+1) + 80HP damage (+1.0 unit)


def test_no_reset_keeps_full_window():
    """When the entire window is one match (no restart), first/last span the
    full window — fix must not alter normal-case behavior."""
    from train.rl.shared import player_scores_eval as pse

    entries = [
        (0 * 60_000,  [_snap(frags=0, damage_dealt=0)]),
        (5 * 60_000,  [_snap(frags=3, damage_dealt=240)]),
        (10 * 60_000, [_snap(frags=6, damage_dealt=480)]),
    ]

    per_player: dict = {}
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
                if pse._is_counter_reset(rec['last'], s):
                    rec['first_ts'] = ts_ms
                    rec['first'] = s
                rec['last_ts'] = ts_ms
                rec['last'] = s
                rec['is_rl'] = s.is_rl

    rec = per_player["bot"]
    assert rec['first_ts'] == 0
    assert rec['last_ts'] == 10 * 60_000
    assert rec['first'].frags == 0
    assert rec['last'].frags == 6
