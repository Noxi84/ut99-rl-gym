package aiplay.rl.rewards.movement.facing;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense yaw-facing reward: {@code bonus · max(0, dot(viewDir, dirToObjective))} — clipped op 0 zodat
 * "weg-kijken" niet negatief wordt gestraft (dat is taak van {@code ViewSmoothness} en
 * {@code ViewAlignment}).
 */
public record FacingParams(RewardMetadata metadata, double bonus) implements RewardBlock {

  public FacingParams {
    if (metadata == null) {
      throw new IllegalArgumentException("FacingParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return bonus != 0.0;
  }
}
