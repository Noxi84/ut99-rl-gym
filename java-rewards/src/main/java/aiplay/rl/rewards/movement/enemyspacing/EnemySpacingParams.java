package aiplay.rl.rewards.movement.enemyspacing;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense afstandsmanagement t.o.v. de dichtstbijzijnde levende enemy, gemeten als 2D afstand
 * genormaliseerd op de inter-base afstand van de map (zo werken dezelfde drempelwaarden op grote en
 * kleine maps).
 *
 * <p>Drie regimes:
 * <ul>
 *   <li>te dichtbij ({@code dist < minNorm}): {@code tooClosePenalty} + escape-shaping via
 *       {@code deltaScale · (currDist - prevDist)}.</li>
 *   <li>ideale band ({@code idealMinNorm ≤ dist ≤ idealMaxNorm}): {@code idealBonus} per tick.</li>
 *   <li>te ver ({@code dist > maxNorm}, alleen tijdens "pressure mode" = eigen vlag wordt gedragen
 *       door enemy): closing-shaping via {@code tooFarClosingScale · (prevDist - currDist)}.</li>
 * </ul>
 */
public record EnemySpacingParams(
    RewardMetadata metadata,
    double minNorm,
    double idealMinNorm,
    double idealMaxNorm,
    double maxNorm,
    double tooClosePenalty,
    double deltaScale,
    double idealBonus,
    double tooFarClosingScale)
    implements RewardBlock {

  public EnemySpacingParams {
    if (metadata == null) {
      throw new IllegalArgumentException("EnemySpacingParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return tooClosePenalty != 0.0 || idealBonus != 0.0 || tooFarClosingScale != 0.0;
  }
}
