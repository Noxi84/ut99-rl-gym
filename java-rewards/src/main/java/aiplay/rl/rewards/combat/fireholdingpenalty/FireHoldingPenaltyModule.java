package aiplay.rl.rewards.combat.fireholdingpenalty;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#FIRE_HOLDING_PENALTY}. */
@RewardModuleComponent
public final class FireHoldingPenaltyModule implements RewardModule<FireHoldingPenaltyParams> {

  @Override
  public RewardId id() {
    return RewardId.FIRE_HOLDING_PENALTY;
  }

  @Override
  public FireHoldingPenaltyParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.FIRE_HOLDING_PENALTY, block);
    return new FireHoldingPenaltyParams(
        md,
        s.requireDouble(RewardId.FIRE_HOLDING_PENALTY, block, "per_tick_penalty"),
        s.requireDouble(RewardId.FIRE_HOLDING_PENALTY, block, "min_aim_score"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new FireHoldingPenaltyReward(ctx.catalog().fireHoldingPenalty());
  }
}
