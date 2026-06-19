"""Exporteer de N+8 ONNX-bestanden uit de GEMIGREERDE .pt-checkpoints (na
migrate_actor_features.py --apply). Breekt de feature-migratie-deadlock: de bots hebben de
nieuwe-input-size ONNX nodig vóór ze met de nieuwe features.json herstarten, maar de SAC-trainer
exporteert de main ONNX pas tijdens training (op nieuwe-size experience die er nog niet is).

Draait op de trainer-host (4090) met venv-python + PYTHONPATH=projectroot, NA --apply.

Usage:
  PYTHONPATH=/home/kris/projects/ut99neuralnet \
    /home/kris/projects/ut99neuralnet/.venv/bin/python3 scripts/migrate/export_migrated_onnx.py \
    --features /tmp/features_new.json
"""
import argparse, json, sys
from pathlib import Path
import numpy as np
import torch

from train.model.bc_sequence_network import (
    BCSequenceNetwork, export_actor_onnx, load_compatible_state_dict,
)

ARCH = dict(hidden_size=640, num_layers=2, dropout=0.15, player_hidden_dim=64,
            player_embed_dim=64, map_embedding_capacity=256, map_embedding_dim=16)
OUTPUT_SIZE = 10
SEQ_LEN = 24
MODEL_DIR = Path("/home/kris/projects/ut99neuralnet-sessions/models/trainingmodel")
REWARDGROUP_PLACEHOLDERS = ["rewardgroup0", "rewardgroup1", "rewardgroup2", "rewardgroup3"]

# (pt-bestand, onnx-bestand) — main + rollback-fallback + bc-baseline.
EXPORTS = [
    ("rl_pawn_sac.pt", "rl_pawn.onnx"),
    ("rl_pawn_sac_best.pt", "rl_pawn_sac_best.onnx"),
    ("rl_pawn_bc_baseline.pt", "rl_pawn_bc_baseline.onnx"),
]


def feats_from(path):
    d = json.load(open(path))
    out = []
    for g in d["feature_groups"]:
        out.extend(REWARDGROUP_PLACEHOLDERS if g.get("features_from") == "rewardgroups"
                   else g.get("features", []))
    return out


def build_actor(feats, device):
    return BCSequenceNetwork(
        feats, OUTPUT_SIZE, ARCH["hidden_size"], ARCH["num_layers"], ARCH["dropout"],
        player_hidden_dim=ARCH["player_hidden_dim"], player_embed_dim=ARCH["player_embed_dim"],
        map_embedding_capacity=ARCH["map_embedding_capacity"],
        map_embedding_dim=ARCH["map_embedding_dim"], expose_target_index=True,
    ).to(device).eval()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", default="/tmp/features_new.json")
    args = ap.parse_args()
    feats = feats_from(args.features)
    input_size = len(feats)
    device = torch.device("cpu")
    print(f"input_size={input_size} seq_len={SEQ_LEN}")

    import onnx
    for pt_name, onnx_name in EXPORTS:
        pt = MODEL_DIR / pt_name
        if not pt.exists():
            print(f"SKIP (missing): {pt_name}")
            continue
        ck = torch.load(pt, map_location="cpu", weights_only=False)
        msd = ck["model_state_dict"]
        lstm_in = next(v.shape[1] for k, v in msd.items()
                       if k.endswith("lstm.weight_ih_l0") and torch.is_tensor(v))
        actor = build_actor(feats, device)
        load_compatible_state_dict(actor, msd)
        out_path = MODEL_DIR / onnx_name
        export_actor_onnx(actor, str(out_path), SEQ_LEN, input_size, device)
        # validatie: input-shape moet [_, 24, input_size] zijn
        m = onnx.load(str(out_path), load_external_data=False)
        dims = [d.dim_value if d.dim_value > 0 else d.dim_param
                for d in m.graph.input[0].type.tensor_type.shape.dim]
        ok = dims[1] == SEQ_LEN and dims[2] == input_size
        print(f"  {onnx_name}: lstm_in={lstm_in} export-shape={dims} {'OK' if ok else 'FAIL!!'}")
        if not ok:
            sys.exit(1)
    print("ALLE EXPORTS OK")


if __name__ == "__main__":
    main()
