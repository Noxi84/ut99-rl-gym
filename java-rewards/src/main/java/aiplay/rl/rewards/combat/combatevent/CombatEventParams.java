package aiplay.rl.rewards.combat.combatevent;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Sparse combat-events: frag, death, fire-edge penalties, on/off-target shot bonus en de killed-by-fire bonus. Werkt op fire-onset en score-deltas tussen prev en curr frame.
 *
 * <p>Shot-aim parameters: {@code shotMinAimScore} is de cosine drempel waarboven een fire-edge als
 * "on target" geldt; {@code shotPrecisionExponent} verscherpt de gradient (reward = base ·
 * aim_score^exp); {@code shotPerfectionBonus} is een extra trap voor zeer accurate shots
 * (aim_score ≥ {@code shotPerfectionThreshold}). {@code shotOnTargetInstagibOnly} gate't de
 * on-target bonus tot hitscan-wapens (cosine-aim klopt niet voor projectile arc-aim).
 *
 * <p>Kill-credit backprop: bridge over projectile-flight-time. Wanneer enabled wordt
 * {@code enemyKilledByFire} retroactief toegekend aan het fire-onset frame binnen
 * {@code killCreditWindowTicks}, anders aan de huidige tick. Gebruikt door
 * {@code PerModelExperienceRecorder}, niet door het reward-component zelf.
 */
public record CombatEventParams(
    RewardMetadata metadata,
    double frag,
    double death,
    double firePenalty,
    double fireDuringCooldownPenalty,
    double shotOnTargetBonusPrimary,
    double shotOnTargetBonusAlt,
    double shotOffTargetPenaltyPrimary,
    double shotOffTargetPenaltyAlt,
    double missedOpportunityPenalty,
    double shotMinAimScore,
    double shotPrecisionExponent,
    double shotPerfectionBonus,
    double shotPerfectionThreshold,
    double enemyKilledByFireReward,
    boolean shotOnTargetInstagibOnly,
    boolean killCreditBackpropEnabled,
    int killCreditWindowTicks)
    implements RewardBlock {

  public CombatEventParams {
    if (metadata == null) {
      throw new IllegalArgumentException("CombatEventParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return frag != 0.0
        || death != 0.0
        || firePenalty != 0.0
        || fireDuringCooldownPenalty != 0.0
        || shotOnTargetBonusPrimary != 0.0
        || shotOnTargetBonusAlt != 0.0
        || shotOffTargetPenaltyPrimary != 0.0
        || shotOffTargetPenaltyAlt != 0.0
        || missedOpportunityPenalty != 0.0
        || shotPerfectionBonus != 0.0
        || enemyKilledByFireReward != 0.0;
  }
}
