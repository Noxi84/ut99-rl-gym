package aiplay.rl.rewards.movement.flakavoidance;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Threat-aware shaping voor inbound flak-projectielen (chunks + grenades). Per tick aggregatie over
 * alle enemy-projectile-slots, gefilterd op {@code isChunk == 1 || isGrenade == 1}.
 *
 * <p>Twee complementaire signalen:
 *
 * <ul>
 *   <li>{@code instantWeight}: shaping op huidige miss-margin —
 *       {@code Σ CAD_norm × urgency} over alle relevant flak.
 *       Leert positionering: bot krijgt continu reward voor staan op een plek waar inbound
 *       flak hem mist. Geen reward zonder dreiging.</li>
 *   <li>{@code deltaWeight}: shaping op afname van threat —
 *       {@code -clamp(threat_curr − threat_prev)} met
 *       {@code threat = max((1 − CAD_norm) × urgency)}.
 *       Beloont actief uit-baan-bewegen; spawn/disappear-spikes worden gedempt door
 *       {@code deltaClampPerTick}.</li>
 * </ul>
 *
 * <p>{@code urgency = max(0, 1 − timeToImpact_norm / urgencyThresholdNorm)} — lineair afnemend
 * vanaf impact tot {@code timeToImpact_norm == urgencyThresholdNorm}, dan 0. Filtert ver weg
 * vliegende projectielen die de bot toch al niet bedreigen.
 */
public record FlakAvoidanceParams(
    RewardMetadata metadata,
    double instantWeight,
    double deltaWeight,
    double urgencyThresholdNorm,
    double deltaClampPerTick)
    implements RewardBlock {

  public FlakAvoidanceParams {
    if (metadata == null) {
      throw new IllegalArgumentException("FlakAvoidanceParams.metadata required");
    }
    if (urgencyThresholdNorm <= 0.0 || urgencyThresholdNorm > 1.0) {
      throw new IllegalArgumentException(
          "FlakAvoidanceParams.urgencyThresholdNorm must be in (0,1], was " + urgencyThresholdNorm);
    }
    if (deltaClampPerTick < 0.0) {
      throw new IllegalArgumentException(
          "FlakAvoidanceParams.deltaClampPerTick must be >= 0, was " + deltaClampPerTick);
    }
  }

  @Override
  public boolean enabled() {
    return instantWeight != 0.0 || deltaWeight != 0.0;
  }
}
