"""Joint VR+shooting export validation — PyTorch FP32 + ONNX FP16 probe gates.

Each export tick runs two sanity probes back-to-back on the same sampled
replay window:

1. **PyTorch FP32 actor** — catches gross divergence (NaN/Inf, saturation,
   collapsed fire-rate, bias drift) before any conversion overhead.
2. **Exported ONNX FP16 internals** — catches numerical regimes that pass
   FP32 but break under FP16 quantisation (a problem the decoupled VR/movement
   trainers have explicitly hit: a near-saturated FP32 policy turns into a
   fully saturated FP16 ONNX on the same weights).

Both probes share the per-head + cross-head metric implementation in
``probes.py``; the two backends differ only in how they compute raw actor
outputs (torch.no_grad forward vs onnxruntime CPU session).

When the ONNX probe fails, ``rollback_onnx`` overwrites the just-exported file
with the immutable BC baseline so the live bots stop swinging immediately
while the trainer keeps iterating in-process.
"""
from __future__ import annotations

import shutil
from pathlib import Path
from typing import Optional

import numpy as np
import torch

from train.common.TrainerLogger import log_print
from train.rl.rl_pawn.trainSAC.probes import (
    JointFeatureIndices, ProbeReport,
    evaluate_probes_from_raw, evaluate_probes_pytorch, format_report,
)
from train.rl.rl_pawn.trainSAC.strata import (
    DEFAULT_SAMPLES_PER_STRATUM, StratificationFeatureIndices,
    classify, format_per_stratum_counts, sample_stratified,
)


def sample_replay_pair(replay, cfg, samples: int,
                       stratify_indices: Optional[StratificationFeatureIndices] = None,
                       per_stratum: int = DEFAULT_SAMPLES_PER_STRATUM,
                       logger=None,
                       global_step: int = 0,
                       ) -> Optional[tuple[np.ndarray, np.ndarray]]:
    """Sample (states, next_states) pairs from replay for the temporal probe.

    Fase 2.5 CTDE: when ``cfg.ctde_mode != "off"`` we sanity-check that the
    replay buffer is wired with a matching ``teammate_state_dim``. The probe
    itself only forwards the actor (self-state only) so we don't need to
    return teammate slices here, but a mismatch is a wiring bug that should
    crash early instead of silently masking critic CTDE state.

    Fase 4b Deel D: stratified sampling — overrides het v1 uniform pad. We
    oversample 5× ``samples`` uniform, classifyen op last-frame features, en
    kiezen ≤ ``per_stratum`` per stratum (5 × 64 = 320 standaard). Wanneer
    ``stratify_indices`` None is valt het terug op uniform sampling — een
    fallback voor smoke-tests / vroege training waar features.json mappings
    nog niet bekend zijn.

    Per-stratum counts worden gelogd onder ``RL_PROBE_STRATIFIED_<step>`` zodat
    diagnose mogelijk is bij sat_limit failures (welk stratum trigerde?).

    Mirrors the VR pattern: callers must use the SAME pair for both
    PyTorch FP32 and ONNX FP16 probes — sample variance can flip a passing
    FP32 probe into a failing ONNX probe on the same model.
    """
    if replay is None or replay.size < samples:
        return None
    if cfg.ctde_mode != "off":
        if not getattr(replay, "ctde_enabled", False):
            raise RuntimeError(
                f"cfg.ctde_mode={cfg.ctde_mode!r} but replay buffer has no "
                f"teammate_state slice — wiring mismatch (training_loop must "
                f"build ReplayBuffer with teammate_state_dim>0)"
            )
        if replay.teammate_state_dim != cfg.teammate_state_dim:
            raise RuntimeError(
                f"cfg.teammate_state_dim={cfg.teammate_state_dim} does not match "
                f"replay.teammate_state_dim={replay.teammate_state_dim}"
            )
    if stratify_indices is None:
        # Backwards-compat / smoke: uniform sampling.
        s, _, _, ns, _ = replay.sample(samples)
        s_r = s.reshape(samples, cfg.seq_len, cfg.input_size).astype(np.float32)
        ns_r = ns.reshape(samples, cfg.seq_len, cfg.input_size).astype(np.float32)
        return s_r, ns_r

    # Oversample uniform; daarna stratify + sample. Factor 5 sluit aan op
    # 5 strata × per_stratum. Cap door replay.size zodat we niet meer vragen
    # dan beschikbaar.
    oversample = min(replay.size, max(samples, 5 * per_stratum))
    s_over, _, _, ns_over, _ = replay.sample(oversample)
    s_over = s_over.reshape(oversample, cfg.seq_len, cfg.input_size).astype(np.float32)
    ns_over = ns_over.reshape(oversample, cfg.seq_len, cfg.input_size).astype(np.float32)

    last_frames = s_over[:, -1, :]
    masks = classify(last_frames, stratify_indices)
    indices, counts = sample_stratified(masks, per_stratum)
    if logger is not None:
        log_print(logger,
            f"RL_PROBE_STRATIFIED step={global_step} | "
            f"{format_per_stratum_counts(counts, per_stratum)}")
    if indices.size == 0:
        # Geen samples in ANY stratum (alle features missen + lege default
        # mask is onmogelijk → default is altijd vol). Defensief fallback
        # naar uniform.
        return s_over[:samples], ns_over[:samples]
    return s_over[indices], ns_over[indices]


