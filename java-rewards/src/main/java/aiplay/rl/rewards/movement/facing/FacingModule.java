package aiplay.rl.rewards.movement.facing;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#FACING}. */
@RewardModuleComponent
public final class FacingModule implements RewardModule<FacingParams> {

  @Override
  public RewardId id() {
    return RewardId.FACING;
  }

  @Override
  public FacingParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.FACING, block);
    return new FacingParams(md, s.requireDouble(RewardId.FACING, block, "weight"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new FacingReward(ctx.catalog().facing());
  }
}
