"""Fase 4b Deel D — Strata-stratified sampling voor de joint probe pipeline.

De v1 uniform sampling (zie ``probes.py`` Fase 3a) leverde 256 willekeurige
transities; in compacte combat-bursts zat de aim-feature ruimte tendentieus
op één type state (zelfde enemy, zelfde bearing) waardoor probe-conclusies
niet generaliseerden. Codex review (regels 31-50 van
``docs/joint_policy/probe-design.md``) schreef expliciet stratified sampling
over vijf state-strata voor:

* ``combat_active``  — enemy-visible-count ≥ 1
* ``no_combat``      — enemy-visible-count == 0
* ``carrier_active`` — self_hasFlag == 1
* ``post_damage``    — recent_damage_taken_2s > 0
* ``default``        — alle transities (overlapt deliberately)

Per-stratum 64 samples → 5 × 64 = 320 totaal, iets boven probe.json's 256.
De extra samples zijn een bewuste keuze van Fase 4b: het corrigeert voor het
feit dat probe-conclusies per stratum gelogd worden en lage-N strata anders
te ruisig zouden zijn (zie module-doc opdracht-tekst).

Strata-classificatie leest features op naam uit de joint features.json. Geen
hardcoded feature IDs (CLAUDE.md "No hardcoded feature IDs"): ontbrekende
features → dat stratum wordt ``skipped`` gelogd en valt automatisch terug
op ``default`` voor probe-eval.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

LOG = logging.getLogger(__name__)

STRATUM_COMBAT_ACTIVE = "combat_active"
STRATUM_NO_COMBAT = "no_combat"
STRATUM_CARRIER_ACTIVE = "carrier_active"
STRATUM_POST_DAMAGE = "post_damage"
STRATUM_DEFAULT = "default"

# Per-weapon strata — één per canonical UT99 weapon key. Identiek aan Java
# WeaponIdentityFeatureValueResolver.FEATURE_TO_WEAPON values en Python
# _canonical_weapon output (player_scores_eval.py). Per stratum maskeert
# samples waar `self_weapon_is<X>` features op 1.0 staan; ondervolle wapens
# (bot heeft dit wapen weinig gedragen) loggen STRATA_UNDERVOL warning en
# tellen alleen wat beschikbaar is. Voor single-weapon training (bv. pulse
# only) zijn 13 wapen-strata leeg → harmless, kosten geen probe-CPU.
WEAPON_KEYS = (
    "ImpactHammer", "Translocator", "Enforcer", "DoubleEnforcer",
    "BioRifle", "ShockRifle", "PulseGun", "Ripper",
    "Minigun", "FlakCannon", "Eightball", "SniperRifle",
    "WarheadLauncher", "SuperShockRifle",
)
STRATA_WEAPON_PREFIX = "active_weapon_"
STRATA_WEAPONS: Tuple[str, ...] = tuple(
    f"{STRATA_WEAPON_PREFIX}{w}" for w in WEAPON_KEYS
)

# Originele 5 state-strata (combat/no_combat/carrier/post_damage/default).
# Aparte tuple zodat callers die alleen state-stratificatie nodig hebben
# (tests, oude probe-rapporten) niet hoeven te schalen met het aantal wapens.
STATE_STRATA: Tuple[str, ...] = (
    STRATUM_COMBAT_ACTIVE,
    STRATUM_NO_COMBAT,
    STRATUM_CARRIER_ACTIVE,
    STRATUM_POST_DAMAGE,
    STRATUM_DEFAULT,
)

STRATA = STATE_STRATA + STRATA_WEAPONS

DEFAULT_SAMPLES_PER_STRATUM = 64


@dataclass(frozen=True)
class StratificationFeatureIndices:
    """Vlakke indices in de last-frame feature vector waarop strata-classifiers
    leunen. ``-1`` betekent feature ontbreekt in de joint feature config — die
    stratum wordt dan ``skipped`` (niet ``failed``).

    ``recent_damage_taken_2s`` is de meest fragile: niet alle iteraties van de
    joint features.json hebben hem (oude shooting Phase-1 features.json had
    hem wel, maar de joint union heeft hem in v1 weggelaten). Bij afwezigheid
    valt ``post_damage`` terug op ``enemy_visible_count >= 1 AND enemy aim
    alignment > 0.7`` als proxy — een bot in een aim-engagement is statistisch
    een goede stand-in voor recent-onder-vuur-genomen-bot.

    ``weapon_indices`` mapt elke canonical weapon-key naar de positie van het
    bijbehorende ``self_weapon_is<X>``-one-hot in de feature-vector. Wapens
    waarvoor de feature ontbreekt krijgen ``-1`` → het stratum is leeg (zelfde
    afhandeling als andere missing features).
    """
    self_has_flag: int
    enemy_visible: Tuple[int, ...]     # 5 entries, -1 wanneer slot mist
    enemy_aim_alignment: Tuple[int, ...]   # 5 entries — voor post_damage proxy
    recent_damage_taken_2s: int        # -1 wanneer joint features hem niet heeft
    # Per-weapon one-hot indices. Tuple van (weapon_key, feature_idx) zodat
    # iteratievolgorde stabiel matcht met STRATA_WEAPONS.
    weapon_indices: Tuple[Tuple[str, int], ...]

    @classmethod
    def from_input_features(cls, input_features: List[str]) -> "StratificationFeatureIndices":
        def idx(name: str) -> int:
            try:
                return input_features.index(name)
            except ValueError:
                return -1
        return cls(
            self_has_flag=idx("self_hasFlag"),
            enemy_visible=tuple(idx(f"enemy{i}_visible") for i in range(5)),
            enemy_aim_alignment=tuple(idx(f"enemy{i}_aimAlignmentDot_norm") for i in range(5)),
            recent_damage_taken_2s=idx("recent_damage_taken_2s"),
            weapon_indices=tuple(
                (w, idx(f"self_weapon_is{w}")) for w in WEAPON_KEYS
            ),
        )


def classify(last_frame_states: np.ndarray,
             indices: StratificationFeatureIndices) -> Dict[str, np.ndarray]:
    """Bereken per-stratum boolean masks voor ``last_frame_states``.

    Parameters
    ----------
    last_frame_states : np.ndarray [B, F]
        Last-timestep feature vectoren — de matchende laatste frame uit de
        states-window. Strata zijn ``B``-lengte boolean masks.

    indices : StratificationFeatureIndices
        Voor-berekende indices in de feature-vector.

    Returns
    -------
    dict
        ``{stratum_name: np.ndarray(B, bool)}`` voor alle strata in ``STRATA``.
        Default-stratum is altijd all-True (vangt alles).
    """
    if last_frame_states.ndim != 2:
        raise ValueError(
            f"last_frame_states moet [B, F] zijn; kreeg shape {last_frame_states.shape}"
        )
    n = last_frame_states.shape[0]

    # enemy_visible_count: sum van aanwezige slot-visibility features. Wanneer
    # alle slot-indices missen → stratum_combat_active is skipped (mask = all-False).
    visible_slots = [i for i in indices.enemy_visible if i >= 0]
    if visible_slots:
        visible_count = np.sum(last_frame_states[:, visible_slots] > 0.5, axis=1)
        combat_active = visible_count >= 1
        no_combat = visible_count == 0
    else:
        LOG.info("STRATA_FEATURE_MISSING: enemyN_visible afwezig — "
                 "combat_active/no_combat stratums leeg")
        combat_active = np.zeros(n, dtype=bool)
        no_combat = np.zeros(n, dtype=bool)

    if indices.self_has_flag >= 0:
        carrier_active = last_frame_states[:, indices.self_has_flag] > 0.5
    else:
        LOG.info("STRATA_FEATURE_MISSING: self_hasFlag afwezig — "
                 "carrier_active stratum leeg")
        carrier_active = np.zeros(n, dtype=bool)

    if indices.recent_damage_taken_2s >= 0:
        post_damage = last_frame_states[:, indices.recent_damage_taken_2s] > 0.0
    else:
        # Fallback proxy: bot is recent in combat als ≥1 enemy zichtbaar EN
        # de chosen-target aim-alignment voldoende hoog is. Niet ideaal — een
        # bot die zelf damage geeft scoort hier ook hoog — maar marginale
        # verschil voor probe-detection.
        aim_slots = [i for i in indices.enemy_aim_alignment if i >= 0]
        if visible_slots and aim_slots:
            visible_mask = np.sum(last_frame_states[:, visible_slots] > 0.5, axis=1) >= 1
            max_aim = np.max(last_frame_states[:, aim_slots], axis=1)
            post_damage = visible_mask & (max_aim > 0.7)
            LOG.info("STRATA_FEATURE_FALLBACK: recent_damage_taken_2s ontbreekt — "
                     "post_damage gebruikt enemy_visible + aim_alignment>0.7 proxy")
        else:
            LOG.info("STRATA_FEATURE_MISSING: recent_damage_taken_2s + proxy "
                     "features afwezig — post_damage stratum leeg")
            post_damage = np.zeros(n, dtype=bool)

    # Per-weapon strata: één mask per canonical weapon key. Wapens waarvan de
    # feature niet in features.json zit (legacy config) → leeg mask. Bij
    # single-weapon training (bv. pulse-only) zijn 13 wapens leeg en valt
    # 1 stratum samen met combat+default voor de actieve weapon.
    weapon_masks: Dict[str, np.ndarray] = {}
    missing_weapons: List[str] = []
    for weapon_key, feat_idx in indices.weapon_indices:
        stratum_name = f"{STRATA_WEAPON_PREFIX}{weapon_key}"
        if feat_idx >= 0:
            weapon_masks[stratum_name] = last_frame_states[:, feat_idx] > 0.5
        else:
            weapon_masks[stratum_name] = np.zeros(n, dtype=bool)
            missing_weapons.append(weapon_key)
    if missing_weapons:
        LOG.info("STRATA_FEATURE_MISSING: self_weapon_is{%s} afwezig — "
                 "weapon-strata leeg voor die wapens",
                 ",".join(missing_weapons))

    return {
        STRATUM_COMBAT_ACTIVE: combat_active,
        STRATUM_NO_COMBAT: no_combat,
        STRATUM_CARRIER_ACTIVE: carrier_active,
        STRATUM_POST_DAMAGE: post_damage,
        STRATUM_DEFAULT: np.ones(n, dtype=bool),
        **weapon_masks,
    }


def sample_stratified(masks: Dict[str, np.ndarray], per_stratum: int,
                       rng: Optional[np.random.Generator] = None
                       ) -> Tuple[np.ndarray, Dict[str, int]]:
    """Sample ≤ per_stratum indices uit elk stratum. Ondervolle strata loggen
    een warning maar crashen niet — we behouden zoveel als er zijn.

    Parameters
    ----------
    masks : dict
        Output van :func:`classify`; ``{stratum: B-bool mask}``.

    per_stratum : int
        Max samples per stratum (5 × per_stratum = totaal). Default 64.

    rng : np.random.Generator | None
        Reproducible sampling — None → fresh non-seeded Generator.

    Returns
    -------
    indices : np.ndarray[int]
        1D array of selected sample indices (may contain duplicates across
        strata; combat_active en post_damage kunnen overlappen). Volgorde:
        per stratum in ``STRATA`` volgorde.

    per_stratum_counts : dict
        ``{stratum: count}`` — hoe veel samples genomen per stratum (≤
        per_stratum). Wordt door caller gelogd voor diagnose.
    """
    if rng is None:
        rng = np.random.default_rng()
    counts: Dict[str, int] = {}
    selected: List[int] = []
    for stratum in STRATA:
        mask = masks[stratum]
        candidates = np.where(mask)[0]
        if len(candidates) <= per_stratum:
            counts[stratum] = int(len(candidates))
            # Weapon-strata met 0 samples = wapen afwezig in arena (bv. FlakOnly
            # mutator). Geen UNDERVOL warning; "afwezig" ≠ "ondervol". Andere
            # strata (combat_active/no_combat/...) en weapon-strata met 1-63
            # samples loggen wel, want dat is echte sample-tekort.
            is_weapon_stratum = stratum.startswith(STRATA_WEAPON_PREFIX)
            if len(candidates) < per_stratum and not (is_weapon_stratum and len(candidates) == 0):
                LOG.warning(
                    "STRATA_UNDERVOL[%s]: %d/%d samples beschikbaar — "
                    "probe-eval voor dit stratum heeft hogere ruis",
                    stratum, len(candidates), per_stratum,
                )
            selected.extend(candidates.tolist())
        else:
            chosen = rng.choice(candidates, size=per_stratum, replace=False)
            counts[stratum] = int(per_stratum)
            selected.extend(chosen.tolist())
    return np.asarray(selected, dtype=np.int64), counts


def format_per_stratum_counts(counts: Dict[str, int], per_stratum: int) -> str:
    """Eén-regel log-string ``combat_active=N/per_stratum, no_combat=M/...``"""
    parts = []
    for stratum in STRATA:
        n = counts.get(stratum, 0)
        parts.append(f"{stratum}={n}/{per_stratum}")
    return ", ".join(parts)
