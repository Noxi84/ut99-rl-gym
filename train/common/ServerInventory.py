from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
_SERVERS_JSON = _PROJECT_ROOT / "resources" / "config" / "servers.json"
# SSH password lives in a NON-tracked secrets file (or env var) — never in
# servers.json, which is committed to git. See secrets.local.json.example.
_SECRETS_JSON = _PROJECT_ROOT / "resources" / "config" / "secrets.local.json"
_SSH_PASSWORD_ENV = "UT99_SSH_PASSWORD"


def _fail(message: str) -> RuntimeError:
    return RuntimeError(f"Server inventory error: {message}")


def _resolve_ssh_password() -> str:
    """Resolve the shared SSH password from a non-tracked source.

    Resolution order (no fallback — fail hard if neither is set, consistent
    with the project's no-config-fallbacks rule):
      1. env var ``UT99_SSH_PASSWORD``
      2. ``resources/config/secrets.local.json`` -> key ``ssh_password``

    The password is intentionally kept out of ``servers.json`` (committed to
    git). The secrets file is git-ignored and distributed to peers by the
    rsync-based ``sync-code.sh`` deploy step. See ``secrets.local.json.example``.
    """
    env_value = os.environ.get(_SSH_PASSWORD_ENV)
    if env_value is not None and env_value.strip():
        return env_value
    if _SECRETS_JSON.is_file():
        with _SECRETS_JSON.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        if not isinstance(data, dict):
            raise _fail(f"{_SECRETS_JSON} must contain a JSON object")
        password = data.get("ssh_password")
        if not isinstance(password, str) or not password.strip():
            raise _fail(f"{_SECRETS_JSON} must define a non-empty 'ssh_password' string")
        return password
    raise _fail(
        f"SSH password not configured. Set ${_SSH_PASSWORD_ENV} or create "
        f"{_SECRETS_JSON} (copy from secrets.local.json.example): "
        '{"ssh_password": "..."}'
    )


