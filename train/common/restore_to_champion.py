"""Restore a model's SAC training state to its newest promoted champion.

Offline, "hard" counterpart to the DeltaGate's in-trainer actor-only rollback
(``training_loop._rollback_joint_to_champion``). The gate reverts only the
in-memory actor and deliberately keeps the bots on the current ONNX to preserve
self-play diversity. This tool instead pushes the champion ONNX to every server
and wipes the replay buffer feed, so every bot is back on the last champion NOW.

Driven by the deploy.json ``reset-current-to-last-champion`` flag via
``scripts/deploy/reset-sac-to-champion.sh``. For the given model_key it:

  1. Resolves the newest PROMOTED champion bundle -> snapshot dir
     (the same resolution runtime ``<mk>/newest`` uses, so it matches what the
     champion-team bots were running).
  2. HARD-REJECTS if the champion's arch/feature fingerprint no longer matches
     the current ``resources/models/<mk>/`` config — the champion ONNX would be
     unloadable by the bots. (rewards-fingerprint mismatch is a warning only:
     new training continues under the current rewards.)
  3. Copies champion ``<mk>.pt`` -> ``trainingmodel/<mk>_sac_best.pt`` +
     ``<mk>_sac.pt`` (the SAC bootstrap ladder loads ``_sac_best.pt`` first).
  4. Copies champion ``<mk>.onnx`` (+ ``.data``) -> ``trainingmodel/<mk>.onnx``
     (the live model the bots run).
  5. Deletes stale SAC checkpoint / inflight / delta-baseline / sac-best ONNX so
     the trainer bootstraps cleanly from the restored champion .pt instead of
     re-loading the disturbed checkpoint.
  6. Pushes the restored ONNX to every other server (synchronous) so the bots
     come up on the champion before the cleared replay buffer refills.

Run on the SAC trainer worker host (where ``trainingmodel/`` lives and from
which ModelSync reaches the other servers)::

    cd /home/kris/projects/ut99neuralnet && \
        .venv/bin/python3 -m train.common.restore_to_champion rl_pawn
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from train.common import ModelSync, champion_pool, champion_store
from train.common.SessionPaths import get_session_paths


def _fail(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def restore(model_key: str) -> None:
    sp = get_session_paths(create_dirs=True)
    tm = sp.trainingmodel_dir

    # 1. Resolve the newest promoted champion. No silent fallback to BC — if the
    #    pool has no promoted champion, the operator asked for something that
    #    cannot be honoured and must be told (use reset-sac-to-bc-baseline for BC).
    bundle = champion_pool.newest_promoted_bundle(model_key)
    if bundle is None or model_key not in bundle.counters:
        _fail(
            f"no promoted champion found for {model_key} (pool is empty or only "
            f"has bootstrap bundles). Nothing to restore to — use "
            f"reset-sac-to-bc-baseline if you want to fall back to BC weights."
        )
    counter = int(bundle.counters[model_key])
    snapshot_dir = champion_store.find_snapshot_dir(model_key, counter)
    if snapshot_dir is None:
        _fail(f"champion snapshot dir missing for {model_key}/{counter:04d} "
              f"(bundles.json references a counter with no snapshot on this host).")

    # 2. Compatibility gate. arch + feature must match the current config or the
    #    bots cannot load the ONNX. rewards mismatch is informational.
    meta = champion_store.read_meta(snapshot_dir)
    compat = champion_store.is_compatible(meta, model_key)
    if not (compat["arch"] and compat["feature"]):
        _fail(
            f"champion {model_key}/{counter:04d} is INCOMPATIBLE with the current "
            f"config (arch={'OK' if compat['arch'] else 'BROKEN'}, "
            f"feature={'OK' if compat['feature'] else 'BROKEN'}). model.json or "
            f"features.json changed since this champion was promoted — its ONNX "
            f"would be unloadable. Promote a current-config champion first, or use "
            f"reset-sac-to-bc-baseline."
        )
    if not compat["rewards"]:
        print(f"  WARN: champion {model_key}/{counter:04d} rewards fingerprint differs "
              f"from current rewards.json (champion was trained under different reward "
              f"weights). Restoring anyway — new training continues under current rewards.")

    champ_pt = snapshot_dir / f"{model_key}.pt"
    champ_onnx = snapshot_dir / f"{model_key}.onnx"
    champ_data = snapshot_dir / f"{model_key}.onnx.data"
    if not champ_pt.exists():
        _fail(
            f"champion {model_key}/{counter:04d} has no {model_key}.pt — it predates "
            f"durable-rollback .pt bundling, so actor+optimizer state cannot be "
            f"restored. Pick a newer champion or use reset-sac-to-bc-baseline."
        )
    if not champ_onnx.exists():
        _fail(f"champion {model_key}/{counter:04d} has no {model_key}.onnx.")

    print(f"Restoring {model_key} -> champion {counter:04d} "
          f"(bundle={bundle.id}, created_at={meta.created_at})")

    # 3. Champion actor+optimizer .pt -> SAC bootstrap ladder (best + current).
    #    bootstrap() loads _sac_best.pt first, then _sac.pt, then BC; writing both
    #    makes the champion the unambiguous bootstrap source regardless of ladder
    #    order.
    sac_best_pt = tm / f"{model_key}_sac_best.pt"
    sac_pt = tm / f"{model_key}_sac.pt"
    shutil.copy2(champ_pt, sac_best_pt)
    shutil.copy2(champ_pt, sac_pt)
    print(f"  .pt   -> {sac_best_pt.name} + {sac_pt.name}")

    # 4. Champion ONNX -> live trainingmodel ONNX (what the bots run). Drop a
    #    stale .data first so a single-file champion ONNX does not leave an
    #    orphaned old external-data sibling that the new ONNX no longer references.
    live_onnx = tm / f"{model_key}.onnx"
    live_data = tm / f"{model_key}.onnx.data"
    if live_data.exists():
        live_data.unlink()
    shutil.copy2(champ_onnx, live_onnx)
    has_data = champ_data.exists()
    if has_data:
        shutil.copy2(champ_data, live_data)
    print(f"  .onnx -> {live_onnx.name}" + (" (+.data)" if has_data else ""))

    # 5. Drop stale SAC state so the trainer bootstraps from the restored champion
    #    .pt instead of resurrecting the disturbed checkpoint / best ONNX.
    stale = [
        tm / f"{model_key}_sac_checkpoint.pt",
        tm / f"{model_key}_sac_inflight.pt",
        tm / f"{model_key}_sac_best.onnx",
        tm / f"{model_key}_sac_best.onnx.data",
        tm / f"{model_key}_sac_delta_baseline.pt",
        tm / f"{model_key}_sac_delta_baseline.onnx",
        tm / f"{model_key}_sac_delta_baseline.onnx.data",
        tm / f"{model_key}_sac_delta_baseline.json",
    ]
    removed = [p.name for p in stale if p.exists()]
    for p in stale:
        if p.exists():
            p.unlink()
    if removed:
        print(f"  removed stale SAC state: {', '.join(removed)}")

    # 6. Push the restored ONNX to every other server (synchronous). Ordering
    #    matters: the deploy pipeline defers bot startup until this returns, so
    #    the bots come up on the champion and refill the cleared buffer with
    #    champion-consistent experience (avoids the buffer-race that would re-poison
    #    a freshly cleared buffer with disturbed-policy transitions).
    targets = ModelSync._get_sync_targets()
    if targets:
        print(f"  pushing {model_key}.onnx to {len(targets)} server(s)...")
        ModelSync.sync_onnx_to_servers(str(live_onnx), wait=True)
        print("  ONNX pushed to all servers.")
    else:
        print("  no remote sync targets (dev-only / single machine) — local copy only.")

    print(f"Done: {model_key} restored to champion {counter:04d}.")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="restore_to_champion")
    p.add_argument("model_key", help="Model key to restore (e.g. rl_pawn)")
    args = p.parse_args(argv)
    restore(args.model_key)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
