package aiplay.rl.rewards.core;

import aiplay.dto.GameStateDto;

/**
 * Immutable context passed to every {@link RewardComponent} on each tick.
 *
 * @param prev   previous game state (non-null, playerPawn non-null)
 * @param curr   current game state (non-null, playerPawn non-null)
 * @param action action vector from the previous tick (may be null when action-rewards are not applicable)
 * @param config reward-tuning parameters (match timing, weapon speeds, pitch curriculum)
 * @param modelTargetIndex Phase 2: shooting model's argmax target_index from
 *        {@code ShootingTargetIndexBus}. {@code -1} = no model choice yet,
 *        readers fall back to engagement target via LeadAimUtils.
 */
public record RewardContext(
    GameStateDto prev,
    GameStateDto curr,
    float[] action,
    RewardTuningConfig config,
    int modelTargetIndex
) {
    /** Backward-compat constructor for callers that don't have model_target_index. */
    public RewardContext(GameStateDto prev, GameStateDto curr, float[] action, RewardTuningConfig config) {
        this(prev, curr, action, config, -1);
    }
}