def _require_dict(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise _fail(f"{path} must be an object")
    return value


def _require_str(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise _fail(f"{path} must be a non-empty string")
    return value


def _coerce_bool(value: Any, path: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"true", "yes", "on", "1"}:
            return True
        if lowered in {"false", "no", "off", "0"}:
            return False
    raise _fail(f"{path} must be a boolean")


def _coerce_int(value: Any, path: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise _fail(f"{path} must be an integer")
    return value


def _coerce_float(value: Any, path: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise _fail(f"{path} must be numeric")
    return float(value)


def _coerce_trainer_slots(value: Any, path: str) -> int:
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"true", "yes", "on"}:
            return 1
        if lowered in {"false", "no", "off", ""}:
            return 0
        try:
            return int(lowered)
        except ValueError as exc:
            raise _fail(f"{path} must be an integer or trainer boolean") from exc
    raise _fail(f"{path} must be an integer or trainer boolean")


def _normalize_env(value: Any, path: str) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise _fail(f"{path} must be an object")
    out: dict[str, str] = {}
    for key, env_value in value.items():
        if not isinstance(key, str) or not key:
            raise _fail(f"{path} keys must be non-empty strings")
        out[key] = str(env_value)
    return out


def _normalize_machine(machine: dict[str, Any], index: int, ssh_password: str) -> dict[str, Any]:
    base = f"machines[{index}]"
    ssh = _require_dict(machine.get("ssh"), f"{base}.ssh")
    capacity = _require_dict(machine.get("capacity"), f"{base}.capacity")
    roles = _require_dict(machine.get("roles"), f"{base}.roles")
    ports = _require_dict(machine.get("ports"), f"{base}.ports")
    gameplay = _require_dict(machine.get("gameplay"), f"{base}.gameplay")

    gpu_instances = _coerce_int(capacity.get("gpu_instances"), f"{base}.capacity.gpu_instances")
    cpu_instances = _coerce_int(capacity.get("cpu_instances"), f"{base}.capacity.cpu_instances")
    bc_trainer_slots = _coerce_trainer_slots(roles.get("bc_trainer_slots"), f"{base}.roles.bc_trainer_slots")
    sac_trainer_slots = _coerce_trainer_slots(roles.get("sac_trainer_slots"), f"{base}.roles.sac_trainer_slots")
    csv_writer_slots = _coerce_int(roles.get("csv_writer_slots"), f"{base}.roles.csv_writer_slots")
    bc_trainer_priority = _coerce_int(roles.get("bc_trainer_priority"), f"{base}.roles.bc_trainer_priority")

    if gpu_instances < 0 or cpu_instances < 0:
        raise _fail(f"{base}.capacity instance counts must be >= 0")
    if bc_trainer_slots < 0 or sac_trainer_slots < 0 or csv_writer_slots < 0:
        raise _fail(f"{base}.roles slot counts must be >= 0")

    return {
        "hostname": _require_str(machine.get("hostname"), f"{base}.hostname"),
        "user": _require_str(ssh.get("user"), f"{base}.ssh.user"),
        "password": ssh_password,
        "machine_id": _require_str(machine.get("machine_id"), f"{base}.machine_id"),
        "gpu_instances": gpu_instances,
        "cpu_instances": cpu_instances,
        "instances_raw": f"{gpu_instances}/{cpu_instances}",
        "cuda_enabled": _coerce_bool(capacity.get("cuda_enabled"), f"{base}.capacity.cuda_enabled"),
      "bc_trainer_slots": bc_trainer_slots,
      "sac_trainer_slots": sac_trainer_slots,
        "csv_writer_slots": csv_writer_slots,
        "bc_trainer_priority": bc_trainer_priority,
        "display_base": _coerce_int(ports.get("display_base"), f"{base}.ports.display_base"),
        "web_port_base": _coerce_int(ports.get("web_port_base"), f"{base}.ports.web_port_base"),
        "game_port_base": _coerce_int(ports.get("game_port_base"), f"{base}.ports.game_port_base"),
        "game_port_step": _coerce_int(ports.get("game_port_step"), f"{base}.ports.game_port_step"),
        "udp_port_base": _coerce_int(ports.get("udp_port_base"), f"{base}.ports.udp_port_base"),
        "state_udp_port_base": _coerce_int(ports.get("state_udp_port_base"), f"{base}.ports.state_udp_port_base"),
        "game_speed": _coerce_float(gameplay.get("speed"), f"{base}.gameplay.speed"),
        "game_style": _require_str(gameplay.get("style"), f"{base}.gameplay.style"),
        "env": _normalize_env(machine.get("env", {}), f"{base}.env"),
    }


def _load_from_json(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        root = json.load(handle)
    machines = root.get("machines")
    if not isinstance(machines, list):
        raise _fail(f"{path} must contain a 'machines' array")
    ssh_password = _resolve_ssh_password()
    return [_normalize_machine(_require_dict(machine, f"machines[{index}]"), index, ssh_password) for index, machine in enumerate(machines)]


def _validate_servers(servers: list[dict[str, Any]]) -> None:
    machine_ids: set[str] = set()
    hostnames: set[str] = set()
    for server in servers:
        machine_id = server["machine_id"].strip().lower()
        hostname = server["hostname"].strip().lower()
        if machine_id in machine_ids:
            raise _fail(f"duplicate machine_id: {server['machine_id']}")
        if hostname in hostnames:
            raise _fail(f"duplicate hostname: {server['hostname']}")
        machine_ids.add(machine_id)
        hostnames.add(hostname)


def load_servers() -> list[dict[str, Any]]:
    if not _SERVERS_JSON.is_file():
        raise _fail(f"canonical inventory not found: {_SERVERS_JSON}")
    servers = _load_from_json(_SERVERS_JSON)
    _validate_servers(servers)
    return servers


def find_server_by_hostname(hostname: str, servers: list[dict[str, Any]] | None = None) -> dict[str, Any] | None:
    haystack = servers if servers is not None else load_servers()
    lowered = hostname.strip().lower()
    short = lowered.split(".", 1)[0]
    for server in haystack:
        candidate = server["hostname"].lower()
        if lowered == candidate or short == candidate.split(".", 1)[0]:
            return server
    return None


def _print_list_tsv() -> int:
    for server in load_servers():
        print("\t".join([
            server["hostname"],
            server["user"],
            server["password"],
            server["machine_id"],
            server["instances_raw"],
            "true" if server["cuda_enabled"] else "false",
          str(server["bc_trainer_slots"]),
          str(server["sac_trainer_slots"]),
            str(server["csv_writer_slots"]),
            str(server["bc_trainer_priority"]),
            str(server["display_base"]),
            str(server["web_port_base"]),
            str(server["game_port_base"]),
            str(server["game_port_step"]),
            str(server["udp_port_base"]),
            str(server["state_udp_port_base"]),
            str(server["game_speed"]),
            server["game_style"],
            json.dumps(server["env"], separators=(",", ":"), sort_keys=True),
        ]))
    return 0


def _validate_command() -> int:
    servers = load_servers()
    bc_count = sum(1 for server in servers if server["bc_trainer_slots"] > 0)
    sac_count = sum(1 for server in servers if server["sac_trainer_slots"] > 0)
    print(f"Validated server inventory: {len(servers)} machine(s), {bc_count} BC-trainer-capable, {sac_count} SAC-trainer-capable")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    if not args:
        print("Usage: python -m train.common.ServerInventory <validate|list-tsv>", file=sys.stderr)
        return 1
    command = args[0]
    if command == "validate":
        return _validate_command()
    if command == "list-tsv":
        return _print_list_tsv()
    print(f"Unknown command: {command}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
