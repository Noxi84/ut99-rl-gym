package aiplay.rl.rewards.combat.ammoconsumptionpenalty;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#AMMO_CONSUMPTION_PENALTY}. */
@RewardModuleComponent
public final class AmmoConsumptionPenaltyModule
    implements RewardModule<AmmoConsumptionPenaltyParams> {

  @Override
  public RewardId id() {
    return RewardId.AMMO_CONSUMPTION_PENALTY;
  }

  @Override
  public AmmoConsumptionPenaltyParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.AMMO_CONSUMPTION_PENALTY, block);
    return new AmmoConsumptionPenaltyParams(
        md,
        s.requireDouble(RewardId.AMMO_CONSUMPTION_PENALTY, block, "per_ammo_penalty"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new AmmoConsumptionPenaltyReward(ctx.catalog().ammoConsumptionPenalty());
  }
}
