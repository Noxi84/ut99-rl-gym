"""SAC shared kernel.

Small, stable API used by the joint VR+shooting SAC trainer in
`train/rl/rl_pawn/trainSAC/`.

Only contains code that has no reason to diverge per model variant:
twin critics, replay buffer, async prefetch, config dataclass,
pure gradient steps, and pure checkpoint I/O. All orchestration
(training loop, BC anchor, smoothness penalty, export gates,
probes, rollback) lives in the model-specific trainer directory.
"""
