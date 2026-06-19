package aiplay.rl.rewards.team.defenderpresence;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense bidirectional positional shaping for the Defend role.
 *
 * <ul>
 *   <li>{@code homeBonus}: per-tick reward when the bot is in its own half and the deepest enemy
 *       penetration into our half exceeds {@code engagementThreshold} — bonus scales linearly with
 *       depth above threshold (1.0 at depth = 1, 0.0 at depth = threshold).</li>
 *   <li>{@code enemyHalfPenalty}: per-tick penalty when the bot is in the enemy half while no
 *       enemy has crossed midfield into our side — discourages roaming forward when there is no
 *       defensive need at home.</li>
 *   <li>{@code engagementThreshold}: the depth value above which the bot should actively defend
 *       (0.0..1.0 along the home→enemy axis, 0.0 = midfield, 1.0 = our home base).</li>
 * </ul>
 */
public record DefenderPresenceParams(
    RewardMetadata metadata,
    double homeBonus,
    double enemyHalfPenalty,
    double engagementThreshold)
    implements RewardBlock {

  public DefenderPresenceParams {
    if (metadata == null) {
      throw new IllegalArgumentException("DefenderPresenceParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return homeBonus != 0.0 || enemyHalfPenalty != 0.0;
  }
}
