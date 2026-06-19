"""Reset SAC checkpoint best_mean_return and baseline_return."""
import os
import sys
import torch

path = sys.argv[1]
if not os.path.exists(path):
    print(f"  checkpoint not found at {path} -- nothing to reset (will be created by trainer)")
    sys.exit(0)

meta = torch.load(path, map_location="cpu", weights_only=False)
step = meta.get("global_step")
best = meta.get("best_mean_return")
baseline = meta.get("baseline_return")
print(f"  step={step}  best={best:.4f}  baseline={baseline}")

meta["best_mean_return"] = -1e9
meta["baseline_return"] = None
torch.save(meta, path)
print("  -> reset to best=-inf baseline=None")
