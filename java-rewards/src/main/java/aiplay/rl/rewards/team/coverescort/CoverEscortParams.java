package aiplay.rl.rewards.team.coverescort;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense escort shaping for the Cover role: reward proximity to whichever teammate currently
 * carries a flag.
 *
 * <ul>
 *   <li>{@code proximityBonus}: per-tick reward when the bot is closer than {@code escortRangeUu}
 *       to the teammate-carrier. Linear ramp 1.0 → 0.0 as distance approaches the range, computed
 *       on {@code max(dist, escort-standoff)} so the ramp plateaus inside the standoff band
 *       (band edge = attractor; the escort is never incentivised to step INTO the carrier).</li>
 *   <li>{@code farPenalty}: per-tick penalty when the bot is further than {@code farRangeUu} from
 *       the carrier — discourages drifting away from the escort target.</li>
 *   <li>{@code berthPenalty}: per-tick penalty ramp (magnitude at 0 UU, linear → 0 at the
 *       standoff edge) while the capture funnel is active — the teammate-carrier can score (own
 *       flag home) and is in the last-mile near base. Actively pushes an already-glued escort out
 *       of the carrier's capture path; all positive escort pulls are simultaneously released.
 *       Also set (via rewardgroup-override) for Attack, whose {@code objective_progress} pull
 *       targets the same teammate-carrier. See {@code EscortObjectiveResolver}.</li>
 *   <li>{@code escortRangeUu}: distance (UU) under which the proximity bonus is active.</li>
 *   <li>{@code farRangeUu}: distance (UU) above which the far penalty is active.</li>
 * </ul>
 *
 * <p>Returns zero when no teammate carries a flag — the sparse {@code escort_proximity_dense}
 * branch of the {@code team_assist} head handles the open-field escort case independently (and
 * skips the carrier, so the two never stack on the same target).
 */
public record CoverEscortParams(
    RewardMetadata metadata,
    double proximityBonus,
    double farPenalty,
    double berthPenalty,
    double escortRangeUu,
    double farRangeUu)
    implements RewardBlock {

  public CoverEscortParams {
    if (metadata == null) {
      throw new IllegalArgumentException("CoverEscortParams.metadata required");
    }
    if (berthPenalty < 0.0) {
      throw new IllegalArgumentException(
          "CoverEscortParams.berthPenalty must be >= 0 (magnitude, applied as a penalty)");
    }
    if (escortRangeUu <= 0.0) {
      throw new IllegalArgumentException("CoverEscortParams.escortRangeUu must be > 0");
    }
    if (farRangeUu < escortRangeUu) {
      throw new IllegalArgumentException(
          "CoverEscortParams.farRangeUu must be >= escortRangeUu");
    }
  }

  @Override
  public boolean enabled() {
    return proximityBonus != 0.0 || farPenalty != 0.0 || berthPenalty != 0.0;
  }
}
