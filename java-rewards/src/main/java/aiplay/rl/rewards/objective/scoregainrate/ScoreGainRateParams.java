package aiplay.rl.rewards.objective.scoregainrate;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense per-tick reward op basis van eigen UT99 score-gain rate over een rolling window.
 *
 * <p>Reden van bestaan: SAC-return op proxy reward divergeerde structureel van de DeltaGate
 * goal-metric (RL-bot score-gain vs UT99-baseline). De gate detecteerde Goodhart maar
 * dwong het beleid niet richting score-gain. Door score-gain-rate als dense reward toe te
 * voegen, krijgt de SAC-gradient direct signaal aligned met DeltaGate.
 *
 * <p>Berekening per tick:
 *
 * <pre>
 *   rate_per_min = (curr.score - oldest_score_in_window) / (curr.t - oldest.t) * 60_000
 *   reward       = weight * rate_per_min
 * </pre>
 *
 * <p>Stateful per bot: de reward-component houdt zelf een ringbuffer van (timestampMs, score)
 * snapshots bij. Een dalende score (match-restart, respawn-reset) detecteert hij door
 * {@code curr.score < oldest_score} → buffer wordt geleegd en reward = 0 voor die tick.
 *
 * <p>Owner: {@link aiplay.rl.rewards.catalog.RewardOwner#SHOOTING} — score-gain wordt grotendeels
 * door fire-decisions gestuurd. Movement en viewrotation hebben deze reward op {@code 0.0}.
 */
public record ScoreGainRateParams(
    RewardMetadata metadata,
    double weight,
    int windowMs,
    int minWindowMs)
    implements RewardBlock {

  public ScoreGainRateParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ScoreGainRateParams.metadata required");
    }
    if (windowMs <= 0) {
      throw new IllegalArgumentException("ScoreGainRateParams.windowMs must be > 0");
    }
    if (minWindowMs < 0 || minWindowMs > windowMs) {
      throw new IllegalArgumentException(
          "ScoreGainRateParams.minWindowMs must be in [0, windowMs]");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
