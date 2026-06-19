package aiplay.rl.rewards.movement.speed;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense reward proportioneel aan 2D verplaatsingssnelheid (UU/tick) tussen frames. Speed wordt
 * geclamped op 20 UU/tick om reward-spikes door fall-impacts of teleport-launches te voorkomen.
 */
public record SpeedParams(RewardMetadata metadata, double scale) implements RewardBlock {

  public SpeedParams {
    if (metadata == null) {
      throw new IllegalArgumentException("SpeedParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return scale != 0.0;
  }
}
