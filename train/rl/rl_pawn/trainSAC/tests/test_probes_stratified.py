"""Fase 4b Deel D — strata-stratified probe sampling tests.

Dekt :mod:`train.rl.rl_pawn.trainSAC.strata`:

* classifyer maakt correcte boolean masks op last-frame features;
* sample_stratified vult elk stratum tot per_stratum samples wanneer
  beschikbaar; ondervolle strata → warning + minder samples;
* probe-eval roundtrip: build dummy actor, dummy replay, stratified
  sample → ``evaluate_probes_from_raw`` produceert ProbeReport zonder crash.

Geen training_loop / SAC kernel hier — alleen de sampling-laag.
"""
from __future__ import annotations

import logging
from typing import List

import numpy as np

from train.rl.rl_pawn.trainSAC.strata import (
    DEFAULT_SAMPLES_PER_STRATUM,
    STATE_STRATA,
    STRATA,
    STRATA_WEAPONS,
    STRATA_WEAPON_PREFIX,
    STRATUM_CARRIER_ACTIVE,
    STRATUM_COMBAT_ACTIVE,
    STRATUM_DEFAULT,
    STRATUM_NO_COMBAT,
    STRATUM_POST_DAMAGE,
    StratificationFeatureIndices,
    WEAPON_KEYS,
    classify,
    format_per_stratum_counts,
    sample_stratified,
)


def _make_indices(input_features: List[str]) -> StratificationFeatureIndices:
    return StratificationFeatureIndices.from_input_features(input_features)


def _synthetic_features() -> List[str]:
    """Synthetische joint features.json input_features ordering — dekt alle
    classifiers (self_hasFlag, enemy0..4_visible, enemy0..4_aimAlignmentDot,
    recent_damage_taken_2s)."""
    feats = ["self_hasFlag"]
    for i in range(5):
        feats.extend([
            f"enemy{i}_isAlive",
            f"enemy{i}_visible",
            f"enemy{i}_aimAlignmentDot_norm",
            f"enemy{i}_relSin",
            f"enemy{i}_relCos",
        ])
    feats.append("recent_damage_taken_2s")
    # Padding zodat we realistische F > 12 hebben
    feats.extend([f"padding_{i}" for i in range(40)])
    return feats


def test_classify_assigns_correct_strata() -> None:
    """Synthese 4 typen states (combat / no-combat / carrier / post-damage)
    → classifier zet juiste masks."""
    feats = _synthetic_features()
    idx = _make_indices(feats)
    n_features = len(feats)

    last_frames = np.zeros((4, n_features), dtype=np.float32)
    # Sample 0: combat_active (enemy0_visible=1)
    last_frames[0, idx.enemy_visible[0]] = 1.0
    # Sample 1: no_combat (alle visible 0)
    # Sample 2: carrier_active (self_hasFlag=1) + no_combat
    last_frames[2, idx.self_has_flag] = 1.0
    # Sample 3: post_damage (recent_damage_taken_2s > 0) + combat
    last_frames[3, idx.recent_damage_taken_2s] = 0.5
    last_frames[3, idx.enemy_visible[1]] = 1.0

    masks = classify(last_frames, idx)
    assert masks[STRATUM_COMBAT_ACTIVE].tolist() == [True, False, False, True]
    assert masks[STRATUM_NO_COMBAT].tolist() == [False, True, True, False]
    assert masks[STRATUM_CARRIER_ACTIVE].tolist() == [False, False, True, False]
    assert masks[STRATUM_POST_DAMAGE].tolist() == [False, False, False, True]
    assert masks[STRATUM_DEFAULT].tolist() == [True, True, True, True]


