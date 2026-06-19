"""Inspecteer het rl_pawn SAC-checkpoint: top-level keys + actor/critic input-laag shapes.
Read-only — verandert niets. Draai op de trainer-host (4090) waar torch + het checkpoint leven."""
import sys
import torch

CKPT = "/home/kris/projects/ut99neuralnet-sessions/models/trainingmodel/rl_pawn_sac_checkpoint.pt"
path = sys.argv[1] if len(sys.argv) > 1 else CKPT

ckpt = torch.load(path, map_location="cpu", weights_only=False)
print("=== TOP-LEVEL KEYS ===")
if isinstance(ckpt, dict):
    for k, v in ckpt.items():
        kind = type(v).__name__
        if isinstance(v, dict):
            print(f"  {k}: dict({len(v)} keys)")
        elif torch.is_tensor(v):
            print(f"  {k}: tensor{tuple(v.shape)}")
        else:
            print(f"  {k}: {kind} = {str(v)[:80]}")
else:
    print("  checkpoint is not a dict:", type(ckpt).__name__)
    sys.exit(0)


def dump_sd(name, sd):
    print(f"\n=== {name} ({len(sd)} params) — input-laag relevante shapes ===")
    for k, v in sd.items():
        if not torch.is_tensor(v):
            continue
        low = k.lower()
        if any(t in low for t in ("lstm.weight_ih", "weight_ih_l0", "heads.", "self_global_idx",
                                  ".0.weight", "enemy_idx", "teammate_idx", "map_embedding")):
            print(f"  {k}: {tuple(v.shape)}")


for key in ("actor", "actor_state_dict", "policy", "model", "policy_state_dict"):
    if key in ckpt and isinstance(ckpt[key], dict):
        dump_sd(f"ACTOR[{key}]", ckpt[key])
        break

for key in ("critic", "critic_state_dict", "q1", "q1_state_dict", "critics"):
    if key in ckpt and isinstance(ckpt[key], dict):
        dump_sd(f"CRITIC[{key}]", ckpt[key])
        break

# Config indien aanwezig
for key in ("config", "cfg", "meta", "input_size", "seq_len"):
    if key in ckpt:
        v = ckpt[key]
        print(f"\n=== CONFIG[{key}] ===")
        print(" ", str(v)[:500])
