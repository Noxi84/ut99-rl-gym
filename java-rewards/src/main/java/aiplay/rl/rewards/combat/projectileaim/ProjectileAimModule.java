package aiplay.rl.rewards.combat.projectileaim;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#PROJECTILE_AIM}. */
@RewardModuleComponent
public final class ProjectileAimModule implements RewardModule<ProjectileAimParams> {

  @Override
  public RewardId id() {
    return RewardId.PROJECTILE_AIM;
  }

  @Override
  public ProjectileAimParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.PROJECTILE_AIM, block);
    return new ProjectileAimParams(
        md,
        s.requireDouble(RewardId.PROJECTILE_AIM, block, "weight"),
        s.requireDouble(RewardId.PROJECTILE_AIM, block, "sigma_ut"),
        s.requireDouble(RewardId.PROJECTILE_AIM, block, "close_range_damping_sigma_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ProjectileAimReward(ctx.catalog().projectileAim());
  }
}
