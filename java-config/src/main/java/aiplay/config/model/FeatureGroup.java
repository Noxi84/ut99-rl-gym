package aiplay.config.model;

import java.util.List;

/**
 * A group of features with a specific temporal activation pattern.
 *
 * <p>Within the total sequence window:
 * <ul>
 *   <li>{@code firstFrames}: the oldest N frames (indices 0..firstFrames-1) are active.</li>
 *   <li>{@code lastFrames}: the most recent M frames (totalWindow-lastFrames..totalWindow-1) are active.</li>
 *   <li>Frames in the gap [{@code firstFrames}, {@code totalWindow - lastFrames}) are masked to zero.</li>
 * </ul>
 * When {@code firstFrames + lastFrames >= totalWindow} there is no gap and all frames are active.
 */
public record FeatureGroup(
    List<String> features,
    int firstFrames,
    int lastFrames
) {}