def test_sample_stratified_full_strata() -> None:
    """Alle 5 state-strata gevuld met ≥ per_stratum → state-totaal = 5 × per_stratum.

    Weapon-strata (active_weapon_*) zijn afwezig in deze synthetische test
    omdat _synthetic_features() geen self_weapon_is<X> features bevat —
    die strata blijven leeg en worden niet meegeteld in deze assert.
    """
    feats = _synthetic_features()
    idx = _make_indices(feats)
    n_features = len(feats)

    # 5 × 100 = 500 samples, met per-stratum 100 stuks ge-classified als TRUE
    n = 500
    last_frames = np.zeros((n, n_features), dtype=np.float32)
    last_frames[0:100, idx.enemy_visible[0]] = 1.0   # combat_active
    # 100..200 = no_combat (alle visible 0; default OK)
    last_frames[200:300, idx.self_has_flag] = 1.0     # carrier_active
    last_frames[300:400, idx.recent_damage_taken_2s] = 0.5  # post_damage
    # 400..500 = default-only

    masks = classify(last_frames, idx)
    per_stratum = 50
    indices, counts = sample_stratified(
        masks, per_stratum=per_stratum, rng=np.random.default_rng(0xCAFE))

    # State-strata vol → counts[s] == per_stratum, totaal == 5 × per_stratum
    for stratum in STATE_STRATA:
        assert counts[stratum] == per_stratum, \
            f"state-stratum {stratum} count = {counts[stratum]}, verwacht {per_stratum}"
    assert sum(counts[s] for s in STATE_STRATA) == 5 * per_stratum
    # Weapon-strata leeg in deze test (geen self_weapon_is<X> features)
    for stratum in STRATA_WEAPONS:
        assert counts[stratum] == 0, \
            f"weapon-stratum {stratum} verwacht leeg, kreeg {counts[stratum]}"


def test_classify_assigns_weapon_strata() -> None:
    """Synthese met self_weapon_is<X> features → classifier zet weapon-masks
    correct op de feature-positie van de actieve wapen one-hot."""
    feats = ["self_hasFlag"]
    for i in range(5):
        feats.extend([
            f"enemy{i}_isAlive",
            f"enemy{i}_visible",
            f"enemy{i}_aimAlignmentDot_norm",
            f"enemy{i}_relSin",
            f"enemy{i}_relCos",
        ])
    feats.append("recent_damage_taken_2s")
    feats.extend([f"self_weapon_is{w}" for w in WEAPON_KEYS])
    idx = _make_indices(feats)

    n = 4
    n_features = len(feats)
    last_frames = np.zeros((n, n_features), dtype=np.float32)
    # Sample 0: actief PulseGun. Sample 1: actief Eightball.
    # Sample 2: actief DoubleEnforcer. Sample 3: geen wapen.
    pulse_idx = feats.index("self_weapon_isPulseGun")
    eightball_idx = feats.index("self_weapon_isEightball")
    double_enf_idx = feats.index("self_weapon_isDoubleEnforcer")
    last_frames[0, pulse_idx] = 1.0
    last_frames[1, eightball_idx] = 1.0
    last_frames[2, double_enf_idx] = 1.0

    masks = classify(last_frames, idx)
    assert masks[f"{STRATA_WEAPON_PREFIX}PulseGun"].tolist() == [True, False, False, False]
    assert masks[f"{STRATA_WEAPON_PREFIX}Eightball"].tolist() == [False, True, False, False]
    assert masks[f"{STRATA_WEAPON_PREFIX}DoubleEnforcer"].tolist() == [False, False, True, False]
    assert masks[f"{STRATA_WEAPON_PREFIX}Enforcer"].tolist() == [False, False, False, False]
    # Default-stratum vangt alles
    assert masks[STRATUM_DEFAULT].tolist() == [True, True, True, True]


