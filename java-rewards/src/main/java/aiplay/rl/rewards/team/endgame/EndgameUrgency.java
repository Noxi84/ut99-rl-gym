package aiplay.rl.rewards.team.endgame;

import aiplay.dto.GameStateDto;
import aiplay.rl.rewards.catalog.EndgameUrgencyParams;
import aiplay.shared.matchcontext.MatchTimingUtils;

/**
 * Computes the "endgame catchup" pressure scalar used by team-coordination reward shaping.
 *
 * <p>{@link #urgency} returns {@code 0.0} unless the bot's team is currently behind on score AND
 * the match is in its endgame ramp window. Inside the window the value scales linearly with how
 * deep we are into the ramp:
 *
 * <pre>
 *   urgency = behindIndicator × clamp01((rampStart - r) / (rampStart - rampFull))
 *   where:
 *     r                = MatchTimingUtils.remainingTimeNorm(state.mapInfo)
 *     behindIndicator  = (signedScoreDiff(state) < 0) ? 1.0 : 0.0
 * </pre>
 *
 * <p>Strict deficit (not "deficit &gt;= N") was chosen by design — every point we are behind
 * triggers the same catchup pressure once the ramp kicks in. Magnitude-scaling on
 * {@code signedScoreDiff} is left to {@code score_diff_norm} (the policy already sees it as a
 * feature); the reward only needs the binary trigger.
 *
 * <p>Sources of truth:
 * <ul>
 *   <li>{@code remaining_time_norm} ramp position → {@link MatchTimingUtils#remainingTimeNorm}
 *       (same helper the feature resolver uses, so reward + feature stay aligned).</li>
 *   <li>"Behind on score?" → {@link MatchTimingUtils#signedScoreDiff} &lt; 0.</li>
 *   <li>Ramp thresholds → {@link EndgameUrgencyParams} (top-level rewards.json block).</li>
 * </ul>
 */
public final class EndgameUrgency {

  private EndgameUrgency() {
  }

  /**
   * Endgame-catchup pressure for this transition. Returns {@code 0.0} outside the ramp window or
   * when the team is not behind. Returns {@code 1.0} when the team is behind AND
   * {@code remainingTimeNorm <= rampFullRemainingNorm}.
   */
  public static double urgency(GameStateDto state, EndgameUrgencyParams params) {
    if (state == null || params == null) {
      return 0.0;
    }
    int diff = MatchTimingUtils.signedScoreDiff(state);
    if (diff >= 0) {
      return 0.0;
    }
    float remainingNorm = MatchTimingUtils.remainingTimeNorm(state.mapInfo);
    double rampStart = params.rampStartRemainingNorm();
    double rampFull = params.rampFullRemainingNorm();
    if (remainingNorm >= rampStart) {
      return 0.0;
    }
    if (remainingNorm <= rampFull) {
      return 1.0;
    }
    double span = rampStart - rampFull;
    return (rampStart - remainingNorm) / span;
  }
}
