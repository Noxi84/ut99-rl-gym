package aiplay.rl.rewards.combat.combatevent;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#COMBAT_EVENT}. */
@RewardModuleComponent
public final class CombatEventModule implements RewardModule<CombatEventParams> {

  @Override
  public RewardId id() {
    return RewardId.COMBAT_EVENT;
  }

  @Override
  public CombatEventParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.COMBAT_EVENT, block);
    JsonNode w = s.requireWeights(RewardId.COMBAT_EVENT, block);
    JsonNode kc = s.requireField(RewardId.COMBAT_EVENT, block, "kill_credit");
    return new CombatEventParams(
        md,
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.frag"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.death"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.fire_penalty"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.fire_during_cooldown_penalty"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.shot_on_target_bonus_primary"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.shot_on_target_bonus_alt"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.shot_off_target_penalty_primary"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.shot_off_target_penalty_alt"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.missed_opportunity_penalty"),
        s.requireDouble(RewardId.COMBAT_EVENT, block, "shot_min_aim_score"),
        s.requireDouble(RewardId.COMBAT_EVENT, block, "shot_precision_exponent"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.shot_perfection_bonus"),
        s.requireDouble(RewardId.COMBAT_EVENT, block, "shot_perfection_threshold"),
        s.requireDouble(RewardId.COMBAT_EVENT, w, "weights.enemy_killed_by_fire"),
        s.requireBoolean(RewardId.COMBAT_EVENT, block, "shot_on_target_instagib_only"),
        s.requireBoolean(RewardId.COMBAT_EVENT, kc, "kill_credit.backprop_enabled"),
        s.requireInt(RewardId.COMBAT_EVENT, kc, "kill_credit.window_ticks"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new CombatEventReward(ctx.catalog().combatEvent());
  }
}
