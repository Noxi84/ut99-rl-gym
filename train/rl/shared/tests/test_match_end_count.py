"""Tests for ``count_match_ends_since`` — the match-aligned gate trigger.

The trainer-side gate fires when the count of MATCH_ENDED log emits across
all servers reaches ``matches_per_eval_cycle`` since the previous fire.
Dedup is per (instance, 5s bucket) and aggregation is ``max(per_instance)``
so a single hung instance doesn't gate the cluster.

These tests bypass SSH by monkeypatching ``load_server_hosts`` and
``_gather_match_end_lines_from_host``.
"""
from __future__ import annotations

import time
from typing import List, Tuple

from train.rl.shared import player_scores_eval as pse


def _make_line(ts_ms: int, session: str = "bot-0") -> str:
    return f"MATCH_ENDED t={ts_ms} session={session}"


def _patch_hosts(monkeypatch, lines_per_host):
    """Install fake SSH-grep results.

    ``lines_per_host`` maps hostname → list of (instance_key, raw_line)
    tuples, mirroring what ``_gather_match_end_lines_from_host`` returns
    in production.
    """
    monkeypatch.setattr(pse, "load_server_hosts",
                        lambda: [(h, h, "pw") for h in lines_per_host])

    def fake_gather(hostname: str, password: str,
                    window_minutes: int) -> List[Tuple[str, str]]:
        return lines_per_host.get(hostname, [])

    monkeypatch.setattr(pse, "_gather_match_end_lines_from_host", fake_gather)


def test_returns_zero_when_no_events(monkeypatch):
    _patch_hosts(monkeypatch, {"hostA": []})
    assert pse.count_match_ends_since(time.time() - 60) == 0


def test_counts_events_after_since_ts(monkeypatch):
    """Only events at-or-after since_ts contribute; older ones are filtered."""
    now_ms = int(time.time() * 1000)
    since_ms = now_ms - 30_000  # 30s back
    lines = [
        ("hostA:instance-0", _make_line(since_ms - 60_000)),  # excluded (60s before)
        ("hostA:instance-0", _make_line(since_ms + 1_000)),   # included
        ("hostA:instance-0", _make_line(since_ms + 11_000)),  # included
    ]
    _patch_hosts(monkeypatch, {"hostA": lines})
    assert pse.count_match_ends_since(since_ms / 1000.0) == 2


def test_dedupes_within_5s_bucket(monkeypatch):
    """Multiple RL bots in the same instance emit MATCH_ENDED on the same
    transition (within ~1s of each other). The 5s bucket collapses those
    redundant emits so the count reflects unique match-grenzen.

    Fixed timestamps avoid the test being flaky on bucket-boundary alignment
    (a 700ms range straddling a 5s mark would otherwise yield 2 buckets).
    """
    # 1_000_000_000_000 ms is exactly on a 5s bucket boundary
    # (1e12 / 5000 = 2e8 exactly), so adding 0..800ms stays in one bucket.
    base = 1_000_000_000_000
    lines = [
        ("hostA:instance-0", _make_line(base + 0,   "bot-0")),
        ("hostA:instance-0", _make_line(base + 500, "bot-1")),
        ("hostA:instance-0", _make_line(base + 800, "bot-2")),
    ]
    _patch_hosts(monkeypatch, {"hostA": lines})
    assert pse.count_match_ends_since((base - 60_000) / 1000.0) == 1


def test_separates_events_more_than_5s_apart(monkeypatch):
    """Two genuine match-ends in the same instance, well-separated → count=2."""
    base = 1_000_000_000_000
    lines = [
        ("hostA:instance-0", _make_line(base + 0)),         # match 1
        ("hostA:instance-0", _make_line(base + 660_000)),   # match 2 (~11 min later)
    ]
    _patch_hosts(monkeypatch, {"hostA": lines})
    assert pse.count_match_ends_since((base - 60_000) / 1000.0) == 2


def test_takes_max_across_instances(monkeypatch):
    """One hung instance with 1 match-end and a healthy instance with 3 →
    cluster progress is 3 (max), not 1 (min) or 4 (sum)."""
    base = 1_000_000_000_000
    lines = [
        ("hostA:instance-hung",   _make_line(base + 200_000)),
        ("hostA:instance-active", _make_line(base + 0)),
        ("hostA:instance-active", _make_line(base + 660_000)),
        ("hostA:instance-active", _make_line(base + 1_320_000)),
    ]
    _patch_hosts(monkeypatch, {"hostA": lines})
    assert pse.count_match_ends_since((base - 60_000) / 1000.0) == 3


def test_aggregates_across_hosts(monkeypatch):
    """Instances on different hosts contribute independently — max is taken
    across the entire (host, instance) keyspace."""
    base = 1_000_000_000_000
    _patch_hosts(monkeypatch, {
        "hostA": [
            ("hostA:instance-0", _make_line(base + 0)),
            ("hostA:instance-0", _make_line(base + 660_000)),
        ],
        "hostB": [
            ("hostB:instance-0", _make_line(base + 60_000)),
            ("hostB:instance-0", _make_line(base + 720_000)),
            ("hostB:instance-0", _make_line(base + 1_380_000)),
            ("hostB:instance-0", _make_line(base + 2_040_000)),
        ],
    })
    assert pse.count_match_ends_since((base - 60_000) / 1000.0) == 4
