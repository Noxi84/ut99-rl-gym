package aiplay.rl.rewards.team.defenderpresence;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#DEFENDER_PRESENCE}. */
@RewardModuleComponent
public final class DefenderPresenceModule implements RewardModule<DefenderPresenceParams> {

  @Override
  public RewardId id() {
    return RewardId.DEFENDER_PRESENCE;
  }

  @Override
  public DefenderPresenceParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.DEFENDER_PRESENCE, block);
    JsonNode w = s.requireWeights(RewardId.DEFENDER_PRESENCE, block);
    return new DefenderPresenceParams(
        md,
        s.requireDouble(RewardId.DEFENDER_PRESENCE, w, "weights.home_bonus"),
        s.requireDouble(RewardId.DEFENDER_PRESENCE, w, "weights.enemy_half_penalty"),
        s.requireDouble(RewardId.DEFENDER_PRESENCE, block, "engagement_threshold"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new DefenderPresenceReward(
        ctx.catalog().defenderPresence(), ctx.catalog().endgameUrgency());
  }
}
