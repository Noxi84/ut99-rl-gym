package aiplay.rl.rewards.objective.flagcarrierkill;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#FLAG_CARRIER_KILL}. */
@RewardModuleComponent
public final class FlagCarrierKillModule implements RewardModule<FlagCarrierKillParams> {

  @Override
  public RewardId id() {
    return RewardId.FLAG_CARRIER_KILL;
  }

  @Override
  public FlagCarrierKillParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.FLAG_CARRIER_KILL, block);
    JsonNode w = s.requireWeights(RewardId.FLAG_CARRIER_KILL, block);
    return new FlagCarrierKillParams(
        md,
        s.requireDouble(RewardId.FLAG_CARRIER_KILL, w, "weights.bonus"),
        s.requireDouble(RewardId.FLAG_CARRIER_KILL, w, "weights.near_base_bonus"),
        s.requireDouble(RewardId.FLAG_CARRIER_KILL, block, "near_base_max_distance_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new FlagCarrierKillReward(ctx.catalog().flagCarrierKill());
  }
}
