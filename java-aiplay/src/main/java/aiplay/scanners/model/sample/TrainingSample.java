package aiplay.scanners.model.sample;

import aiplay.dto.GameStateDto;

import java.util.List;

/**
 * Canonical training sample: one real frame window from a recording session.
 * This is the source of truth — no synthetic modifications.
 */
public class TrainingSample {

    private final String sessionId;
    private final String modelKey;
    private final List<GameStateDto> sessionFrames;
    private final List<GameStateDto> frameWindow;
    private final int currentIndex;

    public TrainingSample(String sessionId, String modelKey,
                          List<GameStateDto> sessionFrames,
                          List<GameStateDto> frameWindow,
                          int currentIndex) {
        this.sessionId = sessionId;
        this.modelKey = modelKey;
        this.sessionFrames = sessionFrames;
        this.frameWindow = frameWindow;
        this.currentIndex = currentIndex;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public List<GameStateDto> getSessionFrames() {
        return sessionFrames;
    }

    public List<GameStateDto> getFrameWindow() {
        return frameWindow;
    }

    public GameStateDto getLastFrame() {
        return frameWindow.get(frameWindow.size() - 1);
    }

    /**
     * Index of lastFrame in sessionFrames. Used by target projectors
     * that need lookahead (e.g. viewrotation).
     */
    public int getCurrentIndex() {
        return currentIndex;
    }
}
