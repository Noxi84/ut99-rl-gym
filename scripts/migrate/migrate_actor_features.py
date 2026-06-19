"""Migreer rl_pawn actor-checkpoints van OUDE → NIEUWE input_features.

Context: 14 nieuwe self_global features (self_projectile{0-6}_enemyClosestApproach_norm
+ _enemyTimeToClosest_norm) zijn ACHTERAAN features.json toegevoegd. Die landen in de
self_global pathway op posities [S_old .. S_old+13] van de LSTM-input (self_global is het
eerste concat-blok; de enemy/teammate/map embeds schuiven +14 op). De bestaande
load_compatible_state_dict kopieert alleen de eerste `old` kolommen → dat zou de embed-
gewichten misalignen. Daarom migreren we hier expliciet: insert 14 NUL-kolommen op positie
S_old in lstm.weight_ih_l0, schuif de embed-kolommen door. Alle overige gewichten blijven
identiek → de policy is bit-voor-bit behouden, de nieuwe features dragen initieel 0 bij.

Verificatie: bouw het oude + nieuwe model, laad het checkpoint resp. de gemigreerde versie,
en check dat de output IDENTIEK is voor dezelfde oude-feature-input (nieuwe features arbitrair).

Usage (op de trainer-host, met venv-python + PYTHONPATH=projectroot):
  python scripts/migrate/migrate_actor_features.py --verify
  python scripts/migrate/migrate_actor_features.py --apply
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

import torch

from train.model.bc_sequence_network import BCSequenceNetwork
from train.model.player_feature_grouping import parse as parse_grouping

ARCH = dict(hidden_size=640, num_layers=2, dropout=0.15, player_hidden_dim=64,
            player_embed_dim=64, map_embedding_capacity=256, map_embedding_dim=16)
OUTPUT_SIZE = 10
LSTM_IH_KEY = "lstm.weight_ih_l0"

MODEL_DIR = Path("/home/kris/projects/ut99neuralnet-sessions/models/trainingmodel")
CHECKPOINTS = [
    "rl_pawn.pt", "rl_pawn_bc_baseline.pt", "rl_pawn_best.pt",
    "rl_pawn_sac.pt", "rl_pawn_sac_best.pt",
]


# Dynamische rewardgroup-kanalen (features.json group met features_from=rewardgroups).
# PropertyReader expandeert deze runtime naar placeholder-namen rewardgroup0..N-1. Voor
# rl_pawn zijn dat er 4 (Attack/Cover/Defend/DeathMatch). Ze tellen mee in de input-vector
# (input_size=2240, niet 2236) en zijn self_global. Zonder deze expansie wijkt de
# zelf-gebouwde grouping 4 features af van het getrainde model → shape-mismatch.
REWARDGROUP_PLACEHOLDERS = ["rewardgroup0", "rewardgroup1", "rewardgroup2", "rewardgroup3"]


def feats_from(path):
    d = json.load(open(path))
    out = []
    for g in d["feature_groups"]:
        if g.get("features_from") == "rewardgroups":
            out.extend(REWARDGROUP_PLACEHOLDERS)
        else:
            out.extend(g.get("features", []))
    return out


def build(feats):
    m = BCSequenceNetwork(
        feats, OUTPUT_SIZE, ARCH["hidden_size"], ARCH["num_layers"], ARCH["dropout"],
        player_hidden_dim=ARCH["player_hidden_dim"], player_embed_dim=ARCH["player_embed_dim"],
        map_embedding_capacity=ARCH["map_embedding_capacity"],
        map_embedding_dim=ARCH["map_embedding_dim"], expose_target_index=True,
    )
    m.eval()
    return m


def migrate_ih(old_W, s_old, delta):
    """Insert `delta` zero-columns at position s_old in a (out, in) weight."""
    out, old_in = old_W.shape
    new_W = torch.zeros(out, old_in + delta, dtype=old_W.dtype)
    new_W[:, :s_old] = old_W[:, :s_old]
    new_W[:, s_old + delta:] = old_W[:, s_old:]
    return new_W


def migrate_state(state, s_old, delta):
    out = {}
    for k, v in state.items():
        if k.endswith(LSTM_IH_KEY) and torch.is_tensor(v) and v.ndim == 2:
            out[k] = migrate_ih(v, s_old, delta)
        else:
            out[k] = v
    return out


def _flatten_out(o):
    if torch.is_tensor(o):
        return [o]
    if isinstance(o, (tuple, list)):
        r = []
        for x in o:
            r += _flatten_out(x)
        return r
    if isinstance(o, dict):
        r = []
        for x in o.values():
            r += _flatten_out(x)
        return r
    return []


def apply_migrated_weights(new_model, old_state, s_old, delta):
    """Return a state_dict for new_model: keep its (grouping-derived) index buffers like
    self_global_idx / enemy_idx_flat (which differ in shape after the feature add), but
    overwrite every shape-matching learnable weight with the OLD value — lstm.weight_ih_l0
    gets the zero-column insert. Buffers whose shape changed (e.g. self_global_idx 554→568)
    are left as the new model computed them."""
    target = new_model.state_dict()
    for k, v in old_state.items():
        if not torch.is_tensor(v):
            continue
        vv = migrate_ih(v, s_old, delta) if k.endswith(LSTM_IH_KEY) and v.ndim == 2 else v
        if k in target and target[k].shape == vv.shape:
            target[k] = vv
    return target


def verify(old_feats, new_feats, s_old, delta, sample_state):
    """Build old+new model, load sample_state resp. migrated, assert identical output."""
    torch.manual_seed(0)
    old_m = build(old_feats)
    new_m = build(new_feats)
    old_m.load_state_dict({k: v for k, v in sample_state.items() if k in old_m.state_dict()},
                          strict=False)
    new_m.load_state_dict(apply_migrated_weights(new_m, sample_state, s_old, delta), strict=True)

    n_old, n_new = len(old_feats), len(new_feats)
    map_idx = parse_grouping(new_feats).map_id_index
    B, L = 3, 12
    torch.manual_seed(42)
    x_old = torch.randn(B, L, n_old)
    x_new = torch.zeros(B, L, n_new)
    x_new[:, :, :n_old] = x_old
    x_new[:, :, n_old:] = torch.randn(B, L, delta)  # arbitrary new-feature values
    # map_id must be a valid embedding index in BOTH inputs
    if map_idx is not None:
        x_old[:, :, map_idx] = 0.0
        x_new[:, :, map_idx] = 0.0
    with torch.no_grad():
        o_old = _flatten_out(old_m(x_old))
        o_new = _flatten_out(new_m(x_new))
    assert len(o_old) == len(o_new), f"output arity differs: {len(o_old)} vs {len(o_new)}"
    max_diff = 0.0
    for a, b in zip(o_old, o_new):
        if a.shape != b.shape:
            raise AssertionError(f"output shape differs: {a.shape} vs {b.shape}")
        max_diff = max(max_diff, (a - b).abs().max().item())
    return max_diff


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", default="/tmp/features_old.json")
    ap.add_argument("--new", default="/home/kris/projects/ut99neuralnet/resources/models/rl_pawn/features.json")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    old_feats = feats_from(args.old)
    new_feats = feats_from(args.new)
    delta = len(new_feats) - len(old_feats)
    g_old = parse_grouping(old_feats)
    g_new = parse_grouping(new_feats)
    s_old, s_new = g_old.self_global_dim, g_new.self_global_dim
    print(f"old_feats={len(old_feats)} new_feats={len(new_feats)} delta={delta}")
    print(f"self_global: old={s_old} new={s_new} (delta={s_new - s_old})")
    assert delta > 0, "no new features"
    assert s_new - s_old == delta, "new features are not all self_global — migration assumption broken"

    # Verify against the live SAC policy (rl_pawn_sac.pt) first.
    new_in_expected = build(new_feats).state_dict()[
        next(k for k in build(new_feats).state_dict() if k.endswith(LSTM_IH_KEY))].shape[1]
    sac = torch.load(MODEL_DIR / "rl_pawn_sac.pt", map_location="cpu", weights_only=False)
    sample = sac["model_state_dict"]
    old_in = sample[next(k for k in sample if k.endswith(LSTM_IH_KEY))].shape[1]
    print(f"checkpoint lstm_input_dim={old_in} → expected new={new_in_expected}")
    if old_in == new_in_expected:
        print("ih-gewichten al op nieuwe input-dim — output-verify overslaan (buffers-only pass).")
    else:
        max_diff = verify(old_feats, new_feats, s_old, delta, sample)
        print(f"VERIFY max output |diff| = {max_diff:.3e}  ({'PASS' if max_diff < 1e-4 else 'FAIL'})")
        if max_diff >= 1e-4:
            print("ABORT: migration does not preserve policy output.")
            sys.exit(1)

    if not args.apply:
        print("\n--verify only; no files written. Re-run with --apply to migrate.")
        return

    bdir = MODEL_DIR / "backup_pre_enemyproj_feature"
    bdir.mkdir(exist_ok=True)
    for name in CHECKPOINTS:
        p = MODEL_DIR / name
        if not p.exists():
            print(f"skip (missing): {name}")
            continue
        ck = torch.load(p, map_location="cpu", weights_only=False)
        msd = ck.get("model_state_dict")
        if not msd or not any(k.endswith(LSTM_IH_KEY) for k in msd):
            print(f"skip (no actor msd): {name}")
            continue
        cur_in = msd[next(k for k in msd if k.endswith(LSTM_IH_KEY))].shape[1]
        changed = False
        if cur_in != new_in_expected:
            msd = migrate_state(msd, s_old, delta)
            changed = True
        # Grouping-afgeleide index-buffers (self_global_idx, enemy_idx_flat, ...) veranderen van
        # SHAPE door de feature-add en zijn puur uit features.json afgeleid (geen geleerde data).
        # Vervang elke shape-afwijkende buffer door de nieuwe-model-versie, anders weigert
        # load_compatible_state_dict de bootstrap (missing=['self_global_idx'] — gevonden bij de
        # eerste echte --apply-run 2026-06-06).
        new_ref = build(new_feats).state_dict()
        for k, v in new_ref.items():
            if k in msd and torch.is_tensor(msd[k]) and msd[k].shape != v.shape \
                    and not k.endswith(LSTM_IH_KEY):
                msd[k] = v
                changed = True
                print(f"  buffer hersteld naar nieuw-model-shape: {name}:{k} -> {tuple(v.shape)}")
        for k, v in new_ref.items():
            if k not in msd and torch.is_tensor(v) and not v.is_floating_point():
                msd[k] = v
                changed = True
                print(f"  ontbrekende index-buffer toegevoegd: {name}:{k}")
        if not changed:
            print(f"already migrated: {name}")
            continue
        if not (bdir / name).exists():
            shutil.copy2(p, bdir / name)
        ck["model_state_dict"] = msd
        torch.save(ck, p)
        print(f"migrated + backed up: {name}  (input {cur_in} → {new_in_expected})")
    print(f"\nBackups in: {bdir}")
    print("DONE. Critics auto-reset on re-bootstrap; clear the replay buffer before train-sac.")


if __name__ == "__main__":
    main()