def test_sample_stratified_undervol_logs_warning(caplog) -> None:
    """Eén stratum ondervol → warning + minder samples; geen crash."""
    feats = _synthetic_features()
    idx = _make_indices(feats)
    n_features = len(feats)

    # 200 default-only states (geen enemy visible, geen flag, geen damage)
    last_frames = np.zeros((200, n_features), dtype=np.float32)
    # 10 carrier_active samples → stratum nét niet vol
    last_frames[0:10, idx.self_has_flag] = 1.0

    masks = classify(last_frames, idx)
    per_stratum = DEFAULT_SAMPLES_PER_STRATUM  # 64
    with caplog.at_level(logging.WARNING,
                         logger="train.rl.rl_pawn.trainSAC.strata"):
        indices, counts = sample_stratified(masks, per_stratum=per_stratum)

    # combat_active = 0 samples (ondervol)
    # no_combat = 200 samples
    # carrier_active = 10 (ondervol)
    # post_damage = 0 (ondervol)
    # default = 200
    assert counts[STRATUM_COMBAT_ACTIVE] == 0
    assert counts[STRATUM_CARRIER_ACTIVE] == 10
    assert counts[STRATUM_POST_DAMAGE] == 0
    assert counts[STRATUM_NO_COMBAT] == per_stratum
    assert counts[STRATUM_DEFAULT] == per_stratum
    assert indices.size > 0, "default + no_combat moeten samples leveren"

    # Warning gelogd voor minimaal 1 ondervol stratum
    warns = [r for r in caplog.records
             if r.levelno == logging.WARNING and "STRATA_UNDERVOL" in r.getMessage()]
    assert warns, "verwacht minstens één STRATA_UNDERVOL warning"


def test_missing_features_fallback_to_default() -> None:
    """Wanneer self_hasFlag of enemy_visible ontbreken in input_features → die
    strata zijn leeg, maar default blijft bevolkt. Geen crash."""
    # input_features zonder zelf-flag-features
    feats = ["padding_0", "padding_1", "padding_2"]
    idx = _make_indices(feats)
    assert idx.self_has_flag == -1
    assert all(i == -1 for i in idx.enemy_visible)

    n_features = len(feats)
    last_frames = np.random.RandomState(0).rand(100, n_features).astype(np.float32)
    masks = classify(last_frames, idx)
    assert not masks[STRATUM_COMBAT_ACTIVE].any()
    assert not masks[STRATUM_CARRIER_ACTIVE].any()
    # default is altijd all-true
    assert masks[STRATUM_DEFAULT].all()

    indices, counts = sample_stratified(masks, per_stratum=DEFAULT_SAMPLES_PER_STRATUM)
    assert counts[STRATUM_DEFAULT] == DEFAULT_SAMPLES_PER_STRATUM
    assert counts[STRATUM_COMBAT_ACTIVE] == 0


def test_post_damage_proxy_when_feature_missing() -> None:
    """recent_damage_taken_2s ontbreekt → post_damage gebruikt proxy
    (enemy_visible AND max(aim_alignment) > 0.7)."""
    feats = ["self_hasFlag"]
    for i in range(5):
        feats.extend([f"enemy{i}_visible", f"enemy{i}_aimAlignmentDot_norm"])
    # recent_damage_taken_2s ontbreekt opzettelijk
    feats.extend(["padding_a", "padding_b"])
    idx = _make_indices(feats)
    assert idx.recent_damage_taken_2s == -1
    n_features = len(feats)

    last_frames = np.zeros((3, n_features), dtype=np.float32)
    # Sample 0: enemy0_visible=1, enemy0_aim=0.8 → post_damage proxy TRUE
    last_frames[0, idx.enemy_visible[0]] = 1.0
    last_frames[0, idx.enemy_aim_alignment[0]] = 0.8
    # Sample 1: enemy0_visible=1 maar aim=0.5 → proxy FALSE
    last_frames[1, idx.enemy_visible[0]] = 1.0
    last_frames[1, idx.enemy_aim_alignment[0]] = 0.5
    # Sample 2: enemy0 niet visible → proxy FALSE
    last_frames[2, idx.enemy_aim_alignment[0]] = 0.9

    masks = classify(last_frames, idx)
    assert masks[STRATUM_POST_DAMAGE].tolist() == [True, False, False]


