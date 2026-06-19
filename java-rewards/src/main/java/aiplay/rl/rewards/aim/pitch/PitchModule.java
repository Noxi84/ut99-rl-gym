package aiplay.rl.rewards.aim.pitch;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#PITCH}. */
@RewardModuleComponent
public final class PitchModule implements RewardModule<PitchParams> {

  @Override
  public RewardId id() {
    return RewardId.PITCH;
  }

  @Override
  public PitchParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.PITCH, block);
    JsonNode w = s.requireWeights(RewardId.PITCH, block);
    return new PitchParams(
        md,
        s.requireDouble(RewardId.PITCH, w, "weights.alignment_weight"),
        s.requireDouble(RewardId.PITCH, w, "weights.acquisition_bonus"),
        s.requireDouble(RewardId.PITCH, w, "weights.extreme_penalty"),
        s.requireDouble(RewardId.PITCH, block, "extreme_threshold_norm"),
        s.requireDouble(RewardId.PITCH, block, "extreme_target_margin_norm"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new PitchReward(ctx.catalog().pitch());
  }
}
