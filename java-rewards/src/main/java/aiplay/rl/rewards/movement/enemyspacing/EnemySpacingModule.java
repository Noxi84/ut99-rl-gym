package aiplay.rl.rewards.movement.enemyspacing;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#ENEMY_SPACING}. */
@RewardModuleComponent
public final class EnemySpacingModule implements RewardModule<EnemySpacingParams> {

  @Override
  public RewardId id() {
    return RewardId.ENEMY_SPACING;
  }

  @Override
  public EnemySpacingParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.ENEMY_SPACING, block);
    JsonNode w = s.requireWeights(RewardId.ENEMY_SPACING, block);
    return new EnemySpacingParams(
        md,
        s.requireDouble(RewardId.ENEMY_SPACING, block, "min_norm"),
        s.requireDouble(RewardId.ENEMY_SPACING, block, "ideal_min_norm"),
        s.requireDouble(RewardId.ENEMY_SPACING, block, "ideal_max_norm"),
        s.requireDouble(RewardId.ENEMY_SPACING, block, "max_norm"),
        s.requireDouble(RewardId.ENEMY_SPACING, w, "weights.too_close_penalty"),
        s.requireDouble(RewardId.ENEMY_SPACING, w, "weights.delta_scale"),
        s.requireDouble(RewardId.ENEMY_SPACING, w, "weights.ideal_bonus"),
        s.requireDouble(RewardId.ENEMY_SPACING, w, "weights.too_far_closing_scale"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new EnemySpacingReward(ctx.catalog().enemySpacing());
  }
}
