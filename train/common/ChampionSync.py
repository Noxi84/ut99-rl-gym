"""Distribute the champion archive + bundles.json to all non-local servers.

Mirrors ModelSync.py's contract: rsync to remote (same absolute path as
local — sessions_dir is mirrored across machines under /home/kris/projects/
ut99neuralnet-sessions/). ONNX files inside snapshot dirs are written once
at create-time and never overwritten in place — the immutability of
champions removes the need for the staging+rename trick that ModelSync uses
for trainingmodel/{mk}.onnx (where Java memory-maps the active file).

Skip rules:
  - UT99_DISABLE_CHAMPION_SYNC env var set → silent skip
  - No remote targets (dev-only) → silent skip
"""
from __future__ import annotations

import argparse
import os
import subprocess
import threading
from pathlib import Path
from typing import Callable, List, Optional

from train.common import champion_store
from train.common.ModelSync import _get_sync_targets


def _disabled() -> bool:
    return os.environ.get("UT99_DISABLE_CHAMPION_SYNC", "").strip().lower() in {
        "1", "true", "yes", "on",
    }


def _ssh_prefix(server: dict) -> List[str]:
    return [
        "sshpass", "-p", server["password"],
        "ssh", "-o", "StrictHostKeyChecking=no",
        f"{server['user']}@{server['host']}",
    ]


def _rsync_dir(local_dir: Path, remote_path: str, server: dict) -> None:
    """Rsync `local_dir/` (contents) to `remote_path/` on server.
    Additive (no --delete) — champions are immutable once created and
    deletes are explicit via the delete CLI."""
    if not local_dir.exists():
        return
    try:
        subprocess.run(
            _ssh_prefix(server) + [f"mkdir -p {remote_path}"],
            capture_output=True, timeout=10,
        )
        cmd = [
            "sshpass", "-p", server["password"],
            "rsync", "-az",
            "-e", "ssh -o StrictHostKeyChecking=no",
            f"{local_dir}/",
            f"{server['user']}@{server['host']}:{remote_path}/",
        ]
        subprocess.run(cmd, capture_output=True, timeout=300)
    except Exception:
        pass


def _rsync_file(local_file: Path, remote_path: str, server: dict) -> None:
    if not local_file.exists():
        return
    try:
        parent = os.path.dirname(remote_path)
        subprocess.run(
            _ssh_prefix(server) + [f"mkdir -p {parent}"],
            capture_output=True, timeout=10,
        )
        cmd = [
            "sshpass", "-p", server["password"],
            "rsync", "-az",
            "-e", "ssh -o StrictHostKeyChecking=no",
            str(local_file),
            f"{server['user']}@{server['host']}:{remote_path}",
        ]
        subprocess.run(cmd, capture_output=True, timeout=60)
    except Exception:
        pass


def _spawn(fn: Callable, args: tuple) -> threading.Thread:
    t = threading.Thread(target=fn, args=args, daemon=True)
    t.start()
    return t


def _wait(threads: List[threading.Thread], timeout_s: float) -> None:
    for t in threads:
        t.join(timeout=timeout_s)


def sync_snapshot(model_key: str, snapshot_dir: Path, wait: bool = False) -> None:
    """Sync one snapshot directory + bundles.json to all non-local servers.
    Default fire-and-forget; wait=True blocks until threads complete (used
    by CLI so users see if anything went wrong before the script exits).
    """
    if _disabled():
        return
    targets = _get_sync_targets()
    if not targets:
        return

    root = champion_store.champions_root()
    rel = snapshot_dir.relative_to(root)
    remote_snapshot = f"{root}/{rel}"
    bundles = root / "bundles.json"

    threads: List[threading.Thread] = []
    for server in targets:
        threads.append(_spawn(_rsync_dir, (snapshot_dir, remote_snapshot, server)))
        if bundles.exists():
            threads.append(_spawn(_rsync_file, (bundles, f"{root}/bundles.json", server)))
    if wait:
        _wait(threads, timeout_s=300)


def sync_bundles_only(wait: bool = False) -> None:
    """Sync only bundles.json — used after promote when no new ONNX is added."""
    if _disabled():
        return
    targets = _get_sync_targets()
    if not targets:
        return
    root = champion_store.champions_root()
    bundles = root / "bundles.json"
    if not bundles.exists():
        return
    threads = [_spawn(_rsync_file, (bundles, f"{root}/bundles.json", server))
               for server in targets]
    if wait:
        _wait(threads, timeout_s=60)


def sync_all(wait: bool = False) -> None:
    """Sync the entire champions/ tree to all servers. Slower; used at
    startup, after reset, or for manual reconciliation."""
    if _disabled():
        return
    targets = _get_sync_targets()
    if not targets:
        return
    root = champion_store.champions_root()
    threads = [_spawn(_rsync_dir, (root, str(root), server)) for server in targets]
    if wait:
        _wait(threads, timeout_s=600)


# ── CLI ────────────────────────────────────────────────────────────────

def _cli_all(args: argparse.Namespace) -> int:
    sync_all(wait=True)
    print("sync_all complete")
    return 0


def _cli_snapshot(args: argparse.Namespace) -> int:
    d = champion_store.find_snapshot_dir(args.model_key, args.counter)
    if d is None:
        print(f"Snapshot not found: {args.model_key}/{args.counter:04d}")
        return 1
    sync_snapshot(args.model_key, d, wait=True)
    print(f"sync_snapshot complete: {args.model_key}/{args.counter:04d}")
    return 0


def _cli_bundles(args: argparse.Namespace) -> int:
    sync_bundles_only(wait=True)
    print("sync_bundles_only complete")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="ChampionSync")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("all", help="Sync entire champions/ tree")
    c.set_defaults(func=_cli_all)

    c = sub.add_parser("snapshot", help="Sync one snapshot dir + bundles.json")
    c.add_argument("model_key")
    c.add_argument("counter", type=int)
    c.set_defaults(func=_cli_snapshot)

    c = sub.add_parser("bundles", help="Sync only bundles.json")
    c.set_defaults(func=_cli_bundles)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
