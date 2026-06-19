package aiplay.rl.rewards.team.coverescort;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#COVER_ESCORT}. */
@RewardModuleComponent
public final class CoverEscortModule implements RewardModule<CoverEscortParams> {

  @Override
  public RewardId id() {
    return RewardId.COVER_ESCORT;
  }

  @Override
  public CoverEscortParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.COVER_ESCORT, block);
    JsonNode w = s.requireWeights(RewardId.COVER_ESCORT, block);
    return new CoverEscortParams(
        md,
        s.requireDouble(RewardId.COVER_ESCORT, w, "weights.proximity_bonus"),
        s.requireDouble(RewardId.COVER_ESCORT, w, "weights.far_penalty"),
        s.requireDouble(RewardId.COVER_ESCORT, w, "weights.berth_penalty"),
        s.requireDouble(RewardId.COVER_ESCORT, block, "escort_range_uu"),
        s.requireDouble(RewardId.COVER_ESCORT, block, "far_range_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new CoverEscortReward(ctx.catalog().coverEscort(), ctx.catalog().endgameUrgency());
  }
}
