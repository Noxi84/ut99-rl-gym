package aiplay.rl.rewards.aim.enemyspawnattention;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#ENEMY_SPAWN_ATTENTION}. */
@RewardModuleComponent
public final class EnemySpawnAttentionModule implements RewardModule<EnemySpawnAttentionParams> {

  @Override
  public RewardId id() {
    return RewardId.ENEMY_SPAWN_ATTENTION;
  }

  @Override
  public EnemySpawnAttentionParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.ENEMY_SPAWN_ATTENTION, block);
    return new EnemySpawnAttentionParams(
        md,
        s.requireDouble(RewardId.ENEMY_SPAWN_ATTENTION, block, "weight"),
        s.requireInt(RewardId.ENEMY_SPAWN_ATTENTION, block, "hold_ticks"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new EnemySpawnAttentionReward(ctx.catalog().enemySpawnAttention());
  }
}
