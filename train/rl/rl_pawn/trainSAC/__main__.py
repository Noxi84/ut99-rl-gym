"""SAC trainer entry point for the joint VR+shooting policy.

Usage:
    python -m train.rl.rl_pawn.trainSAC

The entrypoint itself is intentionally thin and delegates model-key/config
loading to ``config_loader`` and execution to ``training_loop.run_sac``.
"""
from __future__ import annotations

from train.common.SessionPaths import get_session_paths
from train.common.TrainerLogger import setup_trainer_logger
from train.rl.rl_pawn.trainSAC.config_loader import (
    JOINT_MODEL_KEY, load_joint_sac_config,
)
from train.rl.rl_pawn.trainSAC.training_loop import run_sac


def main():
    bundle = load_joint_sac_config()
    SP = get_session_paths(create_dirs=True, model_key=JOINT_MODEL_KEY)
    logger = setup_trainer_logger(
        session_dir=SP.sessions_base_dir,
        model_key=f"{JOINT_MODEL_KEY}_sac",
    )
    buffer_dir = SP.replay_buffer_dir / JOINT_MODEL_KEY
    buffer_dir.mkdir(parents=True, exist_ok=True)
    run_sac(bundle, buffer_dir, SP.trainingmodel_dir, logger)


if __name__ == "__main__":
    main()
