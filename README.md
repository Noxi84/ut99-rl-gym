# ut99-rl-gym

A reinforcement-learning training platform for **Unreal Tournament '99** Capture-the-Flag bots.

A single joint LSTM policy (`rl_pawn`) drives everything — movement, aim (yaw/pitch),
fire/alt-fire, plus an auxiliary target head — trained with **behaviour cloning** (warm
start) followed by **SAC** fine-tuning. A Java process runs the live bots and talks to a
UT99 server over a binary UDP protocol; Python handles data generation and training. The
original setup was distributed across several Linux machines, but the code runs on a single
machine as well.

> **Status: archived.** This was a personal research project and is no longer actively
> developed. It is released so the community can learn from it or take it further.
> There is no maintainer — forks and PRs are welcome.

## What's in here

- `java-*/` — the bot runtime: feature extraction, ONNX model inference, reward computation,
  behaviour tree, live recorder, config.
- `train/` — the Python BC + SAC training pipeline (`train/rl/rl_pawn/`).
- `scripts/mutator/NeuralNetWebserver/` — the UnrealScript mutator (RL game mode, RL bot,
  weapon overrides, per-weapon arenas) that bridges UT99 ↔ the Java process.
- `resources/config/` — machine inventory, gameplay/map/pickup config, runtime settings.
- `resources/models/rl_pawn/` — the model's feature / architecture / reward config **and a
  trained champion model** (`rl_pawn.onnx` + `rl_pawn.onnx.data`) ready to run.
- `docs/` — detailed architecture notes (in Dutch). `CLAUDE.md` is the index into them.

## A trained model is included

`resources/models/rl_pawn/rl_pawn.onnx` (+ `rl_pawn.onnx.data`) is a promoted champion:
the joint policy that passed the dual-KPI promotion gate (combat score, on-target accuracy
and flag score). Both files are required — the `.onnx.data` file holds the external tensor
weights. The default map is `CTF-andACTION`.

## Building

```bash
./mvnw package -DskipTests
```

Running the bots additionally requires a UT99 server with the `NeuralNetWebserver` mutator
installed (UnrealScript source in `scripts/mutator/`). See `docs/` for the runtime
architecture, the UDP transport, the feature pipeline and the training loop.

## License

This project is licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0) — see [LICENSE](LICENSE).

In short: you may use, study, modify and share it freely, but if you distribute a modified
version — **including running it as a network service** — you must release your source under
the same AGPL terms.

**Commercial licensing:** to use this in a closed-source or proprietary product without the
AGPL's copyleft obligations, a separate commercial license is available. Open an issue or
contact [Noxi84](https://github.com/Noxi84).
