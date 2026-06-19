package aiplay.rl.rewards.combat.projectileaim;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense per-tick continuous shaping voor eigen projectielen (vooral flak grenades en chunks): hoe
 * dichter een projectiel bij een vijand komt, hoe hoger de reward.
 *
 * <p>Per projectile: {@code weight · exp(-min_dist_to_any_enemy / sigmaUt) · range_factor}. De
 * {@code range_factor = 1 - exp(-bot_to_enemy_dist / closeRangeDampingSigmaUu)} dempt de reward bij
 * korte bot-to-enemy afstand (close range = primary fire territoire) en nadert 1.0 op grote
 * afstand.
 */
public record ProjectileAimParams(
    RewardMetadata metadata,
    double weight,
    double sigmaUt,
    double closeRangeDampingSigmaUu)
    implements RewardBlock {

  public ProjectileAimParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ProjectileAimParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
