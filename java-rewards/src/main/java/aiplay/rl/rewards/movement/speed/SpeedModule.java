package aiplay.rl.rewards.movement.speed;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SPEED}. */
@RewardModuleComponent
public final class SpeedModule implements RewardModule<SpeedParams> {

  @Override
  public RewardId id() {
    return RewardId.SPEED;
  }

  @Override
  public SpeedParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SPEED, block);
    return new SpeedParams(md, s.requireDouble(RewardId.SPEED, block, "weight"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new SpeedReward(ctx.catalog().speed());
  }
}
