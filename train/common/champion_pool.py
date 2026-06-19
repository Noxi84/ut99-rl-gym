"""Champion bundle pool — the rotating set of opponent bundles that current
self-plays against. Backed by champions/bundles.json.

Bundle = a logical pointer-set: one champion-counter per model_key. Pool is
a list of bundles; rotation is round-robin per server, drop-oldest on promote.

Bootstrap: when champions/{mk}/ is empty for any model, snapshot the promoted
SAC best ONNX when available, otherwise the current trainingmodel ONNX as
0001 for that model. Initial pool is filled with
POOL_SIZE copies of the all-models-at-0001 bundle (degenerate but coherent —
the first promote breaks the degeneracy by introducing diversity in pool[0]).
"""
from __future__ import annotations

import argparse
import fcntl
import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, TypeVar

from train.common import champion_store
from train.common.SessionPaths import get_session_paths

POOL_SIZE = 3

T = TypeVar("T")


@dataclass
class Bundle:
    id: str
    counters: Dict[str, int]
    created_at: str
    created_because: str


@dataclass
class PoolState:
    pool: List[Bundle] = field(default_factory=list)
    next_bundle_id: int = 1


def bundles_path() -> Path:
    return champion_store.champions_root() / "bundles.json"


def _read_state() -> PoolState:
    p = bundles_path()
    if not p.exists():
        return PoolState()
    with p.open("r", encoding="utf-8") as f:
        data = json.load(f)
    bundles = [Bundle(**b) for b in data.get("pool", [])]
    return PoolState(pool=bundles, next_bundle_id=int(data.get("next_bundle_id", 1)))


def _write_state(state: PoolState) -> None:
    p = bundles_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump({
            "pool": [asdict(b) for b in state.pool],
            "next_bundle_id": state.next_bundle_id,
        }, f, indent=2)
    tmp.rename(p)


def _with_lock(fn: Callable[[], T]) -> T:
    p = champion_store.champions_root() / ".pool-lock"
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("w") as lock_f:
        fcntl.flock(lock_f, fcntl.LOCK_EX)
        try:
            return fn()
        finally:
            fcntl.flock(lock_f, fcntl.LOCK_UN)


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _bootstrap_source(model_key: str, trainingmodel_dir: Path) -> tuple[Path, Optional[Path], Optional[str], Dict[str, str]]:
    promoted_onnx = trainingmodel_dir / f"{model_key}_sac_best.onnx"
    promoted_pt = trainingmodel_dir / f"{model_key}_sac_best.pt"
    if promoted_onnx.exists():
        return (
            promoted_onnx,
            promoted_pt if promoted_pt.exists() else None,
            "promoted-bootstrap",
            {
                "decision": "PROMOTE",
                "source": "bootstrap-from-sac-best",
                "reason": "bootstrap from last promoted SAC best ONNX",
            },
        )

    bc_baseline_onnx = trainingmodel_dir / f"{model_key}_bc_baseline.onnx"
    bc_baseline_pt = trainingmodel_dir / f"{model_key}_bc_baseline.pt"
    if bc_baseline_onnx.exists():
        return (
            bc_baseline_onnx,
            bc_baseline_pt if bc_baseline_pt.exists() else None,
            "bc-bootstrap",
            {
                "decision": "PROMOTE",
                "source": "bootstrap-from-bc-baseline",
                "reason": "BC baseline as initial champion",
            },
        )

    return (
        trainingmodel_dir / f"{model_key}.onnx",
        None,
        "current-bootstrap",
        {
            "decision": "PROMOTE",
            "source": "bootstrap-from-current",
            "reason": "current model as initial champion (no BC baseline available)",
        },
    )


def is_promoted_snapshot(model_key: str, counter: int) -> bool:
    """True only for snapshots created by the live PROMOTE path.

    Bootstrap/manual snapshots may be useful for explicit pinned testing, but
    they must not become the dynamic ``<mk>/newest`` champion.
    """
    snapshot_dir = champion_store.find_snapshot_dir(model_key, counter)
    if snapshot_dir is None:
        raise FileNotFoundError(
            f"Snapshot not found: {model_key}/{counter:04d}")
    meta = champion_store.read_meta(snapshot_dir)
    kpi = meta.kpi_at_snapshot or {}
    return str(kpi.get("decision", "")).upper() == "PROMOTE"


