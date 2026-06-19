package aiplay.rl.rewards.aim.viewalignment;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense ViewTargeting-aware alignment: yaw-cos² shaping richting enemy/objective + acquisition
 * (rate-of-improvement) + pre-fire stability bonus. Enemy-target gebruikt lead-aim correctie via
 * {@code LeadAimUtils} voor projectile-wapens.
 *
 * <p>Precisie-tier bonussen ({@code precisionBonus}/{@code onTargetBonus}) zijn historisch reward-
 * hacking magneten gebleken en zijn doorgaans 0; behouden voor per-wapen tuning waar het past
 * (hitscan met milde weight). Pre-fire stability vereist zowel hoge {@code aim_score} als lage
 * {@code yaw-velocity} en moedigt "settling before fire" aan.
 */
public record ViewAlignmentParams(
    RewardMetadata metadata,
    double enemyAlignmentBonus,
    double objectiveAlignmentBonus,
    double acquisitionBonus,
    double precisionBonus,
    double precisionThreshold,
    double onTargetBonus,
    double onTargetThreshold,
    double preFireStabilityBonus,
    double preFireStabilityAimScoreThreshold,
    double preFireStabilityYawVelocityThresholdNorm,
    double enemyAlignmentMaxRangeUu)
    implements RewardBlock {

  public ViewAlignmentParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ViewAlignmentParams.metadata required");
    }
    // Range-gate (no-fallback): de holding-aim-reward op de enemy mag alleen binnen effectieve
    // engagement-afstand tellen, anders farmt de policy passieve lange-afstand-aim (cowardice).
    if (enemyAlignmentMaxRangeUu <= 0.0) {
      throw new IllegalArgumentException(
          "ViewAlignmentParams.enemyAlignmentMaxRangeUu must be > 0, was " + enemyAlignmentMaxRangeUu);
    }
  }

  @Override
  public boolean enabled() {
    return enemyAlignmentBonus != 0.0
        || objectiveAlignmentBonus != 0.0
        || acquisitionBonus != 0.0
        || precisionBonus != 0.0
        || onTargetBonus != 0.0
        || preFireStabilityBonus != 0.0;
  }
}
