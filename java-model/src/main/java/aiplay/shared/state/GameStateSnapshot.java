package aiplay.shared.state;

import aiplay.dto.GridFrame;

import java.util.List;

public class GameStateSnapshot {
    public final long seq;
    public final List<GridFrame> frames;

    public GameStateSnapshot(long seq, List<GridFrame> frames) {
        this.seq = seq;
        this.frames = frames;
    }
}
