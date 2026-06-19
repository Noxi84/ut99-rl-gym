package aiplay.rl.rewards.objective.flagevent;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#FLAG_EVENT}. */
@RewardModuleComponent
public final class FlagEventModule implements RewardModule<FlagEventParams> {

  @Override
  public RewardId id() {
    return RewardId.FLAG_EVENT;
  }

  @Override
  public FlagEventParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.FLAG_EVENT, block);
    JsonNode w = s.requireWeights(RewardId.FLAG_EVENT, block);
    return new FlagEventParams(
        md,
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.taken"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.dropped"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.captured"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.team_captured"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.enemy_captured"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.returned"),
        s.requireDouble(RewardId.FLAG_EVENT, w, "weights.team_returned"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new FlagEventReward(ctx.catalog().flagEvent());
  }
}
