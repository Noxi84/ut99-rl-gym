"""Model role resolution for Python training pipeline.

Reads role bindings from the same roles.json as the Java runtime,
ensuring both sides speak the same role language.
"""
from __future__ import annotations

from train.common.PropertyReader import _get_by_path

# Joint VR + shooting policy — the single low-level policy in productie.
PAWN_POLICY = "pawn_policy"


def resolve_model_key(role: str) -> str:
    """Resolve a role to its bound model key. Crashes if the binding is missing."""
    key = _get_by_path(f"/roles/bindings/{role}")
    if not isinstance(key, str):
        raise ValueError(f"/roles/bindings/{role} must be a string, got {type(key).__name__}")
    return key
