package aiplay.rl.rewards.core;

/**
 * Single reward sub-component. Each implementation computes one logically coherent
 * slice of the total reward (e.g. flag events, combat, progress shaping).
 *
 * <p>The orchestrating {@link aiplay.rl.RewardComputer} creates all components
 * at construction time and calls {@link #compute} on every tick.</p>
 */
public interface RewardComponent {

  /**
   * Compute this component's reward contribution for the transition prev → curr.
   *
   * <p>Callers guarantee that both {@code prev} and {@code curr} have a non-null
   * {@code playerPawn} with health &gt; 0 in the previous frame. Null/death edge
   * cases are handled by the orchestrator before dispatching.</p>
   *
   * @param ctx shared context containing prev/curr state, optional action, and config
   * @return scalar reward contribution (may be positive, negative, or zero)
   */
  double compute(RewardContext ctx);
}
