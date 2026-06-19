package aiplay.rl.rewards.combat.fireholdingpenalty;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense penalty zolang een fire-knop ingedrukt is en {@code aim_score < minAimScore}: voorkomt dat
 * de bot lange fire-bursts richting muren of niets vasthoudt. Werkt symmetrisch voor primary en alt
 * fire (additief wanneer beide tegelijk actief).
 *
 * <p>Vult {@code CombatEvent.shotOffTargetPenalty} aan: die geeft één puls op de rising edge van
 * fire-active, deze geeft sustained pressure tegen "knop ingedrukt houden".
 */
public record FireHoldingPenaltyParams(
    RewardMetadata metadata, double perTickPenalty, double minAimScore) implements RewardBlock {

  public FireHoldingPenaltyParams {
    if (metadata == null) {
      throw new IllegalArgumentException("FireHoldingPenaltyParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return perTickPenalty != 0.0;
  }
}
