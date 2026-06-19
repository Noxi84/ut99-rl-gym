"""Necto-style permutation-invariant sequence network.

Takes a flat [batch, seq, total_dim] input tensor and internally splits it into:
- self/global pathway (direct concat)
- teammate slots → shared PlayerEncoder → mean+max pool over slots
- enemy slots    → shared PlayerEncoder → mean+max pool over slots

The pooled team summaries plus self/global features are concatenated per timestep
and fed to an LSTM + head. This makes the network permutation-invariant across
teammate/enemy slot positions (slot-switching at the input does not change the
output — pooling removes order information).

When grouping has no teammate/enemy slots, the network degenerates to a plain
LSTM on the flat input (same behavior as the legacy flat BCSequenceNetwork).

The single-input flat tensor API is preserved so callers (BC data loading,
ONNX export, Java inference) do not need to change their tensor packing —
only the model's forward pass does the splitting internally.
"""
from __future__ import annotations

import os
from pathlib import Path

import torch
import torch.nn as nn

from train.model.player_feature_grouping import parse as parse_grouping


class PlayerEncoder(nn.Module):
    """Per-player MLP encoder. Shared weights across all slots of the same team."""

    def __init__(self, player_dim: int, hidden_dim: int = 64, embed_dim: int = 64):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(player_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
            nn.Linear(hidden_dim, embed_dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class TargetHead(nn.Module):
    """Per-enemy target-selection head. Scores each enemy slot from the LSTM
    output (sequence context) and the slot's encoded features (slot identity).
    Returns target_logits over enemy slots with absent slots masked to a
    finite-large negative so softmax/argmax routes mass away from them.

    Trained via:
    - BC: cross-entropy against post-hoc kill-attribution labels
    - SAC: indirectly via reward attribution (reward computer reads argmax)
           + entropy regularisation to prevent collapse to "always slot 0"
    """

    def __init__(self, lstm_dim: int, player_embed_dim: int, hidden_dim: int = 64):
        super().__init__()
        self.scorer = nn.Sequential(
            nn.Linear(lstm_dim + player_embed_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
            nn.Linear(hidden_dim, 1),
        )

    def forward(self, lstm_last: torch.Tensor, enemy_embeds_last: torch.Tensor,
                enemy_mask: torch.Tensor) -> torch.Tensor:
        # lstm_last: [B, lstm_dim] — last-timestep LSTM output
        # enemy_embeds_last: [B, max_enemies, embed_dim] — per-slot encoded at last timestep
        # enemy_mask: [B, max_enemies] — True if slot is present
        B, M, _ = enemy_embeds_last.shape
        ctx = lstm_last.unsqueeze(1).expand(B, M, -1)
        combined = torch.cat([ctx, enemy_embeds_last], dim=-1)
        logits = self.scorer(combined).squeeze(-1)  # [B, max_enemies]
        # Use finfo.min/2 (FP16-safe) for absent slots — softmax routes mass away.
        masked_value = torch.finfo(logits.dtype).min / 2.0
        logits = torch.where(enemy_mask, logits, torch.full_like(logits, masked_value))
        return logits


class BCSequenceNetwork(nn.Module):
    def __init__(
        self,
        input_features: list[str],
        output_size: int,
        hidden_size: int = 256,
        num_layers: int = 2,
        dropout: float = 0.2,
        player_hidden_dim: int = 64,
        player_embed_dim: int = 64,
        map_embedding_capacity: int = 0,
        map_embedding_dim: int = 0,
        gaussian_head: bool = False,
        log_std_init: float = -1.0,
        expose_target_index: bool = False,
    ):
        super().__init__()
        self.hidden_size = hidden_size
        self.output_size = output_size
        self.input_size = len(input_features)
        self.player_embed_dim = player_embed_dim
        self.expose_target_index = expose_target_index

        grouping = parse_grouping(input_features)
        self.grouping = grouping

        # Self/global pathway: pre-computed flat indices baked into a buffer.
        self.register_buffer(
            "self_global_idx",
            torch.tensor(grouping.self_and_global_indices, dtype=torch.long),
        )
        self_global_dim = grouping.self_global_dim

        if grouping.map_id_index is not None:
            if map_embedding_capacity <= 0 or map_embedding_dim <= 0:
                raise ValueError(
                    "input feature 'map_id' requires positive "
                    "map_embedding_capacity and map_embedding_dim in model config"
                )
            self.map_id_index = int(grouping.map_id_index)
            self.map_embedding_capacity = int(map_embedding_capacity)
            self.map_embedding_dim = int(map_embedding_dim)
            self.map_embedding = nn.Embedding(
                self.map_embedding_capacity,
                self.map_embedding_dim,
            )
        else:
            self.map_id_index = None
            self.map_embedding_capacity = 0
            self.map_embedding_dim = 0
            self.map_embedding = None

        # Teammate pathway.
        if grouping.max_teammates > 0:
            tm_idx = torch.tensor(grouping.teammate_slot_indices, dtype=torch.long)  # [M, D]
            self.register_buffer("teammate_idx_flat", tm_idx.reshape(-1))
            self.teammate_max = grouping.max_teammates
            self.teammate_dim = grouping.teammate_player_dim
            self.teammate_encoder = PlayerEncoder(
                player_dim=self.teammate_dim,
                hidden_dim=player_hidden_dim,
                embed_dim=player_embed_dim,
            )
            self.teammate_isAlive_rel = grouping.teammate_isAlive_relative_idx
        else:
            self.teammate_max = 0
            self.teammate_dim = 0
            self.teammate_encoder = None
            self.teammate_isAlive_rel = None

        # Enemy pathway.
        if grouping.max_enemies > 0:
            en_idx = torch.tensor(grouping.enemy_slot_indices, dtype=torch.long)  # [M, D]
            self.register_buffer("enemy_idx_flat", en_idx.reshape(-1))
            self.enemy_max = grouping.max_enemies
            self.enemy_dim = grouping.enemy_player_dim
            self.enemy_encoder = PlayerEncoder(
                player_dim=self.enemy_dim,
                hidden_dim=player_hidden_dim,
                embed_dim=player_embed_dim,
            )
            self.enemy_isAlive_rel = grouping.enemy_isAlive_relative_idx
        else:
            self.enemy_max = 0
            self.enemy_dim = 0
            self.enemy_encoder = None
            self.enemy_isAlive_rel = None

        lstm_input_dim = self_global_dim
        if self.teammate_encoder is not None:
            lstm_input_dim += 2 * player_embed_dim
        if self.enemy_encoder is not None:
            lstm_input_dim += 2 * player_embed_dim
        if self.map_embedding is not None:
            lstm_input_dim += self.map_embedding_dim

        self.lstm = nn.LSTM(
            lstm_input_dim,
            hidden_size,
            num_layers,
            dropout=dropout if num_layers > 1 else 0.0,
            batch_first=True,
        )
        self.head = nn.Sequential(
            nn.LayerNorm(hidden_size),
            nn.GELU(),
            nn.Linear(hidden_size, 128),
            nn.GELU(),
            nn.Linear(128, output_size),
        )

        if gaussian_head:
            self.log_std = nn.Parameter(torch.full((output_size,), float(log_std_init)))
        else:
            self.log_std = None

        # Phase 2: target-selection head — exposed only when configured (shooting model).
        # Requires enemy slots to exist in the grouping (otherwise nothing to select).
        if expose_target_index and self.enemy_encoder is not None:
            self.target_head = TargetHead(
                lstm_dim=hidden_size,
                player_embed_dim=player_embed_dim,
                hidden_dim=player_hidden_dim,
            )
        else:
            self.target_head = None

    def _pool_team(
        self,
        x: torch.Tensor,
        idx_flat: torch.Tensor,
        max_slots: int,
        player_dim: int,
        encoder: nn.Module,
        isAlive_rel_idx: int | None,
        *,
        return_per_slot: bool = False,
    ):
        """Gather per-slot features, encode each, then pool (mean + max concat).

        When return_per_slot=True, also returns the encoded last-frame slots
        and 2D mask, for downstream attention/target-selection heads.
        Returns: pooled OR (pooled, encoded_last [B, max_slots, embed], mask_2d [B, max_slots])
        """
        B, S, _ = x.shape
        # [B, S, max_slots * player_dim] → [B, S, max_slots, player_dim]
        gathered = x.index_select(-1, idx_flat)
        per_slot = gathered.reshape(B, S, max_slots, player_dim)

        encoded = encoder(per_slot)  # [B, S, max_slots, embed_dim]

        if isAlive_rel_idx is not None:
            # Mask from the LAST-frame isAlive value, broadcast across all timesteps.
            # Rationale: `isAlive` rarely changes within a short temporal window
            # (only around death/respawn transitions). Using the last-frame value
            # as a single uniform mask lets the config keep `isAlive` in a narrow
            # [0, 1] temporal group (1 CSV column per slot) while still correctly
            # gating the pool. Feature values at timesteps where the slot was
            # absent are 0-filled by the resolver — they still flow through the
            # encoder but contribute a learned "null embedding" signal.
            # select() instead of [..., idx] because PyTorch's ONNX exporter
            # emits an unsliceable scalar-start Slice node for the [..., idx] form.
            last_ts = per_slot.select(1, per_slot.size(1) - 1)                 # [B, max_slots, player_dim]
            last_isAlive = last_ts.select(-1, isAlive_rel_idx)                 # [B, max_slots]
            mask_2d = last_isAlive > 0.5                                       # [B, max_slots]
            mask = mask_2d.unsqueeze(1).expand(B, S, max_slots)                # [B, S, max_slots]
        else:
            mask_2d = torch.ones(B, max_slots, dtype=torch.bool, device=x.device)
            mask = mask_2d.unsqueeze(1).expand(B, S, max_slots)
        # Stay in the encoder's dtype so AMP/autocast/FP16 paths don't cross-promote.
        mask_f = mask.to(encoded.dtype).unsqueeze(-1)  # [B, S, max_slots, 1]

        # Mean pool — weighted by mask, divided by live-slot count.
        summed = (encoded * mask_f).sum(dim=2)                 # [B, S, embed]
        counts = mask_f.sum(dim=2).clamp(min=1.0)              # [B, S, 1]
        mean_pool = summed / counts

        # Max pool — replace masked slots with the dtype-min finite value before max.
        # Using torch.finfo.min keeps the constant representable in FP16 (AMP) —
        # -1e9 overflows FP16 (range ±65504), while torch.finfo(fp16).min ≈ -65504.
        neg_large = torch.full_like(encoded, torch.finfo(encoded.dtype).min)
        encoded_for_max = torch.where(mask_f.bool(), encoded, neg_large)
        max_pool = encoded_for_max.max(dim=2).values           # [B, S, embed]
        # When no slots are present (all-empty frame), zero the max-pool so downstream
        # layers see a sensible value rather than finfo.min.
        any_isAlive = mask.any(dim=2).to(max_pool.dtype).unsqueeze(-1)  # [B, S, 1]
        max_pool = max_pool * any_isAlive

        pooled = torch.cat([mean_pool, max_pool], dim=-1)       # [B, S, 2*embed]
        if return_per_slot:
            encoded_last = encoded.select(1, encoded.size(1) - 1)  # [B, max_slots, embed]
            return pooled, encoded_last, mask_2d
        return pooled

    def _encode(self, x: torch.Tensor, *, capture_enemy_per_slot: bool = False):
        # Concat order: [self_global | enemy_pool | teammate_pool]. Enemies come
        # before teammates so that older checkpoints trained without a teammate
        # pool can resume cleanly: the enemy-pool columns in lstm.weight_ih_l0
        # stay aligned, and the new teammate-pool columns are appended at the
        # end (partial load from load_compatible_state_dict keeps random init
        # for the new columns, old-enemy weights for the rest).
        parts: list[torch.Tensor] = [x.index_select(-1, self.self_global_idx)]
        enemy_per_slot_last = None
        enemy_mask = None
        if self.enemy_encoder is not None:
            if capture_enemy_per_slot:
                pooled, enemy_per_slot_last, enemy_mask = self._pool_team(
                    x, self.enemy_idx_flat, self.enemy_max,
                    self.enemy_dim, self.enemy_encoder, self.enemy_isAlive_rel,
                    return_per_slot=True,
                )
                parts.append(pooled)
            else:
                parts.append(self._pool_team(
                    x, self.enemy_idx_flat, self.enemy_max,
                    self.enemy_dim, self.enemy_encoder, self.enemy_isAlive_rel,
                ))
        if self.teammate_encoder is not None:
            parts.append(self._pool_team(
                x, self.teammate_idx_flat, self.teammate_max,
                self.teammate_dim, self.teammate_encoder, self.teammate_isAlive_rel,
            ))
        if self.map_embedding is not None:
            # Use the last timestep's id and broadcast its embedding across the
            # whole sequence. This keeps the categorical map context available
            # to the LSTM even when the raw map_id feature group is last-frame
            # only and temporal masking zeroes earlier raw columns.
            last_frame = x.select(1, x.size(1) - 1)
            map_ids = last_frame.select(-1, self.map_id_index).to(torch.long)
            map_ids = torch.clamp(map_ids, 0, self.map_embedding_capacity - 1)
            map_emb = self.map_embedding(map_ids)
            parts.append(map_emb.unsqueeze(1).expand(-1, x.size(1), -1))
        combined = torch.cat(parts, dim=-1)
        out, _ = self.lstm(combined)
        # Last-timestep: select() is ONNX-friendlier than out[:, -1, :].
        lstm_last = out.select(1, out.size(1) - 1)
        if capture_enemy_per_slot:
            return lstm_last, enemy_per_slot_last, enemy_mask
        return lstm_last

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.head(self._encode(x))

    def forward_with_target(self, x: torch.Tensor):
        """Forward pass with target_logits as a second output. Used for
        ONNX export and training when target_head is enabled. When the head
        is absent, falls back to single-output behavior.
        Returns: action_logits OR (action_logits, target_logits)
        """
        if self.target_head is None:
            return self.head(self._encode(x))
        lstm_last, enemy_per_slot_last, enemy_mask = self._encode(x, capture_enemy_per_slot=True)
        action = self.head(lstm_last)
        target_logits = self.target_head(lstm_last, enemy_per_slot_last, enemy_mask)
        return action, target_logits


def load_compatible_state_dict(model: nn.Module, state_dict: dict) -> tuple[list[str], list[str]]:
    # Strip the ``_orig_mod.`` prefix that ``torch.compile`` adds to every
    # key in an ``OptimizedModule.state_dict()``. Bootstrap unconditionally
    # constructs the raw nn.Module before compile, so the receiving model
    # never has that prefix — without this strip, every key from a
    # compiled-save checkpoint would land in ``skipped`` and the receiver
    # would silently keep its randomly-initialised weights.
    prefix = "_orig_mod."
    if any(k.startswith(prefix) for k in state_dict.keys()):
        state_dict = {
            (k[len(prefix):] if k.startswith(prefix) else k): v
            for k, v in state_dict.items()
        }
    current = model.state_dict()
    compatible = {}
    skipped = []
    for key, value in state_dict.items():
        if key not in current:
            skipped.append(key)
            continue
        cur_value = current[key]
        if cur_value.shape == value.shape:
            compatible[key] = value
            continue
        if key.endswith("lstm.weight_ih_l0") and cur_value.ndim == 2 and value.ndim == 2:
            if cur_value.shape[0] == value.shape[0]:
                merged = cur_value.clone()
                cols = min(cur_value.shape[1], value.shape[1])
                merged[:, :cols] = value[:, :cols]
                compatible[key] = merged
                continue
        skipped.append(key)

    missing = [key for key in current.keys() if key not in compatible]
    current.update(compatible)
    model.load_state_dict(current, strict=False)
    return missing, skipped


def export_actor_onnx(
    model: BCSequenceNetwork,
    onnx_path: str,
    seq_len: int,
    input_size: int,
    device: torch.device,
    fp16_internals: bool = True,
) -> None:
    """Export ONNX with atomic file replacement.

    Exports to a staging directory first, then atomically renames into place.
    This prevents SIGBUS crashes in Java when ONNX Runtime has the old .data
    file memory-mapped — os.rename() replaces the directory entry but the old
    inode stays valid for existing mappings.

    When fp16_internals=True (default), the exported graph is rewritten so all
    weights/ops are FP16 but the I/O boundary stays FP32 (Cast-FP16 at the
    input, Cast-FP32 at the output). BC training already uses AMP (FP16 forward
    pass via torch.amp.autocast), so downcasting weights at export introduces
    no new numerical regime beyond what training already validated on.
    Java ORT callers pass the same float[] they always passed — no FP16-aware
    code on the caller side.

    We use onnxconverter_common.float16 here instead of PyTorch's .half() +
    wrapper pattern because PyTorch's dynamo exporter optimises small cast
    wrappers away inconsistently (observed: same wrapper produced FP16 for one
    model and FP32 for another in the same export session). The ONNX-graph
    rewrite is deterministic and touches every Float initializer uniformly.
    """
    was_training = model.training
    model_eval = model.to(device).eval()
    dummy = torch.randn(1, seq_len, input_size, device=device)

    # Multi-output export when target_head is present (Phase 2 attention).
    # Java reads the second output as target_index via argmax.
    has_target_head = getattr(model_eval, "target_head", None) is not None
    if has_target_head:
        # Wrapper that returns (action_logits, target_logits) tuple to torch.onnx.export.
        class _ExportWrapper(nn.Module):
            def __init__(self, m: nn.Module):
                super().__init__()
                self.m = m

            def forward(self, x):
                return self.m.forward_with_target(x)

        wrapped = _ExportWrapper(model_eval).to(device).eval()
        output_names = ["output", "target_logits"]
        dynamic_axes = {
            "x": {0: "batch"},
            "output": {0: "batch"},
            "target_logits": {0: "batch"},
        }
    else:
        wrapped = model_eval
        output_names = ["output"]
        dynamic_axes = {"x": {0: "batch"}, "output": {0: "batch"}}

    final_path = Path(onnx_path)
    staging_dir = final_path.parent / ".onnx_staging"
    staging_dir.mkdir(exist_ok=True)
    staging_onnx = staging_dir / final_path.name
    staging_data = Path(str(staging_onnx) + ".data")

    with torch.no_grad():
        torch.onnx.export(
            wrapped,
            dummy,
            str(staging_onnx),
            input_names=["x"],
            output_names=output_names,
            opset_version=18,
            do_constant_folding=False,
            dynamic_axes=dynamic_axes,
            dynamo=False,
        )

    if fp16_internals:
        import onnx
        import onnxruntime as ort
        import numpy as np
        import shutil
        from onnxconverter_common import float16

        # Unique per-model subdir so concurrent trainers (e.g. parallel BC for
        # movement + viewrotation + shooting) don't stomp on each other's
        # in-flight fp16 staging files. Using the final filename stem guarantees
        # uniqueness while still being deterministic/readable for debugging.
        fp16_dir = staging_dir / f"fp16_tmp_{final_path.stem}"
        if fp16_dir.exists():
            shutil.rmtree(fp16_dir)
        fp16_dir.mkdir()
        fp16_onnx = fp16_dir / final_path.name
        fp16_data = fp16_dir / (final_path.name + ".data")

        try:
            onnx_model = onnx.load(str(staging_onnx), load_external_data=True)
            onnx_model_fp16 = float16.convert_float_to_float16(
                onnx_model, keep_io_types=True,
                max_finite_val=65504.0,
            )
            # onnxconverter_common 1.16 rewrites Cast nodes' output value_info
            # to FLOAT16 but leaves the Cast `to` attribute at FLOAT, producing
            # a graph ORT refuses to load with
            # "Type Error: ... does not match expected type (tensor(float))".
            # PyTorch's LSTM export emits ~12 Cast nodes (h/c state, masking
            # constants) where this mismatch occurs. Realign each Cast's `to`
            # with the declared output dtype.
            _patch_cast_to_match_valueinfo(onnx_model_fp16)
            for init in onnx_model_fp16.graph.initializer:
                del init.external_data[:]
                init.data_location = onnx.TensorProto.DEFAULT

            onnx.save_model(
                onnx_model_fp16,
                str(fp16_onnx),
                save_as_external_data=True,
                all_tensors_to_one_file=True,
                location=fp16_data.name,
                size_threshold=1024,
            )

            probe_exc: Exception | None = None
            finite = False
            try:
                probe_sess = ort.InferenceSession(str(fp16_onnx),
                    providers=["CPUExecutionProvider"])
                in_name = probe_sess.get_inputs()[0].name
                finite = True
                for scale in (1.0, 3.0, 8.0):
                    probe_in = (np.random.randn(4, seq_len, input_size)
                                .astype(np.float32) * float(scale))
                    outs = probe_sess.run(None, {in_name: probe_in})
                    # Check all outputs (action_logits + target_logits when present)
                    if not all(np.all(np.isfinite(o)) for o in outs):
                        finite = False
                        break
                del probe_sess
            except Exception as exc:
                probe_exc = exc

            if finite:
                if staging_onnx.exists():
                    staging_onnx.unlink()
                if staging_data.exists():
                    staging_data.unlink()
                os.rename(str(fp16_onnx), str(staging_onnx))
                if fp16_data.exists():
                    os.rename(str(fp16_data), str(staging_data))
            elif probe_exc is not None:
                import sys
                import traceback
                import warnings
                # Library / graph bug — surface loudly so it lands in tmux
                # logs. Falling back to FP32 keeps training alive but the
                # operator must see this.
                print(f"[FP16 EXPORT FAIL] {final_path.name}: "
                      f"{type(probe_exc).__name__}: {probe_exc}",
                      file=sys.stderr, flush=True)
                traceback.print_exception(type(probe_exc), probe_exc,
                                          probe_exc.__traceback__,
                                          file=sys.stderr)
                warnings.warn(
                    f"FP16 ONNX validation raised "
                    f"{type(probe_exc).__name__} for {final_path.name} "
                    f"({probe_exc}); kept FP32 export. Likely a "
                    "converter/graph bug — see stderr trace."
                )
            else:
                import warnings
                # Non-finite output despite a successful graph load. BC
                # training uses AMP (FP16 forward) so this should not
                # happen — points at NaN/inf weights in the actor
                # checkpoint or extreme activations after a loss spike.
                warnings.warn(
                    f"FP16 inference produced non-finite output for "
                    f"{final_path.name}; kept FP32 export. AMP training "
                    "should prevent this — investigate NaN weights or "
                    "recent loss spikes in the actor checkpoint."
                )
        finally:
            if fp16_dir.exists():
                shutil.rmtree(fp16_dir, ignore_errors=True)

    _assert_onnx_pair_coherent(staging_onnx, staging_data)

    final_data = Path(str(final_path) + ".data")
    if staging_data.exists():
        os.rename(str(staging_data), str(final_data))
    os.rename(str(staging_onnx), str(final_path))

    if was_training:
        model.train()


def _patch_cast_to_match_valueinfo(model_proto) -> int:
    """Realign Cast nodes' `to` attribute with the declared output dtype.

    onnxconverter_common.float16.convert_float_to_float16 rewrites value_info
    of Cast outputs to FP16 when the surrounding graph becomes FP16, but does
    not update the Cast's own `to` attribute. ORT then refuses to load the
    graph with "Type ... does not match expected type". Walking the Cast
    nodes and matching `to` to the declared output dtype repairs the graph
    without needing a graph rewrite.
    """
    import onnx
    TP = onnx.TensorProto
    name_dtype: dict[str, int] = {}
    for vi in (list(model_proto.graph.input)
               + list(model_proto.graph.output)
               + list(model_proto.graph.value_info)):
        name_dtype[vi.name] = vi.type.tensor_type.elem_type
    for init in model_proto.graph.initializer:
        name_dtype[init.name] = init.data_type
    patched = 0
    for node in model_proto.graph.node:
        if node.op_type != "Cast":
            continue
        to_attr = next((a for a in node.attribute if a.name == "to"), None)
        if to_attr is None:
            continue
        declared = name_dtype.get(node.output[0])
        if declared in (TP.FLOAT, TP.FLOAT16) and to_attr.i != declared:
            to_attr.i = declared
            patched += 1
    return patched


def _assert_onnx_pair_coherent(onnx_path: Path, data_path: Path) -> None:
    """Verify .onnx and .onnx.data are a matched pair before atomic rename."""
    import onnx
    model = onnx.load(str(onnx_path), load_external_data=False)
    needs_external = any(
        init.data_location == onnx.TensorProto.EXTERNAL
        for init in model.graph.initializer
    )
    if needs_external and not data_path.exists():
        raise RuntimeError(
            f"ONNX export incoherent: {onnx_path.name} has external-data "
            f"references but {data_path.name} is missing. Model would load "
            "graph but no weights at runtime."
        )
