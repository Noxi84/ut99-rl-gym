package aiplay.dto;

/**
 * Wraps a GameStateDto with grid-aligned metadata.
 * <p>
 * The raw GameStateDto represents the game state at capture time.
 * The gridMs and frameNumber represent the position on the resampled
 * FPS-aligned grid (set by AiPlayFacade).
 * <p>
 * Multiple GridFrames can reference the same underlying GameStateDto
 * (hold-last resampling), each with a different gridMs.
 */
public record GridFrame(
        GameStateDto state,
        long gridMs,
        int frameNumber
) {}
