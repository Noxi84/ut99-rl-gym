# python-trainer/train/common/SessionPaths.py
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from train.common import PropertyReader


@dataclass(frozen=True)
class SessionPaths:
    """Fixed directory layout — no session IDs in paths."""
    sessions_base_dir: Path
    trainingdata_dir: Path
    trainingmodel_dir: Path
    replay_buffer_dir: Path
    recordings_dir: Path
    logs_dir: Path


def get_session_paths(create_dirs: bool = True, model_key: str | None = None) -> SessionPaths:
    sessions_base_dir = Path(PropertyReader.get_sessions_dir()).expanduser().resolve()
    trainingdata_dir = sessions_base_dir / "csv-training-data"
    if model_key:
        trainingdata_dir = trainingdata_dir / model_key

    sp = SessionPaths(
        sessions_base_dir=sessions_base_dir,
        trainingdata_dir=trainingdata_dir,
        trainingmodel_dir=sessions_base_dir / "models" / "trainingmodel",
        replay_buffer_dir=sessions_base_dir / "rl-replay-buffer",
        recordings_dir=sessions_base_dir / "json-recording-sessions",
        logs_dir=sessions_base_dir / "logs",
    )

    if create_dirs:
        sp.trainingdata_dir.mkdir(parents=True, exist_ok=True)
        sp.trainingmodel_dir.mkdir(parents=True, exist_ok=True)
        sp.replay_buffer_dir.mkdir(parents=True, exist_ok=True)
        sp.recordings_dir.mkdir(parents=True, exist_ok=True)
        sp.logs_dir.mkdir(parents=True, exist_ok=True)

    return sp
