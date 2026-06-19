package aiplay.rl.rewards.objective.flagcarrierkill;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Sparse bonus wanneer een frag samenvalt met onze eigen vlag transitioning carried→dropped:
 * heuristisch hebben we de enemy carrier net gedood. {@code nearBaseBonus} schaalt lineair met de
 * 2D afstand tussen drop-locatie en de enemy-base (waar zij zouden hebben gescored): vol bij base,
 * 0 op {@code nearBaseMaxDistanceUu}.
 */
public record FlagCarrierKillParams(
    RewardMetadata metadata,
    double bonus,
    double nearBaseBonus,
    double nearBaseMaxDistanceUu)
    implements RewardBlock {

  public FlagCarrierKillParams {
    if (metadata == null) {
      throw new IllegalArgumentException("FlagCarrierKillParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return bonus != 0.0 || nearBaseBonus != 0.0;
  }
}