def validate_actor_sane(
    *,
    actor,
    bc_actor,
    cfg,
    device: torch.device,
    logger,
    global_step: int,
    probe_cfg,
    replay_pair: Optional[tuple[np.ndarray, np.ndarray]],
    feature_idx: JointFeatureIndices,
) -> tuple[bool, ProbeReport]:
    """Run per-head + cross-head probes on the PyTorch FP32 actor.

    Returns ``(passed, report)``. ``passed`` is the immediate-rollback bool:
    ``False`` if either any non-skipped metric failed OR fire+altFire both
    collapsed. The training loop additionally feeds ``report`` to a
    ``ProbeViolationTracker`` that applies the 2-cycle filter.

    When ``replay_pair`` is None (buffer too small), a synthetic Gaussian
    sample is used so the probe still catches catastrophic failures during
    early-training warmup; cross-head probes self-skip because the synthetic
    states don't carry meaningful enemy bearings.
    """
    if replay_pair is None:
        rng = np.random.default_rng(0xA1A1A1)
        synth = rng.standard_normal(
            (probe_cfg.samples_per_probe, cfg.seq_len, cfg.input_size),
            dtype=np.float32,
        ).clip(-3.0, 3.0)
        states_np = synth
        next_np = rng.standard_normal(
            (probe_cfg.samples_per_probe, cfg.seq_len, cfg.input_size),
            dtype=np.float32,
        ).clip(-3.0, 3.0)
        label = "PYTORCH_FP32_SYNTH"
    else:
        states_np, next_np = replay_pair
        label = "PYTORCH_FP32"

    states_t = torch.from_numpy(states_np).to(device)
    next_t = torch.from_numpy(next_np).to(device)

    try:
        report = evaluate_probes_pytorch(
            actor, bc_actor, states_t, next_t, feature_idx, probe_cfg,
        )
    except Exception as e:
        log_print(logger,
            f"VALIDATE_FAIL[{label}] step={global_step}: probe raised "
            f"{type(e).__name__}: {e}")
        return False, ProbeReport()

    passed = (not report.any_failed) and (not report.collapse_both_dims)
    flag = "OK" if passed else "FAIL"
    log_print(logger,
        f"VALIDATE_{flag}[{label}] step={global_step}: {format_report(report)}")
    return passed, report


def _onnx_forward_with_target(sess, states_np: np.ndarray):
    """Run an ONNX session that outputs (action_logits, target_logits).

    BCSequenceNetwork.export_actor_onnx writes a multi-output ONNX when the
    TargetHead is present; the joint actor always has it. If the model has
    only one output, we return ``(action, None)`` so the caller can fail the
    export before it reaches Java's strict runtime decoder.
    """
    in_name = sess.get_inputs()[0].name
    outputs = sess.run(None, {in_name: states_np})
    if len(outputs) == 1:
        return np.asarray(outputs[0]), None
    return np.asarray(outputs[0]), np.asarray(outputs[1])


