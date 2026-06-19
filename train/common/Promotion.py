"""Model candidate registration, evaluation gates, and promotion authority.

Candidates are training outputs that must pass gates before being promoted
to active runtime bindings. This module ensures that training never
implicitly pushes models to live — only promoted candidates are synced.

Gate configuration is read from /resources/models/{model_key}/promotion.json.
If no promotion config exists, all gates pass (backward compatible).
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field, asdict
from enum import Enum
from pathlib import Path
from typing import Optional

from train.common import PropertyReader
from train.common.SessionPaths import get_session_paths

_LOG = logging.getLogger(__name__)


# ── Candidate lifecycle ──

class CandidateStatus(str, Enum):
    BUILT = "built"
    EVALUATING = "evaluating"
    REJECTED = "rejected"
    PROMOTED = "promoted"
    SUPERSEDED = "superseded"


@dataclass
class CandidateRecord:
    model_key: str
    role: str
    step: int
    val_loss: float
    timestamp: float = field(default_factory=time.time)
    status: CandidateStatus = CandidateStatus.BUILT
    gate_results: dict = field(default_factory=dict)
    promotion_timestamp: Optional[float] = None
    experiment_id: Optional[str] = None
    variant_id: Optional[str] = None
    run_id: Optional[str] = None


@dataclass
class PromotionConfig:
    """Per-model gate thresholds. Loaded from promotion.json."""
    enabled: bool
    max_val_loss: float
    max_regression_vs_baseline: float


# ── Config loading ──

def _load_promotion_config(model_key: str) -> PromotionConfig:
    """Load promotion.json for this model. Crashes if the file or any key is missing."""
    root = PropertyReader._root()
    if "models" not in root:
        raise ValueError("PropertyReader root: missing required key 'models'")
    if model_key not in root["models"]:
        raise ValueError(f"Model '{model_key}' not found in index.json")
    model = root["models"][model_key]
    if "promotion" not in model:
        raise ValueError(f"Model '{model_key}': missing promotion.json")
    promo = model["promotion"]
    for key in ("enabled", "max_val_loss", "max_regression_vs_baseline"):
        if key not in promo:
            raise ValueError(f"Model '{model_key}' promotion.json: missing required key '{key}'")
    return PromotionConfig(
        enabled=bool(promo["enabled"]),
        max_val_loss=float(promo["max_val_loss"]),
        max_regression_vs_baseline=float(promo["max_regression_vs_baseline"]),
    )


# ── Candidate registry (filesystem JSON) ──

def _candidates_dir(model_key: str) -> Path:
    sp = get_session_paths(create_dirs=False)
    d = Path(sp.sessions_base_dir) / "candidates" / model_key
    d.mkdir(parents=True, exist_ok=True)
    return d


def _active_bindings_path() -> Path:
    sp = get_session_paths(create_dirs=False)
    return Path(sp.sessions_base_dir) / "active_bindings.json"


def _load_active_bindings() -> dict:
    p = _active_bindings_path()
    if p.exists():
        try:
            with open(p) as f:
                return json.load(f)
        except (json.JSONDecodeError, ValueError):
            # Corrupted file (e.g. from parallel write race) — start fresh
            return {}
    return {}


def register_candidate(model_key: str, role: str, step: int, val_loss: float,
    experiment_id: Optional[str] = None,
    variant_id: Optional[str] = None,
    run_id: Optional[str] = None) -> CandidateRecord:
    """Register a new candidate. Writes metadata JSON atomically to candidates dir.

    All optional lineage fields (experiment_id, variant_id, run_id) are
    persisted in the initial write to prevent incomplete metadata on crash.
    """
    record = CandidateRecord(
        model_key=model_key,
        role=role,
        step=step,
        val_loss=val_loss,
        experiment_id=experiment_id,
        variant_id=variant_id,
        run_id=run_id,
    )
    _save_candidate(record)
    _LOG.info(f"CANDIDATE registered: {model_key} role={role} step={step} val_loss={val_loss:.4f}"
              f"{f' variant={variant_id}' if variant_id else ''}"
              f"{f' run={run_id}' if run_id else ''}")
    return record


def _save_candidate(record: CandidateRecord) -> None:
    path = _candidates_dir(record.model_key) / f"step-{record.step}.json"
    with open(path, "w") as f:
        json.dump(asdict(record), f, indent=2, default=str)


# ── Gates ──

def _artifact_gate(onnx_path: str, record: CandidateRecord) -> bool:
    """Check that the ONNX artifact exists and has nonzero size."""
    p = Path(onnx_path)
    data_p = Path(onnx_path + ".data")
    ok = p.exists() and p.stat().st_size > 0 and data_p.exists() and data_p.stat().st_size > 0
    record.gate_results["artifact"] = "pass" if ok else "fail"
    if not ok:
        _LOG.warning(f"GATE artifact FAILED: {onnx_path} (exists={p.exists()}, data_exists={data_p.exists()})")
    return ok


def _threshold_gate(record: CandidateRecord, config: PromotionConfig) -> bool:
    """Check that val_loss is below the configured threshold."""
    ok = record.val_loss <= config.max_val_loss
    record.gate_results["threshold"] = "pass" if ok else f"fail (val_loss={record.val_loss:.4f} > max={config.max_val_loss:.4f})"
    if not ok:
        _LOG.warning(f"GATE threshold FAILED: val_loss={record.val_loss:.4f} > max={config.max_val_loss:.4f}")
    return ok


def _baseline_gate(record: CandidateRecord, config: PromotionConfig) -> bool:
    """Check that candidate is not worse than the currently promoted model."""
    bindings = _load_active_bindings()
    current = bindings.get(record.role)
    if current is None:
        record.gate_results["baseline"] = "pass (no baseline)"
        return True

    baseline_loss = current.get("val_loss")
    if baseline_loss is None:
        record.gate_results["baseline"] = "pass (baseline has no val_loss)"
        return True

    regression = record.val_loss - baseline_loss
    ok = regression <= config.max_regression_vs_baseline
    record.gate_results["baseline"] = (
        "pass" if ok else f"fail (regression={regression:.4f} > max={config.max_regression_vs_baseline:.4f})"
    )
    if not ok:
        _LOG.warning(f"GATE baseline FAILED: regression={regression:.4f} vs baseline_loss={baseline_loss:.4f}")
    return ok


# ── Promotion authority ──

def evaluate_and_promote(record: CandidateRecord, onnx_path: str) -> bool:
    """Run all gates on a candidate. If all pass, promote it.

    Returns True if candidate was promoted (sync should proceed).
    Returns False if rejected (sync should NOT happen).

    If promotion is not enabled in config, always returns True (backward compatible).
    """
    config = _load_promotion_config(record.model_key)

    if not config.enabled:
        record.status = CandidateStatus.PROMOTED
        record.promotion_timestamp = time.time()
        record.gate_results["promotion_enabled"] = "false (auto-promote)"
        _save_candidate(record)
        _update_active_binding(record)
        return True

    record.status = CandidateStatus.EVALUATING
    _save_candidate(record)

    gates_passed = (
        _artifact_gate(onnx_path, record)
        and _threshold_gate(record, config)
        and _baseline_gate(record, config)
    )

    if gates_passed:
        record.status = CandidateStatus.PROMOTED
        record.promotion_timestamp = time.time()
        _save_candidate(record)
        _update_active_binding(record)
        _LOG.info(f"PROMOTED: {record.model_key} step={record.step} val_loss={record.val_loss:.4f}")
        return True
    else:
        record.status = CandidateStatus.REJECTED
        _save_candidate(record)
        _LOG.warning(f"REJECTED: {record.model_key} step={record.step} gates={record.gate_results}")
        return False


def _update_active_binding(record: CandidateRecord) -> None:
    """Update active_bindings.json: supersede previous, install new.
    Uses file lock to prevent corruption from parallel trainers."""
    import fcntl
    p = _active_bindings_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    lock_path = p.with_suffix(".lock")
    with open(lock_path, "w") as lock_f:
        fcntl.flock(lock_f, fcntl.LOCK_EX)
        try:
            # Read under lock
            bindings = {}
            if p.exists():
                try:
                    with open(p) as f:
                        bindings = json.load(f)
                except (json.JSONDecodeError, ValueError):
                    bindings = {}

            # Capture shallow previous (only one level, no chain)
            previous = bindings.get(record.role)
            prev_summary = None
            if previous is not None:
                prev_summary = {
                    "model_key": previous.get("model_key"),
                    "step": previous.get("step"),
                    "val_loss": previous.get("val_loss"),
                    "status": CandidateStatus.SUPERSEDED.value,
                    "promoted_at": previous.get("promoted_at"),
                }

            # Install new
            bindings[record.role] = {
                "model_key": record.model_key,
                "step": record.step,
                "val_loss": record.val_loss,
                "status": CandidateStatus.PROMOTED.value,
                "promoted_at": record.promotion_timestamp,
                "previous": prev_summary,
            }

            # Write under lock
            tmp = p.with_suffix(".tmp")
            with open(tmp, "w") as f:
                json.dump(bindings, f, indent=2)
            tmp.rename(p)
        finally:
            fcntl.flock(lock_f, fcntl.LOCK_UN)
