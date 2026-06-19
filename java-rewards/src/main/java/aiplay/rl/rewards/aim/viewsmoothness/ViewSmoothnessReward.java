package aiplay.rl.rewards.aim.viewsmoothness;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;

/**
 * Dense view smoothness penalty: penalizes large view rotation changes, yaw oscillation, and large
 * yaw/pitch deltas within a window after fire-edge (post-fire commitment — forces follow-through on
 * the shot). Stateful — tracks previous yaw delta for oscillation detection and last fire-edge tick
 * for post-fire commitment.
 */
public class ViewSmoothnessReward implements RewardComponent {

  private final ViewSmoothnessParams params;
  private int prevYawDelta = 0;
  private long tickCount = 0;
  private long lastFireEdgeTick = Long.MIN_VALUE;

  public ViewSmoothnessReward(ViewSmoothnessParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "ViewSmoothnessReward requires non-null ViewSmoothnessParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    tickCount++;

    if (ctx.curr().playerPawn.health <= 0) {
      return 0.0;
    }

    // Track fire-edge — needed both for post-fire commitment and as state across ticks.
    if (ctx.prev().playerPawn != null) {
      boolean fireEdge =
          (!ctx.prev().playerPawn.fireActive && ctx.curr().playerPawn.fireActive)
              || (!ctx.prev().playerPawn.altFireActive && ctx.curr().playerPawn.altFireActive);
      if (fireEdge) lastFireEdgeTick = tickCount;
    }

    if (params.smoothnessPenalty() == 0.0
        && params.yawOscillationPenalty() == 0.0
        && params.postFireCommitmentPenalty() == 0.0) {
      return 0.0;
    }

    if (ctx.prev().playerPawn.viewRotation == null
        || ctx.curr().playerPawn.viewRotation == null) {
      return 0.0;
    }

    // Yaw delta with wrap-around handling (UT rotation: 0..65535)
    int prevYaw = ctx.prev().playerPawn.viewRotation.x & 0xFFFF;
    int currYaw = ctx.curr().playerPawn.viewRotation.x & 0xFFFF;
    int rawYawDelta = currYaw - prevYaw;
    if (rawYawDelta > 32768) {
      rawYawDelta -= 65536;
    }
    if (rawYawDelta < -32768) {
      rawYawDelta += 65536;
    }

    // Pitch delta (no wrapping)
    int pitchDelta = ctx.curr().playerPawn.viewRotation.y - ctx.prev().playerPawn.viewRotation.y;

    // Normalize to fraction of full rotation
    double yawNorm = Math.abs(rawYawDelta) / 65536.0;
    double pitchNorm = Math.abs(pitchDelta) / 65536.0;

    double penalty = -params.smoothnessPenalty() * (yawNorm + pitchNorm);

    // Yaw oscillation: penalize sign reversals (left→right→left swinging)
    if (params.yawOscillationPenalty() != 0.0
        && prevYawDelta != 0
        && rawYawDelta != 0
        && ((prevYawDelta > 0) != (rawYawDelta > 0))) {
      double wastedNorm = Math.min(Math.abs(prevYawDelta), Math.abs(rawYawDelta)) / 65536.0;
      penalty -= params.yawOscillationPenalty() * wastedNorm;
    }
    prevYawDelta = (rawYawDelta != 0) ? rawYawDelta : prevYawDelta;

    // Post-fire commitment penalty: extra view-jerk penalty within N ticks after fire-edge.
    // Forces follow-through — model can't fire then immediately swing to a different target.
    int window = params.postFireCommitmentWindowTicks();
    double postFireScale = params.postFireCommitmentPenalty();
    if (window > 0
        && postFireScale != 0.0
        && lastFireEdgeTick != Long.MIN_VALUE
        && (tickCount - lastFireEdgeTick) <= window
        && (tickCount - lastFireEdgeTick) > 0) {
      penalty -= postFireScale * (yawNorm + pitchNorm);
    }

    return penalty;
  }
}