def validate_exported_onnx_sane(
    *,
    onnx_path: str,
    bc_actor,
    cfg,
    device: torch.device,
    logger,
    global_step: int,
    probe_cfg,
    replay_pair: Optional[tuple[np.ndarray, np.ndarray]],
    feature_idx: JointFeatureIndices,
) -> tuple[bool, ProbeReport]:
    """Run the same probe schema as ``validate_actor_sane`` but against the
    exported ONNX (FP16 internals). Returns ``(passed, report)``.

    BC actor outputs are still computed in PyTorch (no separate BC ONNX to
    run) — this is consistent with the VR pattern and gives the FP16 actor a
    PyTorch FP32 reference. The downside is BC vs ONNX numerical-regime
    asymmetry; for the joint model this is acceptable v1 because BC is FP32
    by definition (only the SAC actor goes through FP16 conversion at export).
    """
    if replay_pair is None:
        return True, ProbeReport()  # nothing to compare against

    try:
        import onnxruntime as ort
    except ImportError as e:
        log_print(logger,
            f"VALIDATE_SKIP[ONNX_FP16] step={global_step}: onnxruntime not "
            f"installed ({e}); treating as passed.")
        return True, ProbeReport()

    states_np, next_np = replay_pair
    try:
        sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        raw_action, target_logits = _onnx_forward_with_target(sess, states_np)
        raw_action_next, _ = _onnx_forward_with_target(sess, next_np)
        del sess
    except Exception as e:
        log_print(logger,
            f"VALIDATE_FAIL[ONNX_FP16] step={global_step}: ORT raised "
            f"{type(e).__name__}: {e}")
        return False, ProbeReport()

    if not np.all(np.isfinite(raw_action)):
        log_print(logger,
            f"VALIDATE_FAIL[ONNX_FP16] step={global_step}: non-finite "
            f"actions in ONNX output")
        return False, ProbeReport()

    actor_action = np.tanh(raw_action)
    actor_action_next = np.tanh(raw_action_next)
    if target_logits is None:
        log_print(logger,
            f"VALIDATE_FAIL[ONNX_FP16] step={global_step}: exported rl_pawn "
            f"ONNX has no target_logits output. Runtime requires two outputs "
            f"(actions[10] + target_logits[5]); refusing deploy.")
        return False, ProbeReport()
    if target_logits.ndim != 2 or target_logits.shape[1] != 5:
        log_print(logger,
            f"VALIDATE_FAIL[ONNX_FP16] step={global_step}: target_logits "
            f"shape {tuple(target_logits.shape)} != [B,5]")
        return False, ProbeReport()

    bc_action_np = None
    bc_action_next_np = None
    if bc_actor is not None:
        with torch.no_grad():
            bc_actor.eval()
            states_t = torch.from_numpy(states_np).to(device)
            next_t = torch.from_numpy(next_np).to(device)
            bc_action_np = torch.tanh(bc_actor(states_t)).cpu().numpy()
            bc_action_next_np = torch.tanh(bc_actor(next_t)).cpu().numpy()

    last_frame_states = states_np[:, -1, :]
    report = evaluate_probes_from_raw(
        actor_actions=actor_action,
        actor_actions_next=actor_action_next,
        target_logits=target_logits,
        bc_actions=bc_action_np,
        bc_actions_next=bc_action_next_np,
        last_frame_states=last_frame_states,
        feature_idx=feature_idx,
        cfg=probe_cfg,
    )
    passed = (not report.any_failed) and (not report.collapse_both_dims)
    flag = "OK" if passed else "FAIL"
    log_print(logger,
        f"VALIDATE_{flag}[ONNX_FP16] step={global_step}: {format_report(report)}")
    return passed, report


def rollback_onnx(onnx_path: str, fallback_onnx: str, logger, global_step: int) -> bool:
    """Overwrite ``onnx_path`` with ``fallback_onnx`` (and matching .data file).

    Identical to the decoupled VR/movement implementation — kept local for
    symmetry with the rest of the joint trainSAC pipeline. Returns True if
    fallback was applied.
    """
    fb_path = Path(fallback_onnx)
    fb_data = Path(str(fallback_onnx) + ".data")
    if not fb_path.exists():
        log_print(logger, f"ROLLBACK_NO_FALLBACK step={global_step}:"
                  f" fallback {fallback_onnx} missing — leaving onnx untouched")
        return False
    target = Path(onnx_path)
    target_data = Path(str(onnx_path) + ".data")
    try:
        shutil.copy2(str(fb_path), str(target))
        if fb_data.exists():
            shutil.copy2(str(fb_data), str(target_data))
        elif target_data.exists():
            target_data.unlink()
        target.touch()
        if target_data.exists():
            target_data.touch()
        log_print(logger, f"ROLLBACK_OK step={global_step}:"
                  f" {target.name} restored from {fb_path.name}")
        return True
    except Exception as e:
        log_print(logger, f"ROLLBACK_FAIL step={global_step}:"
                  f" {type(e).__name__}: {e}")
        return False