def test_probe_eval_integration_with_stratified_sampling() -> None:
    """Dummy probe-eval met stratified sampling — assert dat sampling +
    classifyer + evaluate_probes_from_raw zonder crash een ProbeReport
    produceren, en de per-stratum counts geformat kunnen worden tot log-line.
    """
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features()
    strata_idx = _make_indices(feats)
    probe_feat_idx = JointFeatureIndices.from_input_features(feats)
    n_features = len(feats)

    n_over = 400
    last_frames = np.zeros((n_over, n_features), dtype=np.float32)
    last_frames[0:80, strata_idx.enemy_visible[0]] = 1.0
    last_frames[80:160, strata_idx.self_has_flag] = 1.0
    last_frames[160:240, strata_idx.recent_damage_taken_2s] = 0.5
    # 240..400 default

    masks = classify(last_frames, strata_idx)
    indices, counts = sample_stratified(masks, per_stratum=50,
                                         rng=np.random.default_rng(123))
    log_line = format_per_stratum_counts(counts, per_stratum=50)
    # Verzeker dat alle strata-namen + counts in log staan
    for stratum in STRATA:
        assert f"{stratum}={counts[stratum]}" in log_line

    sampled_last_frames = last_frames[indices]
    n_sampled = sampled_last_frames.shape[0]

    # Dummy probe inputs — same shapes that probe-eval verwacht.
    actions = np.random.RandomState(7).randn(n_sampled, 10).astype(np.float32) * 0.3
    actions_next = actions + 0.05 * np.random.RandomState(8).randn(n_sampled, 10).astype(np.float32)
    target_logits = np.random.RandomState(9).randn(n_sampled, 5).astype(np.float32)

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 1.5
        spread_floor = 0.05
        gain_bias_limit = 0.50
        yaw_gain_bias_limit = 0.50
        pitch_gain_bias_limit = 0.50
        yaw_gain_bias_delta_limit = 0.50
        pitch_gain_bias_delta_limit = 0.50
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.60
        jump_rate_ceiling = 0.75
        duck_rate_ceiling = 0.75
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.60
        fire_aim_alignment_floor = 0.70

    report = evaluate_probes_from_raw(
        actor_actions=actions, actor_actions_next=actions_next,
        target_logits=target_logits,
        bc_actions=None, bc_actions_next=None,
        last_frame_states=sampled_last_frames,
        feature_idx=probe_feat_idx, cfg=_ProbeCfgStub(),
    )
    # Sanity-check: report bevat per-head + cross-head probes
    names = {m.name for m in report.metrics}
    assert "yaw_sat" in names
    assert "fire_rate" in names
    assert "altFire_rate" in names
    assert "idle_rate" in names
    assert "target_entropy" in names
    assert "cc1_target_yaw_consistency" in names


def test_target_entropy_skips_when_fewer_than_three_live_enemies() -> None:
    """Concentrated target logits are valid in 1v1/2-enemy states.

    The live export gate uses the same ``probe.json`` wording: entropy and
    concentration checks only apply to samples with >=3 live enemy slots.
    """
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features()
    feature_idx = JointFeatureIndices.from_input_features(feats)
    n = 16
    states = np.zeros((n, len(feats)), dtype=np.float32)
    for i in range(2):
        states[:, feats.index(f"enemy{i}_isAlive")] = 1.0
    actions = np.zeros((n, 10), dtype=np.float32)
    # Keep fire/altFire flipping so the binary collapse probe does not distract.
    actions[:, 8] = np.where(np.arange(n) % 2 == 0, -1.0, 1.0)
    actions[:, 9] = np.where(np.arange(n) % 2 == 0, 1.0, -1.0)
    target_logits = np.full((n, 5), -10.0, dtype=np.float32)
    target_logits[:, 0] = 10.0

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 10.0
        spread_floor = 0.0
        gain_bias_limit = 1.0
        yaw_gain_bias_limit = 1.0
        pitch_gain_bias_limit = 1.0
        yaw_gain_bias_delta_limit = 1.0
        pitch_gain_bias_delta_limit = 1.0
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.60
        jump_rate_ceiling = 0.75
        duck_rate_ceiling = 0.75
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.0
        fire_aim_alignment_floor = 0.0

    report = evaluate_probes_from_raw(
        actor_actions=actions,
        actor_actions_next=actions.copy(),
        target_logits=target_logits,
        bc_actions=None,
        bc_actions_next=None,
        last_frame_states=states,
        feature_idx=feature_idx,
        cfg=_ProbeCfgStub(),
    )

    target_metrics = {
        m.name: m for m in report.metrics
        if m.name in {"target_entropy", "target_concentration"}
    }
    assert target_metrics["target_entropy"].skipped
    assert target_metrics["target_concentration"].skipped
    assert "target_entropy" not in report.failed_names()


