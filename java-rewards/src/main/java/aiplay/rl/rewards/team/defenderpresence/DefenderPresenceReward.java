package aiplay.rl.rewards.team.defenderpresence;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.GameStateDto;
import aiplay.rl.rewards.catalog.EndgameUrgencyParams;
import aiplay.rl.rewards.team.endgame.EndgameUrgency;

/**
 * Dense positional shaping for Defend-role bots: rewards staying in own half while the deepest
 * enemy threatens, and penalises forward roaming when no enemy is past midfield.
 *
 * <p>Reads {@code enemy_depthInOwnHalf} and the bot's own half via {@link RewardUtils} so that the
 * definition matches the role-context feature exactly. Returns zero in the Attack and DeathMatch
 * rewardgroups (their {@code homeBonus} and {@code enemyHalfPenalty} weights are zero).
 *
 * <p>Endgame catchup: the final shaping is multiplied by {@code (1 - urgency)} so that during the
 * configured endgame window the camping bonus AND the enemy-half penalty both fade to zero. This
 * lets a Defender peel off the home base when the team is behind and time is short — the positive
 * pull toward attack is provided by {@code TeamAssistReward.endgameAttackBonus}, this class just
 * removes the brake.
 */
public class DefenderPresenceReward implements RewardComponent {

  private final DefenderPresenceParams params;
  private final EndgameUrgencyParams endgameParams;

  public DefenderPresenceReward(DefenderPresenceParams params, EndgameUrgencyParams endgameParams) {
    if (params == null) {
      throw new IllegalArgumentException(
          "DefenderPresenceReward requires non-null DefenderPresenceParams");
    }
    if (endgameParams == null) {
      throw new IllegalArgumentException(
          "DefenderPresenceReward requires non-null EndgameUrgencyParams");
    }
    this.params = params;
    this.endgameParams = endgameParams;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (!params.enabled()) {
      return 0.0;
    }
    GameStateDto state = ctx.curr();
    if (state.playerPawn == null || state.playerPawn.health <= 0) {
      return 0.0;
    }

    boolean inEnemyHalf = RewardUtils.botOnEnemyHalf(state);
    double enemyDepth = RewardUtils.enemyDepthInOwnHalf(state);
    double threshold = params.engagementThreshold();

    double raw;
    if (!inEnemyHalf && enemyDepth > threshold) {
      double headroom = Math.max(1e-6, 1.0 - threshold);
      double scale = Math.min(1.0, (enemyDepth - threshold) / headroom);
      raw = params.homeBonus() * scale;
    } else if (inEnemyHalf && enemyDepth <= 0.0) {
      raw = -params.enemyHalfPenalty();
    } else {
      return 0.0;
    }

    double urgency = EndgameUrgency.urgency(state, endgameParams);
    return raw * (1.0 - urgency);
  }
}
