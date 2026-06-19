package aiplay.rl.rewards.aim.viewsmoothness;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#VIEW_SMOOTHNESS}. */
@RewardModuleComponent
public final class ViewSmoothnessModule implements RewardModule<ViewSmoothnessParams> {

  @Override
  public RewardId id() {
    return RewardId.VIEW_SMOOTHNESS;
  }

  @Override
  public ViewSmoothnessParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.VIEW_SMOOTHNESS, block);
    JsonNode w = s.requireWeights(RewardId.VIEW_SMOOTHNESS, block);
    return new ViewSmoothnessParams(
        md,
        s.requireDouble(RewardId.VIEW_SMOOTHNESS, w, "weights.smoothness_penalty"),
        s.requireDouble(RewardId.VIEW_SMOOTHNESS, w, "weights.yaw_oscillation_penalty"),
        s.requireDouble(RewardId.VIEW_SMOOTHNESS, w, "weights.post_fire_commitment_penalty"),
        s.requireInt(RewardId.VIEW_SMOOTHNESS, block, "post_fire_commitment_window_ticks"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ViewSmoothnessReward(ctx.catalog().viewSmoothness());
  }
}