def test_target_entropy_fails_when_three_plus_live_enemies_collapse() -> None:
    """The skip is narrow: 3+ live enemy states still enforce diversity."""
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features()
    feature_idx = JointFeatureIndices.from_input_features(feats)
    n = 16
    states = np.zeros((n, len(feats)), dtype=np.float32)
    for i in range(3):
        states[:, feats.index(f"enemy{i}_isAlive")] = 1.0
    actions = np.zeros((n, 10), dtype=np.float32)
    actions[:, 8] = np.where(np.arange(n) % 2 == 0, -1.0, 1.0)
    actions[:, 9] = np.where(np.arange(n) % 2 == 0, 1.0, -1.0)
    target_logits = np.full((n, 5), -10.0, dtype=np.float32)
    target_logits[:, 0] = 10.0

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 10.0
        spread_floor = 0.0
        gain_bias_limit = 1.0
        yaw_gain_bias_limit = 1.0
        pitch_gain_bias_limit = 1.0
        yaw_gain_bias_delta_limit = 1.0
        pitch_gain_bias_delta_limit = 1.0
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.60
        jump_rate_ceiling = 0.75
        duck_rate_ceiling = 0.75
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.0
        fire_aim_alignment_floor = 0.0

    report = evaluate_probes_from_raw(
        actor_actions=actions,
        actor_actions_next=actions.copy(),
        target_logits=target_logits,
        bc_actions=None,
        bc_actions_next=None,
        last_frame_states=states,
        feature_idx=feature_idx,
        cfg=_ProbeCfgStub(),
    )

    assert "target_entropy" in report.failed_names()
    assert "target_concentration" in report.failed_names()


def test_movement_probe_fails_idle_collapse() -> None:
    """Full-joint export must not pass a policy that mostly idles."""
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features()
    feature_idx = JointFeatureIndices.from_input_features(feats)
    n = 32
    states = np.zeros((n, len(feats)), dtype=np.float32)
    actions = np.zeros((n, 10), dtype=np.float32)
    actions[:, 5] = 1.0  # bIdle on for every sample
    actions[:, 8] = np.where(np.arange(n) % 2 == 0, -1.0, 1.0)
    actions[:, 9] = np.where(np.arange(n) % 2 == 0, 1.0, -1.0)
    target_logits = np.zeros((n, 5), dtype=np.float32)

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 10.0
        spread_floor = 0.0
        gain_bias_limit = 1.0
        yaw_gain_bias_limit = 1.0
        pitch_gain_bias_limit = 1.0
        yaw_gain_bias_delta_limit = 1.0
        pitch_gain_bias_delta_limit = 1.0
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.60
        jump_rate_ceiling = 0.75
        duck_rate_ceiling = 0.75
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.0
        fire_aim_alignment_floor = 0.0

    report = evaluate_probes_from_raw(
        actor_actions=actions,
        actor_actions_next=actions.copy(),
        target_logits=target_logits,
        bc_actions=None,
        bc_actions_next=None,
        last_frame_states=states,
        feature_idx=feature_idx,
        cfg=_ProbeCfgStub(),
    )

    assert "idle_rate" in report.failed_names()


