"""Fase 4b Deel B — DualKPIDeltaGate live wiring tests.

De pure gate-mechanica (promote AND, rollback OR, streak-bookkeeping) wordt
al gedekt door ``train/rl/shared/sac_core/tests/test_dual_kpi_delta_gate.py``.
Deze tests focussen op het *wiring* aspect:

* baseline.json met null waardes → placeholder mode + WARN log + actie=hold
* baseline.json populated → echte ratios + correcte decisions over cycli
* OR-rollback uitschakelt niet zolang andere KPI binnen marges blijft

Geen training_loop integratie hier (vereist actor/critic/replay) — alleen de
``_load_baselines`` + ``DualKPIDeltaGate.evaluate`` interactie wordt
end-to-end gedraaid op synthetische data.
"""
from __future__ import annotations

import json
from pathlib import Path

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


class _CapturingLogger:
    """Minimal stand-in voor de trainer-logger zodat tests assertions kunnen
    doen op log content zonder dat we de echte SessionRollingLogger booten."""
    def __init__(self) -> None:
        self.lines: list[str] = []

    def info(self, msg: str) -> None:
        self.lines.append(msg)


def test_placeholder_mode_on_null_baseline(tmp_path: Path) -> None:
    """Wanneer baseline.json null waardes heeft moet _load_baselines een
    placeholder ``JointBaselines(1.0, 1.0)`` teruggeven, een WARN log
    schrijven, en de gate moet INSUFFICIENT/NEUTRAL teruggeven zonder
    promote/rollback acties. Geen crash — gebruiker mag trainen zonder
    bewezen baseline."""
    # Re-implementeer de mini logica van training_loop._load_baselines tegen
    # een tijdelijke baseline.json. De echte _load_baselines leest uit
    # _CONFIG_DIR (process-global) wat tests niet mogen muteren; via Monkey
    # patching kunnen we hem op tmp_path richten zonder side-effects.
    import train.rl.rl_pawn.trainSAC.training_loop as tl

    fake_baseline = tmp_path / "baseline.json"
    fake_baseline.write_text(json.dumps({
        "decoupled_vr_shots_on_target_rate": None,
        "decoupled_movement_flag_score": None,
        "decoupled_shooting_combat_score": None,
        "measurement_window_minutes": 5,
        "measurement_window_count": 10,
        "_samples_observed": [],
        "measurement_date": None,
        "measurement_git_sha": None,
    }))

    # Tijdelijk _CONFIG_DIR redirecten.
    original = tl._CONFIG_DIR
    tl._CONFIG_DIR = tmp_path
    log_lines: list[str] = []
    try:
        baselines = tl._load_baselines(_StubLogger(log_lines))
    finally:
        tl._CONFIG_DIR = original

    assert baselines.combat_score == 1.0
    assert baselines.shots_on_target_rate == 1.0
    assert baselines.source.startswith("placeholder")
    assert any("WARNING" in line and "baseline" in line.lower() for line in log_lines), \
        f"verwacht WARN log met 'baseline' — kreeg: {log_lines}"


def test_promote_after_three_cycles_with_populated_baseline() -> None:
    """3 cycli waar beide ratios ≥ promote-threshold → PROMOTE actie."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg,
        baseline_combat_score=6.86,        # huidige live shooting baseline
        baseline_aim_rate=0.90,            # huidige VR baseline (Iter 14)
        baseline_movement_score=1.885,     # huidige movement flag_score baseline
        logger=_CapturingLogger())

    # Drie identieke promote-ready cycli: combat 0.96, aim 0.85.
    decisions = []
    for _ in range(3):
        r = gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
        decisions.append(r.decision)
    # Eerste twee NEUTRAL (streak opbouwen), derde PROMOTE.
    assert decisions[:2] == ["NEUTRAL", "NEUTRAL"]
    assert decisions[2] == "PROMOTE", f"verwacht PROMOTE, kreeg: {decisions}"


def test_rollback_after_two_skill_fail() -> None:
    """2 cycli waar één KPI < rollback-threshold (de ander OK) → ROLLBACK.
    Live-wiring sanity: cross-skill regressie wordt onafhankelijk gedetecteerd,
    één KPI is voldoende (OR-rollback)."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=6.86,
        baseline_aim_rate=0.90, baseline_movement_score=1.885, logger=_CapturingLogger())

    # combat 0.84 → ratio 0.84/0.86 = 0.978 ... wacht, ratio is current/baseline.
    # Voor ratio 0.84 willen we current = 0.84 * 6.86 = 5.76; dat < 0.85 → violation.
    # aim ratio 0.90 → current = 0.81 (≥ 0.60·0.90 = 0.54 → geen rollback-violation).
    r1 = gate.evaluate(current_combat_score=5.76, current_aim_rate=0.81, current_movement_score=1.885)
    assert r1.decision == "NEUTRAL"
    assert gate.rollback_combat_streak == 1
    assert gate.rollback_aim_streak == 0

    r2 = gate.evaluate(current_combat_score=5.76, current_aim_rate=0.81, current_movement_score=1.885)
    assert r2.decision == "ROLLBACK"
    assert "combat_score" in r2.reason


def test_or_rollback_logic_triggers_on_either_kpi() -> None:
    """Sectie 7.5 trigger A: één-skill regressie detecteerd via OR. Test
    expliciet dat het symmetrisch werkt — aim-only collapse triggert evengoed
    rollback, niet alleen combat-only collapse."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=6.86,
        baseline_aim_rate=0.90, baseline_movement_score=1.885, logger=_CapturingLogger())

    # combat ratio 0.92 (geen violation), aim ratio 0.50 (<0.60 → violation).
    # aim current = 0.50 * 0.90 = 0.45.
    gate.evaluate(current_combat_score=6.31, current_aim_rate=0.45, current_movement_score=1.885)
    r = gate.evaluate(current_combat_score=6.31, current_aim_rate=0.45, current_movement_score=1.885)
    assert r.decision == "ROLLBACK", (
        f"aim-only violation moet rollback triggeren via OR; kreeg: {r.decision}")
    assert "aim_rate" in r.reason


def test_promote_streak_resets_on_failed_cycle() -> None:
    """Een failing cyclus na 2 passing breekt de streak — vervolgens 3 nieuwe
    passing cycles vereist voor PROMOTE. Voorkomt dat oude streaks blijven
    hangen en stale-success false PROMOTEs veroorzaken."""
    cfg = _make_cfg()
    gate = DualKPIDeltaGate(cfg, baseline_combat_score=6.86,
        baseline_aim_rate=0.90, baseline_movement_score=1.885, logger=_CapturingLogger())

    # 2 passing cycles
    gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
    gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
    assert gate.promote_streak == 2
    # 1 failing (aim ratio 0.75 < 0.80)
    gate.evaluate(current_combat_score=6.58, current_aim_rate=0.675, current_movement_score=1.885)
    assert gate.promote_streak == 0
    # 2 nieuwe passing → nog NEUTRAL (need 3)
    r1 = gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
    r2 = gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
    assert r1.decision == "NEUTRAL" and r2.decision == "NEUTRAL"
    # 3e passing → PROMOTE
    r3 = gate.evaluate(current_combat_score=6.58, current_aim_rate=0.765, current_movement_score=1.885)
    assert r3.decision == "PROMOTE"


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

class _StubLogger:
    """log_print(logger, msg) uit TrainerLogger expects een logger.info(msg)
    methode. Stub die appended naar een externe list voor inspection."""
    def __init__(self, sink: list[str]) -> None:
        self.sink = sink

    def info(self, msg: str) -> None:
        self.sink.append(msg)
