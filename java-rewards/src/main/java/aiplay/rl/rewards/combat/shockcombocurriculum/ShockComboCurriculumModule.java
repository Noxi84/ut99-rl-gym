package aiplay.rl.rewards.combat.shockcombocurriculum;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SHOCK_COMBO_CURRICULUM_SHAPING}. */
@RewardModuleComponent
public final class ShockComboCurriculumModule implements RewardModule<ShockComboCurriculumParams> {

  @Override
  public RewardId id() {
    return RewardId.SHOCK_COMBO_CURRICULUM_SHAPING;
  }

  @Override
  public ShockComboCurriculumParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block);
    JsonNode w = s.requireWeights(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block);
    return new ShockComboCurriculumParams(
        md,
        s.requireDouble(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, w, "weights.weight"),
        s.requireDouble(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block, "beam_sigma_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block, "enemy_sigma_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block, "max_ball_enemy_range_uu"),
        s.requireDouble(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, block, "max_delta_per_frame"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ShockComboCurriculumReward(ctx.catalog().shockComboCurriculum());
  }
}