def test_pitch_bias_delta_fails_against_bc_reference() -> None:
    """A constant positive pitch delta in neutral context passes saturation but
    still drives the accumulator to the upper pitch clamp; reject it."""
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features()
    feature_idx = JointFeatureIndices.from_input_features(feats)
    n = 32
    states = np.zeros((n, len(feats)), dtype=np.float32)
    actions = np.zeros((n, 10), dtype=np.float32)
    actions_next = actions.copy()
    bc_actions = np.zeros((n, 10), dtype=np.float32)
    bc_actions_next = bc_actions.copy()
    actions[:, 7] = 0.12  # pitchDelta_norm mean: small enough to avoid sat, large enough to climb.
    actions_next[:, 7] = 0.12
    actions[:, 8] = np.where(np.arange(n) % 2 == 0, -1.0, 1.0)
    actions[:, 9] = np.where(np.arange(n) % 2 == 0, 1.0, -1.0)
    target_logits = np.zeros((n, 5), dtype=np.float32)

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 10.0
        spread_floor = 0.0
        gain_bias_limit = 1.0
        yaw_gain_bias_limit = 1.0
        pitch_gain_bias_limit = 0.08
        yaw_gain_bias_delta_limit = 1.0
        pitch_gain_bias_delta_limit = 0.05
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.95
        jump_rate_ceiling = 0.95
        duck_rate_ceiling = 0.95
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.0
        fire_aim_alignment_floor = 0.0

    report = evaluate_probes_from_raw(
        actor_actions=actions,
        actor_actions_next=actions_next,
        target_logits=target_logits,
        bc_actions=bc_actions,
        bc_actions_next=bc_actions_next,
        last_frame_states=states,
        feature_idx=feature_idx,
        cfg=_ProbeCfgStub(),
    )

    assert "pitch_gain_bias" in report.failed_names()
    assert "pitch_gain_bias_delta" in report.failed_names()


def test_contextual_pitch_bias_is_allowed_for_pitch_aim_hints() -> None:
    """Flak/rocket arcs and height-difference maps need sustained pitch deltas.
    The bias gate should only reject neutral drift, not contextual vertical aim."""
    from train.rl.rl_pawn.trainSAC.probes import (
        JointFeatureIndices,
        evaluate_probes_from_raw,
    )

    feats = _synthetic_features() + [
        "enemy0_pitchBearing_norm",
        "primaryAimPitchError_norm",
        "secondaryAimPitchError_norm",
        "shootIntentPitchError_norm",
    ]
    feature_idx = JointFeatureIndices.from_input_features(feats)
    n = 32
    states = np.zeros((n, len(feats)), dtype=np.float32)
    states[:, feats.index("shootIntentPitchError_norm")] = 0.35
    actions = np.zeros((n, 10), dtype=np.float32)
    actions_next = actions.copy()
    bc_actions = np.zeros((n, 10), dtype=np.float32)
    bc_actions_next = bc_actions.copy()
    actions[:, 7] = 0.12
    actions_next[:, 7] = 0.12
    actions[:, 8] = np.where(np.arange(n) % 2 == 0, -1.0, 1.0)
    actions[:, 9] = np.where(np.arange(n) % 2 == 0, 1.0, -1.0)
    target_logits = np.zeros((n, 5), dtype=np.float32)

    class _ProbeCfgStub:
        sat_limit = 0.40
        jitter_ratio_vs_bc = 10.0
        spread_floor = 0.0
        gain_bias_limit = 1.0
        yaw_gain_bias_limit = 1.0
        pitch_gain_bias_limit = 0.08
        yaw_gain_bias_delta_limit = 1.0
        pitch_gain_bias_delta_limit = 0.05
        rate_floor = 0.005
        rate_ceiling = 0.995
        both_dims_collapse_floor = 0.001
        idle_rate_ceiling = 0.95
        jump_rate_ceiling = 0.95
        duck_rate_ceiling = 0.95
        target_entropy_floor_3plus_enemies = 0.20
        target_concentration_ceiling = 0.90
        target_yaw_consistency_floor = 0.0
        fire_aim_alignment_floor = 0.0

    report = evaluate_probes_from_raw(
        actor_actions=actions,
        actor_actions_next=actions_next,
        target_logits=target_logits,
        bc_actions=bc_actions,
        bc_actions_next=bc_actions_next,
        last_frame_states=states,
        feature_idx=feature_idx,
        cfg=_ProbeCfgStub(),
    )

    assert "pitch_gain_bias" not in report.failed_names()
    assert "pitch_gain_bias_delta" not in report.failed_names()
    skipped = {m.name for m in report.metrics if m.skipped}
    assert "pitch_gain_bias" in skipped
    assert "pitch_gain_bias_delta" in skipped