def bootstrap() -> PoolState:
    """Ensure each model has at least one snapshot and the pool is filled.

    For each model_key without snapshots: snapshot the last promoted
    trainingmodel/{mk}_sac_best.onnx when present; otherwise snapshot the
    current trainingmodel/{mk}.onnx as a bootstrap fallback. Then create the
    first bundle from the lowest counters and replicate it to fill the pool.
    Idempotent — running bootstrap on an already-bootstrapped pool is a no-op.
    """
    def _do() -> PoolState:
        sp = get_session_paths(create_dirs=True)
        state = _read_state()

        for mk in champion_store.all_model_keys():
            existing = champion_store.list_snapshots(mk)
            if not existing:
                src, src_pt, tag, kpi_at_snapshot = _bootstrap_source(
                    mk, sp.trainingmodel_dir)
                if not src.exists():
                    raise FileNotFoundError(
                        f"Cannot bootstrap {mk}: {src} does not exist. "
                        f"Train BC/SAC first or place a baseline ONNX at "
                        f"{sp.trainingmodel_dir}/{mk}.onnx.")
                champion_store.create_snapshot(
                    mk, src, tag=tag,
                    kpi_at_snapshot=kpi_at_snapshot,
                    src_pt=src_pt,
                )

        if not state.pool:
            counters: Dict[str, int] = {}
            for mk in champion_store.all_model_keys():
                snaps = champion_store.list_snapshots(mk)
                first = champion_store.read_meta(snaps[0])
                counters[mk] = first.counter
            bid = f"bundle-{state.next_bundle_id:04d}"
            initial_bundle = Bundle(
                id=bid,
                counters=counters,
                created_at=_now_iso(),
                created_because="bootstrap",
            )
            state.next_bundle_id += 1
            state.pool = [initial_bundle for _ in range(POOL_SIZE)]
            _write_state(state)
        return state

    return _with_lock(_do)


def _add_bundle(model_key: str, new_counter: int, created_because: str) -> Bundle:
    """Insert a new bundle at pool head, preserving other model counters."""
    def _do() -> Bundle:
        state = _read_state()
        if not state.pool:
            raise RuntimeError("Pool empty — call bootstrap() first")
        head = state.pool[0]
        new_counters = dict(head.counters)
        new_counters[model_key] = new_counter
        bid = f"bundle-{state.next_bundle_id:04d}"
        bundle = Bundle(
            id=bid,
            counters=new_counters,
            created_at=_now_iso(),
            created_because=created_because,
        )
        state.next_bundle_id += 1
        state.pool.insert(0, bundle)
        state.pool = state.pool[:POOL_SIZE]
        _write_state(state)
        return bundle

    return _with_lock(_do)


def add_promoted_bundle(model_key: str, new_counter: int) -> Bundle:
    """Add a bundle for a snapshot that was produced by PROMOTE.

    This guard prevents a merely deployed/current model from becoming the
    dynamic champion by accidentally calling the promote CLI/API.
    """
    if not is_promoted_snapshot(model_key, new_counter):
        raise RuntimeError(
            f"Refusing to add non-promoted snapshot to champion pool: "
            f"{model_key}/{new_counter:04d}")
    return _add_bundle(model_key, new_counter, f"{model_key} promote")


def add_bootstrap_bundle(model_key: str, new_counter: int) -> Bundle:
    """Add a non-promoted bootstrap bundle.

    Runtime ``<mk>/newest`` ignores these once promoted-only resolution is
    applied; they remain useful as explicit pinned counters.
    """
    return _add_bundle(model_key, new_counter, f"{model_key} bootstrap")


def newest_bundle() -> Optional[Bundle]:
    state = _read_state()
    return state.pool[0] if state.pool else None


def newest_promoted_bundle(model_key: str) -> Optional[Bundle]:
    """Return the newest pool bundle whose counter for model_key is promoted."""
    state = _read_state()
    for bundle in state.pool:
        counter = bundle.counters.get(model_key)
        if counter is None:
            continue
        if is_promoted_snapshot(model_key, int(counter)):
            return bundle
    return None


# ── CLI ────────────────────────────────────────────────────────────────

def _cli_bootstrap(args: argparse.Namespace) -> int:
    state = bootstrap()
    print(f"Pool size: {len(state.pool)}, next_bundle_id: {state.next_bundle_id}")
    for b in state.pool:
        print(f"  {b.id}: {b.counters}  ({b.created_because})")
    if args.sync:
        from train.common import ChampionSync
        ChampionSync.sync_all(wait=True)
        print("Synced champions/ tree to all remote servers")
    return 0


def _cli_show(args: argparse.Namespace) -> int:
    state = _read_state()
    out = {
        "pool": [asdict(b) for b in state.pool],
        "next_bundle_id": state.next_bundle_id,
    }
    print(json.dumps(out, indent=2))
    return 0


def _cli_promote(args: argparse.Namespace) -> int:
    bundle = add_promoted_bundle(args.model_key, args.counter)
    print(f"Added bundle: {bundle.id}  {bundle.counters}")
    if args.sync:
        from train.common import ChampionSync
        ChampionSync.sync_bundles_only(wait=True)
        print("Synced bundles.json to all remote servers")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="champion_pool")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("bootstrap", help="Initial bootstrap from trainingmodel ONNX")
    c.add_argument("--no-sync", dest="sync", action="store_false",
                   help="Skip rsync to remote servers (default: sync)")
    c.set_defaults(func=_cli_bootstrap, sync=True)

    c = sub.add_parser("show", help="Print bundles.json contents")
    c.set_defaults(func=_cli_show)

    c = sub.add_parser("promote", help="Manually add a promoted bundle")
    c.add_argument("model_key")
    c.add_argument("counter", type=int)
    c.add_argument("--no-sync", dest="sync", action="store_false",
                   help="Skip rsync to remote servers (default: sync)")
    c.set_defaults(func=_cli_promote, sync=True)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
