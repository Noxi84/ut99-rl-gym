package aiplay.rl.rewards.objective.objectiveprogress;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#OBJECTIVE_PROGRESS}. */
@RewardModuleComponent
public final class ObjectiveProgressModule implements RewardModule<ObjectiveProgressParams> {

  @Override
  public RewardId id() {
    return RewardId.OBJECTIVE_PROGRESS;
  }

  @Override
  public ObjectiveProgressParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.OBJECTIVE_PROGRESS, block);
    JsonNode w = s.requireWeights(RewardId.OBJECTIVE_PROGRESS, block);
    return new ObjectiveProgressParams(
        md,
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.progress_scale"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.alive_bonus"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.own_flag_return_progress_scale"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.carrier_progress_scale"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.carrier_proximity_bonus"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, block, "carrier_proximity_radius_uu"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, block, "efc_engagement_range_uu"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, w, "weights.efc_threat_progress_scale"),
        s.requireDouble(RewardId.OBJECTIVE_PROGRESS, block, "efc_threat_proximity_range_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ObjectiveProgressReward(ctx.catalog().objectiveProgress());
  }
}
