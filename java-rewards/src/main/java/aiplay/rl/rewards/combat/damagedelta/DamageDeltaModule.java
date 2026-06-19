package aiplay.rl.rewards.combat.damagedelta;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#DAMAGE_DELTA}. */
@RewardModuleComponent
public final class DamageDeltaModule implements RewardModule<DamageDeltaParams> {

  @Override
  public RewardId id() {
    return RewardId.DAMAGE_DELTA;
  }

  @Override
  public DamageDeltaParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.DAMAGE_DELTA, block);
    JsonNode w = s.requireWeights(RewardId.DAMAGE_DELTA, block);
    return new DamageDeltaParams(
        md,
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.dealt_per_hp"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.taken_per_hp"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.self_damage_extra_per_hp"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.self_damage_max_hp_per_event"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.friendly_fire_per_hp"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.headshot_bonus"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.efc_damage_bonus_per_hp"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.void_fall_penalty"),
        s.requireDouble(RewardId.DAMAGE_DELTA, w, "weights.void_fall_hp_threshold"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new DamageDeltaReward(ctx.catalog().damageDelta());
  }
}
