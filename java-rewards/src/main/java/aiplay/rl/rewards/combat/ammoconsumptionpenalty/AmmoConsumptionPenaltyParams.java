package aiplay.rl.rewards.combat.ammoconsumptionpenalty;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Per-ammo-unit penalty toegepast op elke verbruikte ammo tussen prev en curr frame.
 * Vervangt de per-tick fire-holding straf door een resource-cost: spam wordt automatisch
 * gestraft (ammo loopt leeg), terwijl één gerichte burst proportioneel kleiner kost.
 *
 * <p>Combineerd met {@code damage_delta.dealt_per_hp}: één hit (10 HP × 0.15 = +1.5)
 * overstemt makkelijk de ammo-cost (~1 ammo × 0.05 = -0.05). Een miss-burst van 8 ammo
 * zonder hit kost -0.4. Bot leert efficiency, niet binaire schiet/niet-schiet keuze.
 */
public record AmmoConsumptionPenaltyParams(
    RewardMetadata metadata, double perAmmoPenalty) implements RewardBlock {

  public AmmoConsumptionPenaltyParams {
    if (metadata == null) {
      throw new IllegalArgumentException("AmmoConsumptionPenaltyParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return perAmmoPenalty != 0.0;
  }
}
