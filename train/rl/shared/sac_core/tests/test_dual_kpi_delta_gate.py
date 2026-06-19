"""DualKPIDeltaGate — promote AND-criterium + rollback OR-criterium tijdseries."""
from __future__ import annotations

import pytest

from train.rl.shared.delta_gate import (
    DualKPIDeltaGate,
    DualKPIDeltaGateConfig,
)


def _make_cfg(**overrides) -> DualKPIDeltaGateConfig:
    base = dict(
        dual_kpi=True,
        kpi_primary="combat_score",
        kpi_secondary="shots_on_target_rate",
        kpi_movement="flag_score",
        promote_combat_score_min_ratio=0.95,
        promote_aim_min_ratio=0.80,
        promote_movement_min_ratio=0.70,
        promote_window_cycles=3,
        rollback_combat_score_max_ratio=0.85,
        rollback_aim_max_ratio=0.60,
        rollback_movement_max_ratio=0.50,
        rollback_window_cycles=2,
        matches_per_eval_cycle=3,
        min_steps_before_eval=0,
        consecutive_rollback_adam_wipe_threshold=3,
    )
    base.update(overrides)
    return DualKPIDeltaGateConfig.from_dict(base)


def test_from_dict_requires_all_keys():
    """Strict-load: omitting any key raises ValueError (CLAUDE.md: no fallbacks)."""
    full = dict(
        dual_kpi=True, kpi_primary="combat_score", kpi_secondary="aim",
        kpi_movement="flag_score",
        promote_combat_score_min_ratio=0.95, promote_aim_min_ratio=0.8,
        promote_movement_min_ratio=0.7,
        promote_window_cycles=3,
        rollback_combat_score_max_ratio=0.85, rollback_aim_max_ratio=0.6,
        rollback_movement_max_ratio=0.5,
        rollback_window_cycles=2, matches_per_eval_cycle=3,
        min_steps_before_eval=60000,
        consecutive_rollback_adam_wipe_threshold=3,
    )
    for missing_key in list(full):
        partial = {k: v for k, v in full.items() if k != missing_key}
        with pytest.raises(ValueError, match="missing required keys"):
            DualKPIDeltaGateConfig.from_dict(partial)


def test_promote_requires_both_kpis_passing_for_window_cycles():
    """AND-criterium: één KPI net onder ratio blokkeert promote, ook al haalt
    de andere; alleen wanneer BEIDE passeren over `promote_window_cycles`
    opeenvolgende cycli triggert PROMOTE."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    # Cyclus 1: combat ratio 1.00 (≥0.95 OK), aim ratio 0.79 (<0.80 → NEUTRAL).
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=15.8, current_movement_score=5.0)
    assert r.decision == "NEUTRAL"
    assert gate.promote_streak == 0

    # Cyclus 2-4: beide passen.
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL" and gate.promote_streak == 1
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL" and gate.promote_streak == 2
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "PROMOTE", f"expected PROMOTE after 3 passing cycles, got {r.decision}"
    assert gate.promote_streak == 0, "promote_streak must reset after PROMOTE"


def test_rollback_triggers_on_single_kpi_violation_for_window_cycles():
    """OR-criterium: ÉÉN KPI onder rollback-threshold voor `rollback_window_cycles`
    cycli triggert ROLLBACK; andere KPI mag binnen marges blijven."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    # Cyclus 1: combat ratio 0.80 (<0.85 → violation).
    # aim ratio 0.90 (≥0.60 → geen violation).
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL"
    assert gate.rollback_combat_streak == 1
    assert gate.rollback_aim_streak == 0

    # Cyclus 2: zelfde → streak=2, ROLLBACK.
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "ROLLBACK", f"expected ROLLBACK after 2 violation cycles, got {r.decision}"
    assert "combat_score ratio" in r.reason


