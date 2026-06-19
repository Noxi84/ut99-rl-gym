"""Champion snapshot store — per-model frozen ONNX archive.

Layout under sessions_dir:
    models/champions/
    └── {model_key}/
        ├── 0001/
        │   ├── {model_key}.onnx
        │   ├── {model_key}.onnx.data    (optional, when external tensor data exists)
        │   └── snapshot.json
        ├── 0002-flak-fix/                (optional human tag suffix)
        └── ...

Counter is monotonic per model, atomic via flock on champions/{model_key}/.lock.
Fingerprints (sha256 over canonicalized JSON of features.json / model.json /
rewards.json) are stored in snapshot.json so consumers can hard-reject
incompatible champions at load time.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import re
import shutil
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from train.common.SessionPaths import get_session_paths

_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent

_COUNTER_DIR_RE = re.compile(r"^(\d{4,})(?:-(.+))?$")


@dataclass
class SnapshotMeta:
    counter: int
    tag: Optional[str]
    model_key: str
    created_at: str
    created_from_commit: Optional[str]
    feature_fingerprint: str
    arch_fingerprint: str
    rewards_fingerprint: str
    kpi_at_snapshot: Dict[str, Any] = field(default_factory=dict)
    match_history: List[Dict[str, Any]] = field(default_factory=list)


def champions_root() -> Path:
    sp = get_session_paths(create_dirs=True)
    root = sp.sessions_base_dir / "models" / "champions"
    root.mkdir(parents=True, exist_ok=True)
    return root


def model_champions_dir(model_key: str) -> Path:
    d = champions_root() / model_key
    d.mkdir(parents=True, exist_ok=True)
    return d


def _model_resource_dir(model_key: str) -> Path:
    return _PROJECT_ROOT / "resources" / "models" / model_key


def compute_json_fingerprint(json_path: Path) -> str:
    """sha256 over the raw file bytes — identical across Python and Java.

    Returns 'sha256:<hex>'. Raw-byte hash is sensitive to trivial whitespace
    changes, but that is acceptable: features.json/model.json are checked
    into git and rarely reformatted, and cross-language byte-equality is
    robust where canonical-JSON serialization could subtly differ between
    Python's json and Jackson.
    """
    with json_path.open("rb") as f:
        data = f.read()
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _git_head_commit() -> Optional[str]:
    head = _PROJECT_ROOT / ".git" / "HEAD"
    if not head.exists():
        return None
    try:
        ref = head.read_text().strip()
        if ref.startswith("ref:"):
            ref_path = _PROJECT_ROOT / ".git" / ref[5:].strip()
            if ref_path.exists():
                return ref_path.read_text().strip()[:8]
        return ref[:8]
    except OSError:
        return None


def _list_counter_dirs(model_dir: Path) -> List[Path]:
    out: List[Path] = []
    if not model_dir.exists():
        return out
    for entry in model_dir.iterdir():
        if not entry.is_dir():
            continue
        if _COUNTER_DIR_RE.match(entry.name):
            out.append(entry)
    out.sort(key=lambda p: int(_COUNTER_DIR_RE.match(p.name).group(1)))
    return out


def _next_counter(model_dir: Path) -> int:
    existing = _list_counter_dirs(model_dir)
    if not existing:
        return 1
    return int(_COUNTER_DIR_RE.match(existing[-1].name).group(1)) + 1


def _counter_dir_name(counter: int, tag: Optional[str]) -> str:
    base = f"{counter:04d}"
    if tag:
        sanitized = re.sub(r"[^a-zA-Z0-9_-]+", "-", tag).strip("-")
        if sanitized:
            return f"{base}-{sanitized}"
    return base



def list_snapshots(model_key: str) -> List[Path]:
    return _list_counter_dirs(model_champions_dir(model_key))


def find_snapshot_dir(model_key: str, counter: int) -> Optional[Path]:
    for d in _list_counter_dirs(model_champions_dir(model_key)):
        if int(_COUNTER_DIR_RE.match(d.name).group(1)) == counter:
            return d
    return None


def read_meta(snapshot_dir: Path) -> SnapshotMeta:
    meta_file = snapshot_dir / "snapshot.json"
    with meta_file.open("r", encoding="utf-8") as f:
        data = json.load(f)
    known = {f.name for f in SnapshotMeta.__dataclass_fields__.values()}
    return SnapshotMeta(**{k: v for k, v in data.items() if k in known})


def _write_meta(snapshot_dir: Path, meta: SnapshotMeta) -> None:
    meta_file = snapshot_dir / "snapshot.json"
    tmp = meta_file.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(asdict(meta), f, indent=2)
    tmp.rename(meta_file)


def current_fingerprints(model_key: str) -> Dict[str, str]:
    res = _model_resource_dir(model_key)
    return {
        "feature_fingerprint": compute_json_fingerprint(res / "features.json"),
        "arch_fingerprint": compute_json_fingerprint(res / "model.json"),
        "rewards_fingerprint": compute_json_fingerprint(res / "rewards.json"),
    }


def is_compatible(meta: SnapshotMeta, model_key: str) -> Dict[str, bool]:
    """Per-fingerprint compatibility. arch + features must match for inference;
    rewards mismatch is informational (forensics) and does not block loading.
    """
    cur = current_fingerprints(model_key)
    return {
        "feature": meta.feature_fingerprint == cur["feature_fingerprint"],
        "arch": meta.arch_fingerprint == cur["arch_fingerprint"],
        "rewards": meta.rewards_fingerprint == cur["rewards_fingerprint"],
    }


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def create_snapshot(model_key: str, src_onnx: Path,
                    tag: Optional[str] = None,
                    kpi_at_snapshot: Optional[Dict[str, Any]] = None,
                    src_pt: Optional[Path] = None) -> Path:
    """Atomically create a new snapshot directory under champions/{model_key}/.

    Counter is allocated under flock; ONNX (+ optional .data sibling) is
    copied into a staging dir, snapshot.json written, then directory is
    renamed into place. Returns the new snapshot directory path.

    The optional `src_pt` argument bundles a torch checkpoint (actor +
    optimizer state) into the snapshot dir as `{model_key}.pt`. Required
    for durable rollback: a rollback restores both ONNX (for the bots)
    AND .pt (for the trainer's actor+optimizer state, so SAC doesn't
    immediately re-export the post-rollback policy back to the
    pre-rollback weights via residual gradient momentum).
    """
    src_onnx = Path(src_onnx)
    if not src_onnx.exists():
        raise FileNotFoundError(f"Source ONNX not found: {src_onnx}")
    src_onnx_data = Path(str(src_onnx) + ".data")
    src_pt = Path(src_pt) if src_pt is not None else None
    if src_pt is not None and not src_pt.exists():
        raise FileNotFoundError(f"Source .pt not found: {src_pt}")

    model_dir = model_champions_dir(model_key)
    lock_path = model_dir / ".lock"
    with lock_path.open("w") as lock_f:
        fcntl.flock(lock_f, fcntl.LOCK_EX)
        try:
            counter = _next_counter(model_dir)
            dir_name = _counter_dir_name(counter, tag)
            snapshot_dir = model_dir / dir_name
            staging = model_dir / f".staging-{counter:04d}"
            if staging.exists():
                shutil.rmtree(staging)
            staging.mkdir()

            # ONNX always goes in as <model_key>.onnx so SnapshotResolver
            # finds it by canonical name. The src filename may differ
            # (e.g. <mk>_sac_delta_baseline.onnx) — rename on copy.
            # When the source uses external_data, a simple shutil.copy preserves
            # the original .data filename reference inside the .onnx file. Loading
            # the renamed copy with ONNX Runtime would then fail because it tries
            # to open the original .data filename which doesn't exist next to
            # the renamed file. Re-serialize via onnx.save_model so the external
            # reference matches the renamed sibling.
            target_onnx = staging / f"{model_key}.onnx"
            target_data = staging / f"{model_key}.onnx.data"
            if src_onnx_data.exists():
                import onnx
                model_proto = onnx.load(str(src_onnx), load_external_data=True)
                for init in model_proto.graph.initializer:
                    del init.external_data[:]
                    init.data_location = onnx.TensorProto.DEFAULT
                onnx.save_model(
                    model_proto,
                    str(target_onnx),
                    save_as_external_data=True,
                    all_tensors_to_one_file=True,
                    location=target_data.name,
                    size_threshold=1024,
                )
            else:
                shutil.copy2(str(src_onnx), str(target_onnx))
            if src_pt is not None:
                shutil.copy2(str(src_pt), str(staging / f"{model_key}.pt"))

            fps = current_fingerprints(model_key)
            meta = SnapshotMeta(
                counter=counter,
                tag=tag,
                model_key=model_key,
                created_at=_now_iso(),
                created_from_commit=_git_head_commit(),
                feature_fingerprint=fps["feature_fingerprint"],
                arch_fingerprint=fps["arch_fingerprint"],
                rewards_fingerprint=fps["rewards_fingerprint"],
                kpi_at_snapshot=kpi_at_snapshot or {},
                match_history=[],
            )
            _write_meta(staging, meta)
            staging.rename(snapshot_dir)
            return snapshot_dir
        finally:
            fcntl.flock(lock_f, fcntl.LOCK_UN)


def delete_snapshot(model_key: str, counter: int) -> bool:
    d = find_snapshot_dir(model_key, counter)
    if d is None:
        return False
    shutil.rmtree(d)
    return True



def all_model_keys() -> List[str]:
    idx_path = _PROJECT_ROOT / "resources" / "models" / "index.json"
    idx = json.loads(idx_path.read_text(encoding="utf-8"))
    return [e["model_key"] for e in idx["models"]]


# ── CLI ────────────────────────────────────────────────────────────────

def _cli_create(args: argparse.Namespace) -> int:
    sp = get_session_paths(create_dirs=True)
    src = sp.trainingmodel_dir / f"{args.model_key}.onnx"
    snapshot_dir = create_snapshot(args.model_key, src, tag=args.tag)
    print(f"Created snapshot: {snapshot_dir}")
    if args.sync:
        from train.common import ChampionSync
        ChampionSync.sync_snapshot(args.model_key, snapshot_dir, wait=True)
        print(f"Synced to all remote servers (or skipped if disabled / single-machine)")
    return 0


def _cli_list(args: argparse.Namespace) -> int:
    models = [args.model_key] if args.model_key else all_model_keys()
    for mk in models:
        snapshots = list_snapshots(mk)
        if not snapshots:
            print(f"{mk}: (no snapshots)")
            continue
        print(f"{mk}:")
        for d in snapshots:
            try:
                meta = read_meta(d)
                compat = is_compatible(meta, mk)
                arch_mark = "OK" if compat["arch"] else "BROKEN"
                feat_mark = "OK" if compat["feature"] else "BROKEN"
                tag = f" [{meta.tag}]" if meta.tag else ""
                print(f"  {meta.counter:04d}{tag}  {meta.created_at}  "
                      f"arch:{arch_mark} feat:{feat_mark}  "
                      f"matches:{len(meta.match_history)}")
            except (FileNotFoundError, json.JSONDecodeError) as e:
                print(f"  {d.name}  ERROR: {e}")
    return 0


def _cli_show(args: argparse.Namespace) -> int:
    parts = args.snapshot_id.split("/")
    if len(parts) != 2:
        print(f"Expected <model_key>/<counter>, got: {args.snapshot_id}",
              file=sys.stderr)
        return 1
    mk, counter_str = parts
    try:
        counter = int(counter_str)
    except ValueError:
        print(f"Invalid counter: {counter_str}", file=sys.stderr)
        return 1
    d = find_snapshot_dir(mk, counter)
    if d is None:
        print(f"Snapshot not found: {args.snapshot_id}", file=sys.stderr)
        return 1
    meta = read_meta(d)
    compat = is_compatible(meta, mk)
    print(json.dumps({
        "path": str(d),
        "compat": compat,
        **asdict(meta),
    }, indent=2))
    return 0


def _cli_validate(args: argparse.Namespace) -> int:
    bad = 0
    for mk in all_model_keys():
        for d in list_snapshots(mk):
            try:
                meta = read_meta(d)
                compat = is_compatible(meta, mk)
                if not (compat["feature"] and compat["arch"]):
                    print(f"INCOMPATIBLE: {mk}/{meta.counter:04d}  "
                          f"feature={'OK' if compat['feature'] else 'BROKEN'}  "
                          f"arch={'OK' if compat['arch'] else 'BROKEN'}")
                    bad += 1
            except Exception as e:
                print(f"ERROR: {d}: {e}")
                bad += 1
    if bad == 0:
        print("All snapshots compatible.")
    return 1 if bad else 0


def _cli_delete(args: argparse.Namespace) -> int:
    if not delete_snapshot(args.model_key, args.counter):
        print(f"Snapshot not found: {args.model_key}/{args.counter:04d}")
        return 1
    print(f"Deleted: {args.model_key}/{args.counter:04d}")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="champion_store")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("create", help="Snapshot current trainingmodel ONNX")
    c.add_argument("model_key")
    c.add_argument("--tag", default=None)
    c.add_argument("--no-sync", dest="sync", action="store_false",
                   help="Skip rsync to remote servers (default: sync)")
    c.set_defaults(func=_cli_create, sync=True)

    c = sub.add_parser("list", help="List snapshots")
    c.add_argument("--model-key", default=None, dest="model_key")
    c.set_defaults(func=_cli_list)

    c = sub.add_parser("show", help="Show snapshot metadata")
    c.add_argument("snapshot_id", help="<model_key>/<counter>")
    c.set_defaults(func=_cli_show)

    c = sub.add_parser("validate", help="Fingerprint check across all snapshots")
    c.set_defaults(func=_cli_validate)

    c = sub.add_parser("delete", help="Delete a snapshot directory")
    c.add_argument("model_key")
    c.add_argument("counter", type=int)
    c.set_defaults(func=_cli_delete)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
