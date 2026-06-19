# train/common/TrainerLogger.py
from __future__ import annotations

import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Optional, Dict, Any


def _to_int(v: str, default: int) -> int:
    try:
        return int(str(v).strip())
    except Exception:
        return default


def _to_bool(v: str, default: bool) -> bool:
    s = str(v).strip().lower()
    if s in ("1", "true", "yes", "y", "on"):
        return True
    if s in ("0", "false", "no", "n", "off"):
        return False
    return default


def setup_trainer_logger(
    session_dir: Path,
    model_key: str,
    *,
    logger_name: Optional[str] = None,
    level: int = logging.INFO,
    max_bytes: Optional[int] = None,
    backup_count: Optional[int] = None,
    also_console: bool = True,
) -> logging.Logger:
    """
    Creates a rolling logger under:
        <session_dir>/logs/<model_key>/trainer.log

    Rolling behavior:
        - RotatingFileHandler(maxBytes, backupCount)

    Env overrides (optional):
        UT99_LOG_MAX_MB        (default 50)
        UT99_LOG_BACKUP_COUNT  (default 10)
        UT99_LOG_CONSOLE       (default true)
        UT99_LOG_LEVEL         (default INFO)
    """
    session_dir = Path(session_dir).expanduser().resolve()
    logs_root = session_dir / "logs"
    model_logs_dir = logs_root / str(model_key)
    model_logs_dir.mkdir(parents=True, exist_ok=True)

    log_file = model_logs_dir / "trainer.log"

    # Defaults, overridable via env
    default_max_mb = _to_int(os.environ.get("UT99_LOG_MAX_MB", "50"), 50)
    default_backup = _to_int(os.environ.get("UT99_LOG_BACKUP_COUNT", "10"), 10)
    default_console = _to_bool(os.environ.get("UT99_LOG_CONSOLE", "true"), True)

    if max_bytes is None:
        max_bytes = int(default_max_mb) * 1024 * 1024
    if backup_count is None:
        backup_count = int(default_backup)
    also_console = bool(also_console) and bool(default_console)

    if logger_name is None:
        logger_name = f"ut99.trainer.{model_key}"

    logger = logging.getLogger(logger_name)

    # Avoid duplicate handlers if re-imported or re-run
    if getattr(logger, "_ut99_configured", False):
        return logger

    logger.setLevel(level)

    # Env log level override
    env_level = str(os.environ.get("UT99_LOG_LEVEL", "")).strip().upper()
    if env_level:
        mapped = getattr(logging, env_level, None)
        if isinstance(mapped, int):
            logger.setLevel(mapped)

    fmt = logging.Formatter(
        fmt="%(asctime)s.%(msecs)03d | %(levelname)s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    file_handler = RotatingFileHandler(
        filename=str(log_file),
        maxBytes=int(max_bytes),
        backupCount=int(backup_count),
        encoding="utf-8",
    )
    file_handler.setFormatter(fmt)
    file_handler.setLevel(logger.level)
    logger.addHandler(file_handler)

    if also_console:
        # Route to stdout so tmux + tee captures the same formatted output as
        # the file handler. The default StreamHandler() target is stderr, which
        # gets line-buffering inconsistencies when interleaved with stdout via
        # `2>&1 | tee`.
        console_handler = logging.StreamHandler(stream=sys.stdout)
        console_handler.setFormatter(fmt)
        console_handler.setLevel(logger.level)
        logger.addHandler(console_handler)

    # Important: don't double-log via root logger
    logger.propagate = False

    logger._ut99_configured = True  # type: ignore[attr-defined]
    logger.info("Logger initialized.")
    logger.info("log_file=%s | maxBytes=%s | backupCount=%s", str(log_file), int(max_bytes), int(backup_count))
    return logger


def log_print(logger: logging.Logger, msg: str, level: str = "info") -> None:
    """Log a message via the configured logger.

    Output goes to:
      - the rolling trainer.log file (RotatingFileHandler)
      - stdout (StreamHandler, when also_console is on)

    Both handlers use the same `<timestamp> | <LEVEL> | <name> | <msg>` format,
    so tmux/tee captures of stdout match trainer.log line-for-line.
    """
    lvl = (level or "info").strip().lower()
    if lvl == "debug":
        logger.debug(msg)
    elif lvl == "warning" or lvl == "warn":
        logger.warning(msg)
    elif lvl == "error":
        logger.error(msg)
    elif lvl == "critical":
        logger.critical(msg)
    else:
        logger.info(msg)


def log_kv_block(logger: logging.Logger, title: str, values: Dict[str, Any]) -> None:
    """
    Nice consistent config dump (similar across trainers).
    """
    logger.info("===== %s =====", title)
    keys = sorted(values.keys())
    for k in keys:
        try:
            v = values[k]
        except Exception:
            v = "<unreadable>"
        logger.info("%s = %s", k, v)
    logger.info("===== end: %s =====", title)
