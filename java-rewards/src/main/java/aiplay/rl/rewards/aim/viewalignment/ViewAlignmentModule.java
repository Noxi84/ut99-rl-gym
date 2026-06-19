package aiplay.rl.rewards.aim.viewalignment;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#VIEW_ALIGNMENT}. */
@RewardModuleComponent
public final class ViewAlignmentModule implements RewardModule<ViewAlignmentParams> {

  @Override
  public RewardId id() {
    return RewardId.VIEW_ALIGNMENT;
  }

  @Override
  public ViewAlignmentParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.VIEW_ALIGNMENT, block);
    JsonNode w = s.requireWeights(RewardId.VIEW_ALIGNMENT, block);
    return new ViewAlignmentParams(
        md,
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.enemy_alignment_bonus"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.objective_alignment_bonus"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.acquisition_bonus"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.precision_bonus"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, block, "precision_threshold"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.on_target_bonus"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, block, "on_target_threshold"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, w, "weights.pre_fire_stability_bonus"),
        s.requireDouble(
            RewardId.VIEW_ALIGNMENT, block, "pre_fire_stability_aim_score_threshold"),
        s.requireDouble(
            RewardId.VIEW_ALIGNMENT, block, "pre_fire_stability_yaw_velocity_threshold_norm"),
        s.requireDouble(RewardId.VIEW_ALIGNMENT, block, "enemy_alignment_max_range_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ViewAlignmentReward(ctx.catalog().viewAlignment());
  }
}
