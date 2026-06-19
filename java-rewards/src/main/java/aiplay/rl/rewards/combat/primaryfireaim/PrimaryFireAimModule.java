package aiplay.rl.rewards.combat.primaryfireaim;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#PRIMARY_FIRE_AIM}. */
@RewardModuleComponent
public final class PrimaryFireAimModule implements RewardModule<PrimaryFireAimParams> {

  @Override
  public RewardId id() {
    return RewardId.PRIMARY_FIRE_AIM;
  }

  @Override
  public PrimaryFireAimParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.PRIMARY_FIRE_AIM, block);
    return new PrimaryFireAimParams(
        md,
        s.requireDouble(RewardId.PRIMARY_FIRE_AIM, block, "weight"),
        s.requireDouble(RewardId.PRIMARY_FIRE_AIM, block, "min_score"),
        s.requireDouble(RewardId.PRIMARY_FIRE_AIM, block, "long_range_damping_sigma_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new PrimaryFireAimReward(ctx.catalog().primaryFireAim());
  }
}
