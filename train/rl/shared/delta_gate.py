"""
DualKPIDeltaGate: in-game performance gate that promotes/rolls back candidates
based on goal-metrics (RL bots vs UT99 baseline bots) rather than the
train-metric (SAC return on the proxy reward).

Conceptual flow per gen:
  1. SAC trains a candidate
  2. Probe validates the candidate (collapse / fire-rate gate)
  3. If probe passes → DEPLOY candidate (becomes the active ONNX on all bots)
  4. Every eval_interval_seconds, this gate samples PLAYER_SCORES logs from all
     servers, computes per-KPI deltas and decides promote / rollback / neutral
     relative to the verified baseline.

The gate works on three in-game KPIs in parallel (combat_score AND
shots_on_target_rate AND flag_score). Promote only when ALL ratios >=
promote-thresholds over N consecutive cycles (AND). Rollback when ANY single
KPI < rollback-threshold for M cycles (OR).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional


# ============================================================================
# Joint movement+VR+shooting commitment-2: KPI DeltaGate
# ============================================================================
# Single-scalar reward (sectie 7.2 vr-shooting-sac-merge.md) draagt cross-dim
# gradient-noise risk. De gate werkt op drie in-game KPIs parallel:
#   - kpi_primary   = combat_score          (shooting baseline)
#   - kpi_secondary = shots_on_target_rate  (VR Iter-14 baseline)
#   - kpi_movement  = flag_score            (movement baseline)
# Promote alleen wanneer ALLE ratios >= promote-thresholds over N opeenvolgende
# cycli (AND). Rollback wanneer ÉÉN KPI < rollback-threshold voor M cycli (OR).
# OR-rollback is bewust gekozen: per-skill regressie detecteert cross-dim
# gradient-noise voordat een gemiddelde KPI het maskeert (sectie 7.3 commitment 2).


@dataclass
class DualKPIDeltaGateConfig:
    """Joint KPI gate config — gelezen uit ``resources/models/rl_pawn/export_gate.json``.

    KPI's worden uitgedrukt als ratio t.o.v. de huidige champion-baseline:
    ``ratio = current / baseline``. Baselines worden vóór joint-deployment
    opnieuw gemeten over 10 matches (zie sectie 10.2) — de gate ontvangt ze
    via constructor en is verder stateless wat KPI-magnitudes betreft.
    """
    dual_kpi: bool
    kpi_primary: str
    kpi_secondary: str
    kpi_movement: str
    promote_combat_score_min_ratio: float
    promote_aim_min_ratio: float
    promote_movement_min_ratio: float
    promote_window_cycles: int
    rollback_combat_score_max_ratio: float
    rollback_aim_max_ratio: float
    rollback_movement_max_ratio: float
    rollback_window_cycles: int
    # Gate-cadence is now match-aligned: trigger fires when MATCH_ENDED count
    # across servers reaches this threshold. Replaces the old wall-clock
    # ``eval_cycle_seconds`` which ignored ServerTravel/MinPlayers overhead
    # (~90s/match observed) and drifted out of phase with match grids over
    # time. Trainer-side: training_loop.py polls
    # player_scores_eval.count_match_ends_since(last_eval_ts).
    matches_per_eval_cycle: int
    min_steps_before_eval: int
    # Na N opeenvolgende ROLLBACK-decisions (zonder PROMOTE ertussen) wist de
    # joint training_loop de Adam optimizer state. Zonder dit blijft het
    # actor-momentum in de gerejecteerde gradient-richting zitten — de
    # post-rollback step duwt de policy onmiddellijk weer terug in de richting
    # die de gate net afkeurde (ratchet-loop). Parity met DeltaGate's
    # ``consecutive_rollback_reset_threshold`` (decoupled SAC).
    consecutive_rollback_adam_wipe_threshold: int

    _REQUIRED_KEYS = (
        "dual_kpi", "kpi_primary", "kpi_secondary", "kpi_movement",
        "promote_combat_score_min_ratio", "promote_aim_min_ratio",
        "promote_movement_min_ratio",
        "promote_window_cycles",
        "rollback_combat_score_max_ratio", "rollback_aim_max_ratio",
        "rollback_movement_max_ratio",
        "rollback_window_cycles",
        "matches_per_eval_cycle",
        "min_steps_before_eval",
        "consecutive_rollback_adam_wipe_threshold",
    )

    @classmethod
    def from_dict(cls, data: dict) -> "DualKPIDeltaGateConfig":
        """Strict load — alle keys uit ``_REQUIRED_KEYS`` vereist (CLAUDE.md).
        Onbekende keys (``_doc``, ``_doc_*``) worden genegeerd."""
        missing = [k for k in cls._REQUIRED_KEYS if k not in data]
        if missing:
            raise ValueError(
                f"DualKPIDeltaGateConfig: missing required keys {missing}"
            )
        return cls(
            dual_kpi=bool(data["dual_kpi"]),
            kpi_primary=str(data["kpi_primary"]),
            kpi_secondary=str(data["kpi_secondary"]),
            kpi_movement=str(data["kpi_movement"]),
            promote_combat_score_min_ratio=float(data["promote_combat_score_min_ratio"]),
            promote_aim_min_ratio=float(data["promote_aim_min_ratio"]),
            promote_movement_min_ratio=float(data["promote_movement_min_ratio"]),
            promote_window_cycles=int(data["promote_window_cycles"]),
            rollback_combat_score_max_ratio=float(data["rollback_combat_score_max_ratio"]),
            rollback_aim_max_ratio=float(data["rollback_aim_max_ratio"]),
            rollback_movement_max_ratio=float(data["rollback_movement_max_ratio"]),
            rollback_window_cycles=int(data["rollback_window_cycles"]),
            matches_per_eval_cycle=int(data["matches_per_eval_cycle"]),
            min_steps_before_eval=int(data["min_steps_before_eval"]),
            consecutive_rollback_adam_wipe_threshold=int(
                data["consecutive_rollback_adam_wipe_threshold"]),
        )


@dataclass
class DualKPIEvalResult:
    """Eén cyclus eval voor logging + persistence."""
    combat_score: float
    aim_rate: float
    movement_score: float
    combat_ratio: float
    aim_ratio: float
    movement_ratio: float
    decision: str          # "PROMOTE" | "ROLLBACK" | "NEUTRAL" | "INSUFFICIENT"
    promote_streak: int    # AFTER applying this cycle
    rollback_combat_streak: int
    rollback_aim_streak: int
    rollback_movement_streak: int
    reason: str            # menselijk leesbare context


@dataclass
class PerWeaponRatios:
    """Per-weapon ratio snapshot — diagnostisch detail voor logging.

    Geen decision-state; alle streak/promote/rollback logic blijft in
    DualKPIDeltaGate. Een lijst hiervan zit in DualKPIPerWeaponEvalResult.
    """
    weapon: str
    combat_ratio: float
    aim_ratio: float
    movement_ratio: float
    current_combat: float
    current_aim: float
    current_movement: float
    baseline_combat: float
    baseline_aim: float
    baseline_movement: float


@dataclass
class DualKPIPerWeaponEvalResult:
    """Joint per-weapon evaluation result.

    AND-promotion: alle wapens met baseline + voldoende current-data moeten
    boven hun eigen promote-thresholds voor ``promote_window_cycles`` cycli.
    OR-rollback per (weapon, KPI): één wapen + één KPI onder rollback-threshold
    voor ``rollback_window_cycles`` cycli triggert de streak. Reductie naar
    scalar via min(ratios) voor promote-checks, max(violations) voor
    rollback-checks — hierdoor blijft de bestaande streak-structuur van
    DualKPIDeltaGate intact.
    """
    per_weapon: Dict[str, PerWeaponRatios]
    # Wapens die meedoen aan de decision (in zowel baseline als current met
    # voldoende samples). Wapens met insufficient samples zitten in
    # ``skipped_weapons`` — de gate negeert ze voor deze cyclus.
    active_weapons: List[str]
    skipped_weapons: List[str]
    decision: str          # PROMOTE | ROLLBACK | NEUTRAL | INSUFFICIENT
    promote_streak: int
    rollback_combat_streak: int
    rollback_aim_streak: int
    rollback_movement_streak: int
    reason: str
    # Aggregate min/max over active_weapons — gerapporteerd voor de bestaande
    # logging-format DUAL_KPI_DELTA_* zodat downstream tooling blijft werken.
    min_combat_ratio: float
    min_aim_ratio: float
    min_movement_ratio: float


class DualKPIDeltaGate:
    """Stateful per-cycle decision-engine voor het joint VR+shooting model.

    Houdt promote/rollback streaks bij over opeenvolgende eval-cycli. Geen
    file-IO of ONNX-management — de bovenliggende SAC training_loop bedient
    baseline-snapshots zelf (zoals bestaande DeltaGate dat doet voor single
    KPI). De gate beslist alleen.

    Decisions:
    - **PROMOTE**: combat_ratio ≥ promote_combat_score_min_ratio EN
      aim_ratio ≥ promote_aim_min_ratio EN
      movement_ratio ≥ promote_movement_min_ratio voor ``promote_window_cycles``
      opeenvolgende cycli. Streak reset bij PROMOTE.
    - **ROLLBACK**: combat_ratio < rollback_combat_score_max_ratio OF
      aim_ratio < rollback_aim_max_ratio OF
      movement_ratio < rollback_movement_max_ratio voor ``rollback_window_cycles``
      opeenvolgende cycli van dezelfde KPI. Beide streaks reset bij ROLLBACK.
    - **INSUFFICIENT**: een baseline is None of 0; geen ratio te berekenen.
    - **NEUTRAL**: geen van bovenstaande.
    """

    def __init__(self,
                 cfg: DualKPIDeltaGateConfig,
                 baseline_combat_score: float | None,
                 baseline_aim_rate: float | None,
                 baseline_movement_score: float | None = None,
                 baseline_per_weapon: Optional[Dict[str, Dict[str, float]]] = None,
                 logger=None):
        self.cfg = cfg
        self.baseline_combat = baseline_combat_score
        self.baseline_aim = baseline_aim_rate
        self.baseline_movement = baseline_movement_score
        # Per-weapon baselines: {weapon_key: {"combat_score": X, "aim_rate": Y,
        # "movement_score": Z}}. Wanneer gegeven activeert evaluate_per_weapon()
        # de AND-over-wapens promotion. De aggregate baselines blijven beschikbaar
        # voor evaluate() (backward-compat single-weapon mode).
        self.baseline_per_weapon: Dict[str, Dict[str, float]] = baseline_per_weapon or {}
        self.logger = logger
        self.promote_streak = 0
        self.rollback_combat_streak = 0
        self.rollback_aim_streak = 0
        self.rollback_movement_streak = 0
        # Aantal opeenvolgende ROLLBACK-decisions sinds laatste PROMOTE (of
        # init). Gereset op PROMOTE en op Adam-wipe escalation door de
        # training_loop. De gate zelf wist de Adam state niet — die action
        # leeft in run_sac() omdat de optimizer reference daar zit. De gate
        # exposeert alleen de teller via een attribuut.
        self.consecutive_rollback_count = 0
        self.last_result: DualKPIEvalResult | DualKPIPerWeaponEvalResult | None = None

    def update_baselines(self, baseline_combat_score: float,
                          baseline_aim_rate: float,
                          baseline_movement_score: float | None = None,
                          baseline_per_weapon: Optional[Dict[str, Dict[str, float]]] = None) -> None:
        """Reset baselines (after PROMOTE) and clear all streaks.

        Wanneer ``baseline_per_weapon`` gegeven is, vervangt het de per-weapon
        baselines. Aggregate baselines worden onafhankelijk geüpdatet — zodat
        beide modes (per-weapon en aggregate) consistent meegroeien.
        """
        self.baseline_combat = baseline_combat_score
        self.baseline_aim = baseline_aim_rate
        self.baseline_movement = baseline_movement_score
        if baseline_per_weapon is not None:
            self.baseline_per_weapon = baseline_per_weapon
        self.promote_streak = 0
        self.rollback_combat_streak = 0
        self.rollback_aim_streak = 0
        self.rollback_movement_streak = 0
        self.consecutive_rollback_count = 0

    def has_per_weapon_baselines(self) -> bool:
        """True wanneer per-weapon mode beschikbaar is — training_loop kiest dan
        evaluate_per_weapon() boven de aggregate evaluate()."""
        return bool(self.baseline_per_weapon)

    def _log(self, msg: str) -> None:
        if self.logger is not None:
            self.logger.info(msg)
        else:
            print(msg, flush=True)

    def evaluate(self, current_combat_score: float,
                  current_aim_rate: float,
                  current_movement_score: float | None = None) -> DualKPIEvalResult:
        """Evaluate one eval-cyclus en returns een ``DualKPIEvalResult``.

        Beide KPI's worden t.o.v. baseline geëvalueerd. PROMOTE is een
        AND-conditie; ROLLBACK is een OR-conditie waarbij elke KPI zijn
        eigen streak heeft (cross-skill regressie wordt onafhankelijk
        gedetecteerd).
        """
        movement_required = bool(self.cfg.kpi_movement)
        movement_missing = (
            movement_required
            and (self.baseline_movement is None
                 or self.baseline_movement == 0
                 or current_movement_score is None)
        )
        if (self.baseline_combat is None or self.baseline_aim is None
                or self.baseline_combat == 0 or self.baseline_aim == 0
                or movement_missing):
            res = DualKPIEvalResult(
                combat_score=current_combat_score,
                aim_rate=current_aim_rate,
                movement_score=0.0 if current_movement_score is None else current_movement_score,
                combat_ratio=0.0,
                aim_ratio=0.0,
                movement_ratio=0.0,
                decision="INSUFFICIENT",
                promote_streak=self.promote_streak,
                rollback_combat_streak=self.rollback_combat_streak,
                rollback_aim_streak=self.rollback_aim_streak,
                rollback_movement_streak=self.rollback_movement_streak,
                reason="baseline_combat, baseline_aim, or baseline_movement is None / zero",
            )
            self.last_result = res
            self._log(f"DUAL_KPI_DELTA_INSUFFICIENT: baseline_combat={self.baseline_combat} "
                      f"baseline_aim={self.baseline_aim} "
                      f"baseline_movement={self.baseline_movement}")
            return res

        combat_ratio = current_combat_score / self.baseline_combat
        aim_ratio = current_aim_rate / self.baseline_aim
        movement_score = 0.0 if current_movement_score is None else current_movement_score
        movement_ratio = (
            movement_score / self.baseline_movement
            if movement_required and self.baseline_movement else 1.0
        )

        promote_ready = (
            combat_ratio >= self.cfg.promote_combat_score_min_ratio
            and aim_ratio >= self.cfg.promote_aim_min_ratio
            and movement_ratio >= self.cfg.promote_movement_min_ratio
        )
        combat_violation = combat_ratio < self.cfg.rollback_combat_score_max_ratio
        aim_violation = aim_ratio < self.cfg.rollback_aim_max_ratio
        movement_violation = movement_ratio < self.cfg.rollback_movement_max_ratio

        if promote_ready:
            self.promote_streak += 1
            # Reset rollback streaks bij promote-ready cyclus: dit is geen
            # regressie meer, zelfs als één KPI eerder onder ratio zat.
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
        else:
            self.promote_streak = 0
            # Per-KPI streak: alleen incrementeren wanneer die specifieke
            # KPI de rollback-drempel overtreedt. OR-rollback grijpt zodra
            # één van beide streaks groot genoeg is — credit-assignment
            # failure (sectie 7.5 trigger A) zichtbaar in één KPI is
            # voldoende.
            self.rollback_combat_streak = (self.rollback_combat_streak + 1
                                            if combat_violation else 0)
            self.rollback_aim_streak = (self.rollback_aim_streak + 1
                                         if aim_violation else 0)
            self.rollback_movement_streak = (self.rollback_movement_streak + 1
                                             if movement_violation else 0)

        decision = "NEUTRAL"
        reason = "within margins"
        if self.promote_streak >= self.cfg.promote_window_cycles:
            decision = "PROMOTE"
            reason = (
                f"ALL ratios ≥ thresholds for {self.promote_streak} cycles "
                f"(combat {combat_ratio:.3f} ≥ {self.cfg.promote_combat_score_min_ratio}, "
                f"aim {aim_ratio:.3f} ≥ {self.cfg.promote_aim_min_ratio}, "
                f"movement {movement_ratio:.3f} ≥ {self.cfg.promote_movement_min_ratio})"
            )
            self.promote_streak = 0
            self.consecutive_rollback_count = 0  # PROMOTE broke the drift ratchet
        elif self.rollback_combat_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            reason = (
                f"combat_score ratio {combat_ratio:.3f} < "
                f"{self.cfg.rollback_combat_score_max_ratio} for "
                f"{self.rollback_combat_streak} cycles"
            )
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1
        elif self.rollback_aim_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            reason = (
                f"aim_rate ratio {aim_ratio:.3f} < "
                f"{self.cfg.rollback_aim_max_ratio} for "
                f"{self.rollback_aim_streak} cycles"
            )
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1
        elif self.rollback_movement_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            reason = (
                f"movement_flag_score ratio {movement_ratio:.3f} < "
                f"{self.cfg.rollback_movement_max_ratio} for "
                f"{self.rollback_movement_streak} cycles"
            )
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1

        res = DualKPIEvalResult(
            combat_score=current_combat_score,
            aim_rate=current_aim_rate,
            movement_score=movement_score,
            combat_ratio=combat_ratio,
            aim_ratio=aim_ratio,
            movement_ratio=movement_ratio,
            decision=decision,
            promote_streak=self.promote_streak,
            rollback_combat_streak=self.rollback_combat_streak,
            rollback_aim_streak=self.rollback_aim_streak,
            rollback_movement_streak=self.rollback_movement_streak,
            reason=reason,
        )
        self.last_result = res
        self._log(
            f"DUAL_KPI_DELTA_{decision}: combat_ratio={combat_ratio:+.3f} "
            f"aim_ratio={aim_ratio:+.3f} movement_ratio={movement_ratio:+.3f} "
            f"promote_streak={self.promote_streak}/"
            f"{self.cfg.promote_window_cycles} "
            f"rb_combat={self.rollback_combat_streak}/{self.cfg.rollback_window_cycles} "
            f"rb_aim={self.rollback_aim_streak}/{self.cfg.rollback_window_cycles} "
            f"rb_movement={self.rollback_movement_streak}/{self.cfg.rollback_window_cycles} "
            f"({reason})"
        )
        return res

    def evaluate_per_weapon(
        self,
        current_per_weapon: Dict[str, Dict[str, float]],
    ) -> DualKPIPerWeaponEvalResult:
        """Per-weapon evaluation met AND-promote en OR-rollback over actieve wapens.

        Reduceert per-weapon ratios naar scalars via min() (voor promote-check) en
        any() (voor rollback-check), zodat de bestaande streak-structuur intact
        blijft. Het wapen met de slechtste ratio bepaalt elke beslissing — een
        regressie op één wapen blokkeert promotion, ook als andere wapens
        boven baseline blijven.

        Args:
            current_per_weapon: {weapon_key: {"combat_score": float,
                "aim_rate": float, "movement_score": float}}. Caller filtert
                vooraf op sample-significance per weapon (anders pollueren
                low-sample wapens de gate).
        """
        if not self.baseline_per_weapon:
            raise RuntimeError(
                "evaluate_per_weapon called but baseline_per_weapon is empty. "
                "Use evaluate() for aggregate mode or set baseline_per_weapon "
                "via constructor / update_baselines()."
            )

        baseline_weapons = set(self.baseline_per_weapon.keys())
        current_weapons = set(current_per_weapon.keys())
        active_weapons = sorted(baseline_weapons & current_weapons)
        skipped_weapons = sorted((baseline_weapons | current_weapons) - set(active_weapons))

        if not active_weapons:
            res = DualKPIPerWeaponEvalResult(
                per_weapon={}, active_weapons=[], skipped_weapons=skipped_weapons,
                decision="INSUFFICIENT", promote_streak=self.promote_streak,
                rollback_combat_streak=self.rollback_combat_streak,
                rollback_aim_streak=self.rollback_aim_streak,
                rollback_movement_streak=self.rollback_movement_streak,
                reason=(f"no overlap between baseline weapons "
                        f"{sorted(baseline_weapons)} and current weapons "
                        f"{sorted(current_weapons)}"),
                min_combat_ratio=0.0, min_aim_ratio=0.0, min_movement_ratio=0.0,
            )
            self.last_result = res
            self._log(f"DUAL_KPI_DELTA_INSUFFICIENT: {res.reason}")
            return res

        per_weapon: Dict[str, PerWeaponRatios] = {}
        for weapon in active_weapons:
            bl = self.baseline_per_weapon[weapon]
            cur = current_per_weapon[weapon]
            bl_combat = float(bl.get("combat_score", 0.0))
            bl_aim = float(bl.get("aim_rate", 0.0))
            bl_movement = float(bl.get("movement_score", 0.0))
            cur_combat = float(cur.get("combat_score", 0.0))
            cur_aim = float(cur.get("aim_rate", 0.0))
            cur_movement = float(cur.get("movement_score", 0.0))

            combat_ratio = cur_combat / bl_combat if bl_combat else 0.0
            aim_ratio = cur_aim / bl_aim if bl_aim else 0.0
            movement_ratio = cur_movement / bl_movement if bl_movement else 0.0

            per_weapon[weapon] = PerWeaponRatios(
                weapon=weapon, combat_ratio=combat_ratio,
                aim_ratio=aim_ratio, movement_ratio=movement_ratio,
                current_combat=cur_combat, current_aim=cur_aim,
                current_movement=cur_movement,
                baseline_combat=bl_combat, baseline_aim=bl_aim,
                baseline_movement=bl_movement,
            )

        min_combat_ratio = min(w.combat_ratio for w in per_weapon.values())
        min_aim_ratio = min(w.aim_ratio for w in per_weapon.values())
        min_movement_ratio = min(w.movement_ratio for w in per_weapon.values())

        promote_ready = (
            min_combat_ratio >= self.cfg.promote_combat_score_min_ratio
            and min_aim_ratio >= self.cfg.promote_aim_min_ratio
            and min_movement_ratio >= self.cfg.promote_movement_min_ratio
        )
        combat_violation = any(w.combat_ratio < self.cfg.rollback_combat_score_max_ratio
                                for w in per_weapon.values())
        aim_violation = any(w.aim_ratio < self.cfg.rollback_aim_max_ratio
                             for w in per_weapon.values())
        movement_violation = any(w.movement_ratio < self.cfg.rollback_movement_max_ratio
                                  for w in per_weapon.values())

        if promote_ready:
            self.promote_streak += 1
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
        else:
            self.promote_streak = 0
            self.rollback_combat_streak = (self.rollback_combat_streak + 1
                                            if combat_violation else 0)
            self.rollback_aim_streak = (self.rollback_aim_streak + 1
                                         if aim_violation else 0)
            self.rollback_movement_streak = (self.rollback_movement_streak + 1
                                              if movement_violation else 0)

        decision = "NEUTRAL"
        reason = "within margins"
        if self.promote_streak >= self.cfg.promote_window_cycles:
            decision = "PROMOTE"
            worst_combat = min(per_weapon, key=lambda w: per_weapon[w].combat_ratio)
            worst_aim = min(per_weapon, key=lambda w: per_weapon[w].aim_ratio)
            worst_movement = min(per_weapon, key=lambda w: per_weapon[w].movement_ratio)
            reason = (
                f"ALL weapons ≥ thresholds for {self.promote_streak} cycles "
                f"(worst combat: {worst_combat}={min_combat_ratio:.3f}, "
                f"worst aim: {worst_aim}={min_aim_ratio:.3f}, "
                f"worst movement: {worst_movement}={min_movement_ratio:.3f})"
            )
            self.promote_streak = 0
            self.consecutive_rollback_count = 0
        elif self.rollback_combat_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            worst = min(per_weapon, key=lambda w: per_weapon[w].combat_ratio)
            reason = (f"combat_score {worst}={per_weapon[worst].combat_ratio:.3f} < "
                      f"{self.cfg.rollback_combat_score_max_ratio} for "
                      f"{self.rollback_combat_streak} cycles")
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1
        elif self.rollback_aim_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            worst = min(per_weapon, key=lambda w: per_weapon[w].aim_ratio)
            reason = (f"aim_rate {worst}={per_weapon[worst].aim_ratio:.3f} < "
                      f"{self.cfg.rollback_aim_max_ratio} for "
                      f"{self.rollback_aim_streak} cycles")
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1
        elif self.rollback_movement_streak >= self.cfg.rollback_window_cycles:
            decision = "ROLLBACK"
            worst = min(per_weapon, key=lambda w: per_weapon[w].movement_ratio)
            reason = (f"movement_flag_score {worst}={per_weapon[worst].movement_ratio:.3f} < "
                      f"{self.cfg.rollback_movement_max_ratio} for "
                      f"{self.rollback_movement_streak} cycles")
            self.rollback_combat_streak = 0
            self.rollback_aim_streak = 0
            self.rollback_movement_streak = 0
            self.consecutive_rollback_count += 1

        res = DualKPIPerWeaponEvalResult(
            per_weapon=per_weapon, active_weapons=active_weapons,
            skipped_weapons=skipped_weapons, decision=decision,
            promote_streak=self.promote_streak,
            rollback_combat_streak=self.rollback_combat_streak,
            rollback_aim_streak=self.rollback_aim_streak,
            rollback_movement_streak=self.rollback_movement_streak,
            reason=reason,
            min_combat_ratio=min_combat_ratio,
            min_aim_ratio=min_aim_ratio,
            min_movement_ratio=min_movement_ratio,
        )
        self.last_result = res

        # Aggregate-line in bestaand DUAL_KPI_DELTA_* format zodat downstream
        # grep-tooling (zoals user's evaluation scripts) blijft werken. De
        # min-ratios beslissen de gate; per-weapon detail volgt eronder.
        self._log(
            f"DUAL_KPI_DELTA_{decision}: combat_ratio={min_combat_ratio:+.3f} "
            f"aim_ratio={min_aim_ratio:+.3f} movement_ratio={min_movement_ratio:+.3f} "
            f"promote_streak={self.promote_streak}/"
            f"{self.cfg.promote_window_cycles} "
            f"rb_combat={self.rollback_combat_streak}/{self.cfg.rollback_window_cycles} "
            f"rb_aim={self.rollback_aim_streak}/{self.cfg.rollback_window_cycles} "
            f"rb_movement={self.rollback_movement_streak}/{self.cfg.rollback_window_cycles} "
            f"active_weapons={active_weapons} "
            f"({reason})"
        )
        for weapon, ratios in per_weapon.items():
            self._log(
                f"DUAL_KPI_PER_WEAPON[{weapon}]: "
                f"combat={ratios.current_combat:.3f}/{ratios.baseline_combat:.3f}"
                f"={ratios.combat_ratio:+.3f} "
                f"aim={ratios.current_aim:.3f}/{ratios.baseline_aim:.3f}"
                f"={ratios.aim_ratio:+.3f} "
                f"movement={ratios.current_movement:.3f}/{ratios.baseline_movement:.3f}"
                f"={ratios.movement_ratio:+.3f}"
            )
        if skipped_weapons:
            self._log(f"DUAL_KPI_PER_WEAPON_SKIPPED: {skipped_weapons} "
                      f"(no current data or no baseline)")
        return res
