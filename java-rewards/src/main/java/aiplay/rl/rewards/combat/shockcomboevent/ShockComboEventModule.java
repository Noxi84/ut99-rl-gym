package aiplay.rl.rewards.combat.shockcomboevent;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SHOCK_COMBO_EVENT}. */
@RewardModuleComponent
public final class ShockComboEventModule implements RewardModule<ShockComboEventParams> {

  @Override
  public RewardId id() {
    return RewardId.SHOCK_COMBO_EVENT;
  }

  @Override
  public ShockComboEventParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SHOCK_COMBO_EVENT, block);
    JsonNode w = s.requireWeights(RewardId.SHOCK_COMBO_EVENT, block);
    return new ShockComboEventParams(
        md,
        s.requireDouble(RewardId.SHOCK_COMBO_EVENT, w, "weights.event_bonus"),
        s.requireDouble(RewardId.SHOCK_COMBO_EVENT, block, "combo_min_damage"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ShockComboEventReward(ctx.catalog().shockComboEvent());
  }
}
