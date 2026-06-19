package aiplay.rl.rewards.catalog;

/**
 * Top-level rewards.json knobs for the endgame-catchup mechanic. Lives outside
 * {@code rewardgroups} because the ramp shape is a global tuning parameter — only the per-role
 * {@code team_assist.weights.endgame_attack_bonus} (and the implicit modulator on
 * {@code defender_presence} / {@code cover_escort}) should differ between roles.
 *
 * <p>Urgency ramps linearly from 0 at {@code rampStartRemainingNorm} (remaining_time_norm exactly
 * at the start of endgame) to 1 at {@code rampFullRemainingNorm} (or below). Multiplied by a
 * binary "team-is-behind" indicator at apply time; see
 * {@code aiplay.rl.rewards.team.endgame.EndgameUrgency#urgency} for the full formula.
 *
 * <ul>
 *   <li>{@code rampStartRemainingNorm}: the remaining-time fraction at which catchup pressure
 *       starts ramping up. Typical: 0.20 (last 20% of match).</li>
 *   <li>{@code rampFullRemainingNorm}: the remaining-time fraction at which catchup pressure
 *       reaches maximum (1.0). Typical: 0.05. Must be strictly less than
 *       {@code rampStartRemainingNorm}.</li>
 * </ul>
 *
 * <p>Strict validation: both must be in {@code (0, 1)} with start &gt; full. No silent defaults
 * (CLAUDE.md no-fallback rule).
 */
public record EndgameUrgencyParams(
    double rampStartRemainingNorm,
    double rampFullRemainingNorm) {

  public EndgameUrgencyParams {
    if (!(rampStartRemainingNorm > 0.0 && rampStartRemainingNorm <= 1.0)) {
      throw new IllegalArgumentException(
          "EndgameUrgencyParams.rampStartRemainingNorm must be in (0, 1], got "
              + rampStartRemainingNorm);
    }
    if (!(rampFullRemainingNorm >= 0.0 && rampFullRemainingNorm < rampStartRemainingNorm)) {
      throw new IllegalArgumentException(
          "EndgameUrgencyParams.rampFullRemainingNorm must be in [0, rampStartRemainingNorm), got "
              + rampFullRemainingNorm
              + " (rampStart=" + rampStartRemainingNorm + ")");
    }
  }
}