def test_single_violation_below_rollback_for_one_cycle_no_action():
    """Eén cyclus onder rollback-drempel ≠ ROLLBACK (filter ruis)."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    # combat ratio 0.80, aim ratio 0.90 — combat onder rollback, aim oké.
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL", f"single-cycle violation should not rollback, got {r.decision}"
    assert gate.rollback_combat_streak == 1


def test_aim_only_violation_triggers_rollback():
    """OR: alleen aim onder rollback voor 2 cycli → ROLLBACK via aim-streak."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    # combat ratio 0.92 (within marges), aim ratio 0.50 (<0.60 → violation).
    r = gate.evaluate(current_combat_score=9.2, current_aim_rate=10.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL"
    assert gate.rollback_aim_streak == 1

    r = gate.evaluate(current_combat_score=9.2, current_aim_rate=10.0, current_movement_score=5.0)
    assert r.decision == "ROLLBACK"
    assert "aim_rate ratio" in r.reason


def test_movement_only_violation_triggers_rollback():
    """OR: alleen movement onder rollback voor 2 cycli → ROLLBACK via movement-streak."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    r = gate.evaluate(current_combat_score=9.2, current_aim_rate=18.0,
                      current_movement_score=2.0)
    assert r.decision == "NEUTRAL"
    assert gate.rollback_movement_streak == 1

    r = gate.evaluate(current_combat_score=9.2, current_aim_rate=18.0,
                      current_movement_score=2.0)
    assert r.decision == "ROLLBACK"
    assert "movement_flag_score ratio" in r.reason


def test_promote_ready_cycle_resets_rollback_streaks():
    """Een cyclus met BEIDE ratios ≥ promote-thresholds annuleert lopende
    rollback-streaks (geen oscillatie tussen PROMOTE en ROLLBACK)."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert gate.rollback_combat_streak == 1
    # Promote-ready cyclus: combat 1.0 ≥ 0.95, aim 0.90 ≥ 0.80.
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL"
    assert gate.rollback_combat_streak == 0, "rollback streak must reset on promote-ready cycle"
    assert gate.promote_streak == 1


def test_insufficient_when_baseline_missing():
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=None,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)
    r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "INSUFFICIENT"


def test_update_baselines_clears_all_streaks():
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)  # promote-ready
    assert gate.promote_streak == 1
    gate.update_baselines(baseline_combat_score=11.0, baseline_aim_rate=22.0,
                          baseline_movement_score=6.0)
    assert gate.promote_streak == 0
    assert gate.rollback_combat_streak == 0
    assert gate.rollback_aim_streak == 0
    assert gate.rollback_movement_streak == 0
    assert gate.consecutive_rollback_count == 0
    assert gate.baseline_combat == 11.0
    assert gate.baseline_aim == 22.0
    assert gate.baseline_movement == 6.0


def test_consecutive_rollback_count_increments_on_each_rollback():
    """Each ROLLBACK decision increments the counter; NEUTRAL does not.

    The training_loop reads this counter to trigger Adam-wipe escalation —
    without it, the actor's optimizer momentum persists across rollbacks and
    pushes the just-reverted policy straight back into the rejected direction.
    """
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)
    assert gate.consecutive_rollback_count == 0

    # NEUTRAL with one violation cycle — counter must stay at 0.
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "NEUTRAL"
    assert gate.consecutive_rollback_count == 0

    # Second violation cycle triggers ROLLBACK.
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "ROLLBACK"
    assert gate.consecutive_rollback_count == 1

    # Two more violation pairs → two more ROLLBACKs.
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)  # NEUTRAL
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "ROLLBACK"
    assert gate.consecutive_rollback_count == 2

    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)  # NEUTRAL
    r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert r.decision == "ROLLBACK"
    assert gate.consecutive_rollback_count == 3  # ≥ threshold; training_loop wipes Adam


def test_consecutive_rollback_count_resets_on_promote():
    """A PROMOTE decision breaks the rollback streak — counter back to 0.

    Without this reset, a long-lived gate that occasionally promotes would
    keep escalating Adam-wipes inappropriately on the next rollback.
    """
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    # Accumulate 2 rollbacks.
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert gate.consecutive_rollback_count == 2

    # Three promote-ready cycles in a row → PROMOTE.
    for _ in range(3):
        gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
    assert gate.last_result.decision == "PROMOTE"
    assert gate.consecutive_rollback_count == 0, "PROMOTE must clear the rollback ratchet"


def test_consecutive_rollback_count_not_incremented_by_neutral():
    """NEUTRAL decisions — including PROMOTE_BUILDING_HELD intermediates —
    must not bump the counter, only confirmed ROLLBACKs do."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=10.0,
                             baseline_aim_rate=20.0, baseline_movement_score=5.0, logger=None)

    for _ in range(5):
        # Single-violation cycle then a passing cycle, repeated. Streak per
        # KPI never reaches rollback_window_cycles=2 → all decisions NEUTRAL.
        r = gate.evaluate(current_combat_score=8.0, current_aim_rate=18.0, current_movement_score=5.0)
        assert r.decision == "NEUTRAL"
        r = gate.evaluate(current_combat_score=10.0, current_aim_rate=18.0, current_movement_score=5.0)
        assert r.decision == "NEUTRAL"
    assert gate.consecutive_rollback_count == 0
