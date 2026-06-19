"""Parse a flat input_features list into per-player slot structure for Necto-style
permutation-invariant encoding.

Naming convention inferred from the feature name:
- self_<suffix>         → self pathway (unified with global features)
- teammate<N>_<suffix>  → teammate slot N
- enemy<N>_<suffix>     → enemy slot N
- otherwise             → global feature (merged into self pathway)

The resulting PlayerFeatureGrouping contains flat-vector indices, so the Necto
model can slice self/teammate/enemy sub-tensors from a single flat input tensor.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

TEAMMATE_PATTERN = re.compile(r"^teammate(\d+)_(.+)$")
ENEMY_PATTERN = re.compile(r"^enemy(\d+)_(.+)$")
MAP_ID_FEATURE = "map_id"


@dataclass(frozen=True)
class PlayerFeatureGrouping:
    """Grouping metadata for a flat input_features list.

    Attributes:
        input_features: original flat list (for reference/debug).
        self_and_global_indices: flat indices for self_* + unprefixed (global) features.
        teammate_slot_indices: [max_teammates][player_dim] flat indices per slot.
        enemy_slot_indices:    [max_enemies][player_dim] flat indices per slot.
        teammate_suffixes: ordered list of suffixes shared by all teammate slots.
        enemy_suffixes:    ordered list of suffixes shared by all enemy slots.
        map_id_index: flat index of the categorical map id feature, if present.
        teammate_isAlive_relative_idx: index of "isAlive" within a teammate slot (for mask).
                                       None if the "isAlive" feature is not in the config.
        enemy_isAlive_relative_idx:    same for enemies.
    """
    input_features: list[str]
    self_and_global_indices: list[int]
    teammate_slot_indices: list[list[int]]
    enemy_slot_indices: list[list[int]]
    teammate_suffixes: list[str]
    enemy_suffixes: list[str]
    map_id_index: int | None
    teammate_isAlive_relative_idx: int | None
    enemy_isAlive_relative_idx: int | None

    @property
    def max_teammates(self) -> int:
        return len(self.teammate_slot_indices)

    @property
    def max_enemies(self) -> int:
        return len(self.enemy_slot_indices)

    @property
    def teammate_player_dim(self) -> int:
        return len(self.teammate_suffixes)

    @property
    def enemy_player_dim(self) -> int:
        return len(self.enemy_suffixes)

    @property
    def self_global_dim(self) -> int:
        return len(self.self_and_global_indices)


def parse(input_features: list[str]) -> PlayerFeatureGrouping:
    """Parse a flat input_features list into a PlayerFeatureGrouping.

    Raises ValueError if the config violates invariants:
    - Slots must be consecutive starting from 0 (no gaps).
    - All slots of the same type must declare the same suffixes in the same order.
    """
    self_and_global_indices: list[int] = []
    teammate_by_slot: dict[int, list[tuple[str, int]]] = {}
    enemy_by_slot: dict[int, list[tuple[str, int]]] = {}
    map_id_index: int | None = None

    for i, feat in enumerate(input_features):
        m_tm = TEAMMATE_PATTERN.match(feat)
        m_en = ENEMY_PATTERN.match(feat)
        if feat == MAP_ID_FEATURE:
            if map_id_index is not None:
                raise ValueError("Feature grouping: map_id may appear at most once")
            map_id_index = i
        elif m_tm:
            slot = int(m_tm.group(1))
            suffix = m_tm.group(2)
            teammate_by_slot.setdefault(slot, []).append((suffix, i))
        elif m_en:
            slot = int(m_en.group(1))
            suffix = m_en.group(2)
            enemy_by_slot.setdefault(slot, []).append((suffix, i))
        else:
            # self_* and unprefixed (global) features share the self pathway
            self_and_global_indices.append(i)

    _demote_sparse_extra_slots(teammate_by_slot, self_and_global_indices, "teammate")

    teammate_suffixes, teammate_slot_indices = _build_slot_group(teammate_by_slot, "teammate")
    enemy_suffixes, enemy_slot_indices = _build_slot_group(enemy_by_slot, "enemy")
    self_and_global_indices.sort()

    teammate_isAlive_rel = (
        teammate_suffixes.index("isAlive") if "isAlive" in teammate_suffixes else None
    )
    enemy_isAlive_rel = (
        enemy_suffixes.index("isAlive") if "isAlive" in enemy_suffixes else None
    )

    return PlayerFeatureGrouping(
        input_features=list(input_features),
        self_and_global_indices=self_and_global_indices,
        teammate_slot_indices=teammate_slot_indices,
        enemy_slot_indices=enemy_slot_indices,
        teammate_suffixes=teammate_suffixes,
        enemy_suffixes=enemy_suffixes,
        map_id_index=map_id_index,
        teammate_isAlive_relative_idx=teammate_isAlive_rel,
        enemy_isAlive_relative_idx=enemy_isAlive_rel,
    )


def _build_slot_group(by_slot: dict[int, list[tuple[str, int]]],
                      prefix_name: str) -> tuple[list[str], list[list[int]]]:
    if not by_slot:
        return [], []

    slots_present = sorted(by_slot.keys())
    max_slot = slots_present[-1]
    if slots_present != list(range(max_slot + 1)):
        missing = [s for s in range(max_slot + 1) if s not in by_slot]
        raise ValueError(
            f"Feature grouping: {prefix_name} slots must be consecutive from 0, "
            f"but slot(s) {missing} are missing while slot {max_slot} is present."
        )

    ref_suffixes = [s for s, _ in by_slot[0]]
    slot_indices: list[list[int]] = []
    for slot in range(max_slot + 1):
        suffixes = [s for s, _ in by_slot[slot]]
        if suffixes != ref_suffixes:
            raise ValueError(
                f"Feature grouping: {prefix_name} slot {slot} has suffixes {suffixes} "
                f"which differ from slot 0 ({ref_suffixes}). All slots of the same "
                "type must declare the same features in the same order."
            )
        slot_indices.append([idx for _, idx in by_slot[slot]])

    return ref_suffixes, slot_indices


def _demote_sparse_extra_slots(by_slot: dict[int, list[tuple[str, int]]],
                               self_and_global_indices: list[int],
                               prefix_name: str) -> None:
    """Move trailing non-uniform slots out of pooled player grouping.

    The Necto-style player encoder requires every pooled slot to expose the
    same suffixes. Some feature sets add owner-prefixed auxiliary channels
    (currently teammate projectile slots) for more owners than the core
    teammate state exposes. Treating those sparse trailing slots as global
    features keeps the raw signal available without breaking the uniform
    per-player tensor contract.
    """
    if not by_slot or 0 not in by_slot:
        return

    ref_suffixes = [s for s, _ in by_slot[0]]
    for slot in sorted(list(by_slot.keys())):
        suffixes = [s for s, _ in by_slot[slot]]
        if suffixes == ref_suffixes:
            continue
        if slot == 0:
            raise ValueError(
                f"Feature grouping: {prefix_name} slot 0 cannot be sparse/non-uniform"
            )
        self_and_global_indices.extend(idx for _, idx in by_slot.pop(slot))
