package aiplay.rl.rewards.combat.shockcomboclick;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SHOCK_COMBO_CLICK}. */
@RewardModuleComponent
public final class ShockComboClickModule implements RewardModule<ShockComboClickParams> {

  @Override
  public RewardId id() {
    return RewardId.SHOCK_COMBO_CLICK;
  }

  @Override
  public ShockComboClickParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SHOCK_COMBO_CLICK, block);
    JsonNode w = s.requireWeights(RewardId.SHOCK_COMBO_CLICK, block);
    return new ShockComboClickParams(
        md,
        s.requireDouble(RewardId.SHOCK_COMBO_CLICK, w, "weights.weight"),
        s.requireDouble(RewardId.SHOCK_COMBO_CLICK, block, "beam_sigma_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CLICK, block, "enemy_sigma_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CLICK, block, "min_ball_dist_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CLICK, block, "baseline_offset"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ShockComboClickReward(ctx.catalog().shockComboClick());
  }
}
