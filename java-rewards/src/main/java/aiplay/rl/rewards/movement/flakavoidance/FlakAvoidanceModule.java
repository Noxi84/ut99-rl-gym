package aiplay.rl.rewards.movement.flakavoidance;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#FLAK_AVOIDANCE}. */
@RewardModuleComponent
public final class FlakAvoidanceModule implements RewardModule<FlakAvoidanceParams> {

  @Override
  public RewardId id() {
    return RewardId.FLAK_AVOIDANCE;
  }

  @Override
  public FlakAvoidanceParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.FLAK_AVOIDANCE, block);
    JsonNode w = s.requireWeights(RewardId.FLAK_AVOIDANCE, block);
    return new FlakAvoidanceParams(
        md,
        s.requireDouble(RewardId.FLAK_AVOIDANCE, w, "weights.instant_weight"),
        s.requireDouble(RewardId.FLAK_AVOIDANCE, w, "weights.delta_weight"),
        s.requireDouble(RewardId.FLAK_AVOIDANCE, block, "urgency_threshold_norm"),
        s.requireDouble(RewardId.FLAK_AVOIDANCE, block, "delta_clamp_per_tick"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new FlakAvoidanceReward(ctx.catalog().flakAvoidance());
  }
}
