package aiplay.rl.rewards.movement.movementaction;

import aiplay.config.ModelConfigRepository;
import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#MOVEMENT_ACTION}. */
@RewardModuleComponent
public final class MovementActionModule implements RewardModule<MovementActionParams> {

  @Override
  public RewardId id() {
    return RewardId.MOVEMENT_ACTION;
  }

  @Override
  public MovementActionParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.MOVEMENT_ACTION, block);
    JsonNode w = s.requireWeights(RewardId.MOVEMENT_ACTION, block);
    return new MovementActionParams(
        md,
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.collision_penalty"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.floor_drop_penalty"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "floor_drop_danger_threshold_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.stuck_penalty"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.dodge_initiate"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.dodge"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "collision_near_threshold_norm"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "stuck_distance_threshold_units"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.idle_urgency_penalty"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.exposed_idle_penalty"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "exposed_idle_velocity_threshold_norm"),
        s.requireDouble(
            RewardId.MOVEMENT_ACTION, block, "exposed_idle_enemy_distance_threshold_norm"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.area_stuck_penalty"),
        s.requireInt(RewardId.MOVEMENT_ACTION, block, "area_stuck_window_ticks"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "area_stuck_radius_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.exploration_bonus"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "exploration_cell_size_uu"),
        s.requireInt(RewardId.MOVEMENT_ACTION, block, "exploration_cooldown_ticks"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.first_visit_bonus"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "exploration_first_visit_voxel_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.void_avoidance_scale"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "void_avoidance_full_drop_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "void_avoidance_clamp_per_tick"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "void_avoidance_z_jump_threshold_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, block, "exploration_grounded_floor_below_max_uu"),
        s.requireDouble(RewardId.MOVEMENT_ACTION, w, "weights.dodge_to_edge_penalty"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new MovementActionReward(
        ctx.catalog().movementAction(), resolveIdleActionIndex(ctx.modelKey()));
  }

  /**
   * Resolve the position of the {@code bIdle} target in the model's action vector, mirroring
   * {@code MovementModelSpec.idleIndex()} on the executor side. Returns {@code -1} when the model has
   * no idle output (legacy 5-target spec, or non-movement models whose action vectors have no
   * {@code bIdle} entry) — the reward then falls back to inferring idle from sin/cos magnitude alone.
   */
  private static int resolveIdleActionIndex(String modelKey) {
    return ModelConfigRepository.shared().get(modelKey).features().targetFeatures().indexOf("bIdle");
  }
}
