package aiplay.rl.rewards.aim.viewsmoothness;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense view-jitter penalty. Drie componenten:
 *
 * <ul>
 *   <li>{@code smoothnessPenalty}: lineaire straf op |yawDelta| + |pitchDelta| genormaliseerd op
 *       full rotation. Houdt continuous draaibewegingen klein.</li>
 *   <li>{@code yawOscillationPenalty}: extra straf wanneer de yaw-delta van teken wisselt (links →
 *       rechts → links). Voorkomt jitter-zonder-richting.</li>
 *   <li>{@code postFireCommitmentPenalty}: extra view-jerk straf binnen
 *       {@code postFireCommitmentWindowTicks} na een fire-edge. Forceert follow-through (geen
 *       fire-then-swing-away).</li>
 * </ul>
 */
public record ViewSmoothnessParams(
    RewardMetadata metadata,
    double smoothnessPenalty,
    double yawOscillationPenalty,
    double postFireCommitmentPenalty,
    int postFireCommitmentWindowTicks)
    implements RewardBlock {

  public ViewSmoothnessParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ViewSmoothnessParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return smoothnessPenalty != 0.0
        || yawOscillationPenalty != 0.0
        || postFireCommitmentPenalty != 0.0;
  }
}
