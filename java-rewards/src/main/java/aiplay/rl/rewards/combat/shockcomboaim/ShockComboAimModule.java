package aiplay.rl.rewards.combat.shockcomboaim;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SHOCK_COMBO_AIM}. */
@RewardModuleComponent
public final class ShockComboAimModule implements RewardModule<ShockComboAimParams> {

  @Override
  public RewardId id() {
    return RewardId.SHOCK_COMBO_AIM;
  }

  @Override
  public ShockComboAimParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SHOCK_COMBO_AIM, block);
    JsonNode w = s.requireWeights(RewardId.SHOCK_COMBO_AIM, block);
    return new ShockComboAimParams(
        md,
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, w, "weights.weight"),
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, block, "min_aim_cos"),
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, block, "min_ball_dist_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, block, "max_ball_dist_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, block, "enemy_context_range_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_AIM, block, "ball_enemy_sigma_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ShockComboAimReward(ctx.catalog().shockComboAim());
  }
}
