package aiplay.rl.rewards.combat.primaryfireaim;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense per-tick reward voor primary fire on-target. Symmetrische tegenhanger van
 * {@link ProjectileAimParams}: zonder deze component krijgt primary fire alleen sparse onset-
 * rewards en collapseert het beleid naar alt-fire spam.
 *
 * <p>Actief wanneer {@code fireActive=true}: reward = {@code weight · aim_score · close_factor}.
 * {@code close_factor = exp(-dist / longRangeDampingSigmaUu)} schaalt de reward hoger dichtbij en
 * dempt op grote afstand. {@code minScore} voorkomt farming: aim onder die drempel geeft 0 reward.
 */
public record PrimaryFireAimParams(
    RewardMetadata metadata,
    double weight,
    double minScore,
    double longRangeDampingSigmaUu)
    implements RewardBlock {

  public PrimaryFireAimParams {
    if (metadata == null) {
      throw new IllegalArgumentException("PrimaryFireAimParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
