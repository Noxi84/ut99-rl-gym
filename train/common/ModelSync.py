"""
Syncs model files from the trainer to other servers after each save.

Reads the shared server inventory to determine which servers to sync to.
Skips the source machine itself. Runs rsync in the background so training
is not blocked by slow network transfers.

Supports ONNX files (to all servers) and .pt checkpoints (to SAC trainer machines).
"""
from __future__ import annotations

import os
import socket
import subprocess
import threading
from pathlib import Path

from train.common.ServerInventory import find_server_by_hostname, load_servers


def _parse_servers() -> list[dict]:
    """Load the normalized server inventory."""
    return [
        {
            "host": server["hostname"],
            "user": server["user"],
            "password": server["password"],
            "machine_id": server["machine_id"],
          "bc_trainer_slots": server["bc_trainer_slots"],
          "sac_trainer_slots": server["sac_trainer_slots"],
            "csv_writer_slots": server["csv_writer_slots"],
        }
        for server in load_servers()
    ]


def _get_local_server(servers: list[dict]) -> dict | None:
    env_machine_id = os.environ.get("UT99_MACHINE_ID", "").strip().lower()
    host_candidates = {
        os.environ.get("UT99_HOSTNAME", "").strip().lower(),
        socket.gethostname().strip().lower(),
        socket.getfqdn().strip().lower(),
    }
    host_candidates.discard("")
    short_candidates = {h.split(".", 1)[0] for h in host_candidates}

    if env_machine_id:
        for server in servers:
            if server["machine_id"].lower() == env_machine_id:
                return server

    hostname_server = find_server_by_hostname(
        os.environ.get("UT99_HOSTNAME", "") or socket.gethostname(),
        [
            {
                "hostname": server["host"],
                "machine_id": server["machine_id"],
            }
            for server in servers
        ],
    )
    if hostname_server is not None:
        for server in servers:
            if server["machine_id"] == hostname_server["machine_id"]:
                return server

    for server in servers:
        host = server["host"].lower()
        short_host = host.split(".", 1)[0]
        if host in host_candidates or short_host in short_candidates:
            return server
    return None


def _get_sync_targets() -> list[dict]:
    servers = _parse_servers()
    local_server = _get_local_server(servers)
    if local_server is None:
        return servers
    local_machine_id = local_server["machine_id"].lower()
    return [s for s in servers if s["machine_id"].lower() != local_machine_id]


def _rsync_file(onnx_path: str, server: dict) -> None:
    """Rsync an ONNX file + its .data file to a remote server atomically.

    Syncs to a staging directory first, then atomically renames into place
    on the remote to prevent SIGBUS when Java has the old .data memory-mapped.
    """
    onnx = Path(onnx_path)
    if not onnx.exists():
        return

    files = [str(onnx)]
    data_file = Path(str(onnx) + ".data")
    has_data = data_file.exists()
    if has_data:
        files.append(str(data_file))

    ssh_prefix = [
        "sshpass", "-p", server["password"],
        "ssh", "-o", "StrictHostKeyChecking=no",
        f"{server['user']}@{server['host']}",
    ]
    staging_dir = f"{onnx.parent}/.onnx_staging"
    remote_staging = f"{server['user']}@{server['host']}:{staging_dir}/"

    try:
        # Ensure both dirs exist
        subprocess.run(ssh_prefix + [f"mkdir -p {onnx.parent} {staging_dir}"],
                       capture_output=True, timeout=10)

        # Rsync to staging dir
        rsync_cmd = [
            "sshpass", "-p", server["password"],
            "rsync", "-az",
            "-e", "ssh -o StrictHostKeyChecking=no",
        ] + files + [remote_staging]
        subprocess.run(rsync_cmd, capture_output=True, timeout=30)

        # Atomically rename staging files into place
        mv_cmds = []
        if has_data:
            mv_cmds.append(f"mv -f {staging_dir}/{data_file.name} {onnx.parent}/{data_file.name}")
        mv_cmds.append(f"mv -f {staging_dir}/{onnx.name} {onnx.parent}/{onnx.name}")
        subprocess.run(ssh_prefix + [" && ".join(mv_cmds)],
                       capture_output=True, timeout=10)
    except Exception:
        pass


def _get_sac_trainer_targets() -> list[dict]:
    """Return remote servers that have sac_trainer_slots > 0."""
    servers = _parse_servers()
    local_server = _get_local_server(servers)
    local_machine_id = local_server["machine_id"].lower() if local_server else ""
    return [s for s in servers
            if s["machine_id"].lower() != local_machine_id and s["sac_trainer_slots"] > 0]


def _rsync_pt_file(pt_path: str, server: dict) -> None:
    """Rsync a .pt checkpoint file to a remote server."""
    pt = Path(pt_path)
    if not pt.exists():
        return
    try:
        ssh_prefix = [
            "sshpass", "-p", server["password"],
            "ssh", "-o", "StrictHostKeyChecking=no",
            f"{server['user']}@{server['host']}",
        ]
        subprocess.run(ssh_prefix + [f"mkdir -p {pt.parent}"],
                       capture_output=True, timeout=10)
        rsync_cmd = [
            "sshpass", "-p", server["password"],
            "rsync", "-az",
            "-e", "ssh -o StrictHostKeyChecking=no",
            str(pt),
            f"{server['user']}@{server['host']}:{pt.parent}/",
        ]
        subprocess.run(rsync_cmd, capture_output=True, timeout=60)
    except Exception:
        pass


def sync_pt_to_sac_trainers(pt_path: str) -> None:
    """Sync a .pt checkpoint to all remote SAC trainer machines in the background."""
    if os.environ.get("UT99_DISABLE_MODEL_SYNC", "").strip().lower() in {"1", "true", "yes", "on"}:
        return

    targets = _get_sac_trainer_targets()
    if not targets:
        return

    for server in targets:
        t = threading.Thread(
            target=_rsync_pt_file,
            args=(pt_path, server),
            daemon=True,
        )
        t.start()


def sync_onnx_to_servers(onnx_path: str, wait: bool = False) -> None:
    """Sync an ONNX file to all other known servers in the background.

    Call this after each ONNX export in any trainer. Spawns background
    threads so training is not blocked.

    ``wait=True`` blocks until every rsync thread completes (up to 120s each).
    Used by offline tooling (e.g. restore_to_champion) that must guarantee the
    push has landed on all servers before the process exits — the trainer hot
    path keeps the default fire-and-forget behaviour.
    """
    if os.environ.get("UT99_DISABLE_MODEL_SYNC", "").strip().lower() in {"1", "true", "yes", "on"}:
        return

    servers = _get_sync_targets()
    if not servers:
        return

    threads = []
    for server in servers:
        t = threading.Thread(
            target=_rsync_file,
            args=(onnx_path, server),
            daemon=True,
        )
        t.start()
        threads.append(t)

    if wait:
        for t in threads:
            t.join(timeout=120)
