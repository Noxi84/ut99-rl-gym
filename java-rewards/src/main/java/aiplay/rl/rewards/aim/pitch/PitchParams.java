package aiplay.rl.rewards.aim.pitch;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense pitch-alignment richting de attention target (zelfde gating-semantic als
 * {@link ViewAlignmentParams} — vuurt alleen wanneer de attention target een enemy is, zoals
 * bepaald door {@code RewardUtils.isEnemyAttentionTarget}; bij OBJECTIVE/NONE komt er geen
 * pitch-penalty zodat de bot vrij naar pad/objective kan kijken).
 *
 * <p>Lineaire shape ({@code -alignmentWeight · |pitchError|}) — symmetrisch met yaw's cos²
 * gradient-magnitude en voorkomt de constante "kijk-omlaag" bias die quadratisch met
 * grote weight (5.0) opleverde (gain_bias gate failures).
 *
 * <p>{@code acquisitionBonus} is potential-based shaping: beloont vermindering van
 * |pitchError| tussen prev en curr — geeft een dichte gradient zonder de optimal policy
 * te verschuiven. Analoog aan {@code view_alignment.acquisition_bonus} voor yaw.
 *
 * <p>Daarbovenop een {@code extremePenalty} voor pitch-waarden voorbij de toegestane range. De
 * range = max({@code extremeThresholdNorm}, |targetPitch| + {@code extremeTargetMarginNorm}) — zo
 * blijft de bot binnen redelijke kijkhoeken zelfs als de target hoog/laag staat.
 */
public record PitchParams(
    RewardMetadata metadata,
    double alignmentWeight,
    double acquisitionBonus,
    double extremePenalty,
    double extremeThresholdNorm,
    double extremeTargetMarginNorm)
    implements RewardBlock {

  public PitchParams {
    if (metadata == null) {
      throw new IllegalArgumentException("PitchParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return alignmentWeight != 0.0 || acquisitionBonus != 0.0 || extremePenalty != 0.0;
  }
}
