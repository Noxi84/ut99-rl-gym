package aiplay.tactical;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.shared.tactical.TacticalConstraintMode;
import aiplay.shared.tactical.TacticalIntent;

/**
 * Applies tactical spatial constraints to movement by removing the homeward
 * longitudinal component when a constraint is active.
 *
 * Supports two constraint modes:
 * - BLOCK_REENTRY_TO_HOME_HALF: midfield fallback (carrier line at 0.5)
 * - BLOCK_REENTRY_PAST_CARRIER_LINE: dynamic carrier shadow
 *
 * Both modes use the same longitudinal check: the no-pass boundary comes from
 * TacticalIntent.carrierLineProgressNorm. When the bot is within
 * carrierLineMarginNorm of the boundary, the homeward component is blocked.
 * When the bot has buffer (further enemy-ward), movement is unconstrained.
 *
 * Called by CommandController after locomotion gating, before command emission.
 */
public final class MovementConstraintApplier {

    /** Result of applying the constraint. */
    public record ConstrainedMovement(
            boolean forward, boolean back, boolean left, boolean right,
            int dodgeDir, boolean wasClamped) {}

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double HALF_PI = Math.PI / 2.0;

  private final double carrierLineMarginNorm;

  public MovementConstraintApplier(double carrierLineMarginNorm) {
    this.carrierLineMarginNorm = carrierLineMarginNorm;
  }

    /**
     * Apply the tactical constraint to movement.
     *
     * @param forward   decoded forward boolean (relative to view)
     * @param back      decoded back boolean (relative to view)
     * @param left      decoded left boolean (relative to view)
     * @param right     decoded right boolean (relative to view)
     * @param dodgeDir  dodge direction (0=none, 1=fwd, 2=back, 3=left, 4=right)
     * @param viewYaw   current view yaw in UT units (0-65535)
     * @param tactical  current tactical intent (nullable)
     * @param state     current game state for base locations
     * @return constrained movement, possibly with some booleans cleared
     */
    public ConstrainedMovement apply(boolean forward, boolean back, boolean left, boolean right,
                                     int dodgeDir, int viewYaw,
                                     TacticalIntent tactical, GameStateDto state) {
        if (tactical == null || tactical.constraintMode == TacticalConstraintMode.UNCONSTRAINED) {
            return new ConstrainedMovement(forward, back, left, right, dodgeDir, false);
        }

      if (tactical.constraintMode != TacticalConstraintMode.BLOCK_REENTRY_TO_HOME_HALF
          && tactical.constraintMode != TacticalConstraintMode.BLOCK_REENTRY_PAST_CARRIER_LINE) {
            return new ConstrainedMovement(forward, back, left, right, dodgeDir, false);
        }

        // Need base locations to compute home→enemy axis
        double homewardAngleRad = computeHomewardAngleRad(state);
        if (Double.isNaN(homewardAngleRad)) {
            return new ConstrainedMovement(forward, back, left, right, dodgeDir, false);
        }

      // Check if bot has buffer beyond the boundary — if so, no constraint needed
      double boundaryProgress = tactical.carrierLineProgressNorm;
      if (boundaryProgress >= 0) {
        double botProgress = computeBotLongitudinalProgress(state);
        if (!Double.isNaN(botProgress) && botProgress > boundaryProgress + carrierLineMarginNorm) {
          // Bot is well enemy-ward of the boundary — no constraint
          return new ConstrainedMovement(forward, back, left, right, dodgeDir, false);
        }
      }

      // Bot is at or near the boundary — block homeward component
        boolean idle = !forward && !back && !left && !right;
        if (idle && dodgeDir == 0) {
            return new ConstrainedMovement(false, false, false, false, 0, false);
        }

        double viewYawRad = utYawToRad(viewYaw);
        boolean clamped = false;

        // --- Constrain locomotion ---
        if (!idle) {
            int moveOffset = computeMoveOffset(forward, back, left, right);
            double moveAngleRad = normalizeAngle(viewYawRad + utOffsetToRad(moveOffset));

            if (isHomeward(moveAngleRad, homewardAngleRad)) {
                // Clamp to closest tangential direction, then convert back to booleans
                double tangentialRad = clampToTangential(moveAngleRad, homewardAngleRad);
                int clampedRelOffset = closestRelativeOffset(tangentialRad, viewYawRad);

                forward = isForwardFromOffset(clampedRelOffset);
                back = isBackFromOffset(clampedRelOffset);
                left = isLeftFromOffset(clampedRelOffset);
                right = isRightFromOffset(clampedRelOffset);
                clamped = true;

                // If clamped result maps to idle (no good tangential direction), stop
                if (!forward && !back && !left && !right) {
                    clamped = true;
                }
            }
        }

        // --- Constrain dodge ---
        if (dodgeDir != 0) {
            double dodgeAngleRad = dodgeDirToWorldAngle(dodgeDir, viewYawRad);
            if (isHomeward(dodgeAngleRad, homewardAngleRad)) {
                dodgeDir = 0;
                clamped = true;
            }
        }

        return new ConstrainedMovement(forward, back, left, right, dodgeDir, clamped);
    }

    /** Compute the world-space angle from bot toward own base (homeward direction). */
    private static double computeHomewardAngleRad(GameStateDto state) {
        if (state == null || state.playerPawn == null || state.playerPawn.location == null) {
            return Double.NaN;
        }
        int botTeam = state.playerPawn.team;
        FlagDto ownFlag = (botTeam == 1) ? state.blueFlag : state.redFlag;
        if (ownFlag == null || ownFlag.baseLocation == null) {
            return Double.NaN;
        }
        CoordinatesDto botLoc = state.playerPawn.location;
        CoordinatesDto homeBase = ownFlag.baseLocation;

        double dx = homeBase.x_norm - botLoc.x_norm;
        double dy = homeBase.y_norm - botLoc.y_norm;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
            return Double.NaN;
        }
        return Math.atan2(dy, dx);
    }

  /**
   * Compute the bot's longitudinal progress on the home→enemy axis. Returns [0..1]: 0=home, 1=enemy. NaN if unavailable.
   */
  private static double computeBotLongitudinalProgress(GameStateDto state) {
    if (state == null || state.playerPawn == null || state.playerPawn.location == null) {
      return Double.NaN;
    }
    int botTeam = state.playerPawn.team;
    FlagDto ownFlag = (botTeam == 1) ? state.blueFlag : state.redFlag;
    FlagDto enemyFlag = (botTeam == 1) ? state.redFlag : state.blueFlag;

    if (ownFlag == null || ownFlag.baseLocation == null
        || enemyFlag == null || enemyFlag.baseLocation == null) {
      return Double.NaN;
    }

    CoordinatesDto homeBase = ownFlag.baseLocation;
    CoordinatesDto enemyBase = enemyFlag.baseLocation;
    CoordinatesDto botLoc = state.playerPawn.location;

    double axisX = enemyBase.x_norm - homeBase.x_norm;
    double axisY = enemyBase.y_norm - homeBase.y_norm;
    double axisLen = Math.sqrt(axisX * axisX + axisY * axisY);
    if (axisLen < 1e-9) {
      return Double.NaN;
    }

    double axisUnitX = axisX / axisLen;
    double axisUnitY = axisY / axisLen;
    double relX = botLoc.x_norm - homeBase.x_norm;
    double relY = botLoc.y_norm - homeBase.y_norm;
    double raw = (relX * axisUnitX + relY * axisUnitY) / axisLen;
    return Math.max(0.0, Math.min(1.0, raw));
  }

    /** Check if a movement angle has a homeward component (within ±90° of homeward). */
    private static boolean isHomeward(double moveAngleRad, double homewardAngleRad) {
        double diff = normalizeAngle(moveAngleRad - homewardAngleRad);
        return Math.abs(diff) < HALF_PI;
    }

    /** Clamp a homeward angle to the closest tangential direction (±90° from homeward). */
    private static double clampToTangential(double moveAngleRad, double homewardAngleRad) {
        double diff = normalizeAngle(moveAngleRad - homewardAngleRad);
        // diff is in (-π/2, π/2) since we already checked isHomeward
        if (diff >= 0) {
            return normalizeAngle(homewardAngleRad + HALF_PI);
        } else {
            return normalizeAngle(homewardAngleRad - HALF_PI);
        }
    }

    /**
     * Find the closest of the 8 relative movement offsets (in UT units) to a
     * target world-space angle, given the current view yaw.
     * Returns the offset in UT units, or Integer.MAX_VALUE for idle.
     */
    private static int closestRelativeOffset(double targetWorldRad, double viewYawRad) {
        double relRad = normalizeAngle(targetWorldRad - viewYawRad);
        double relDeg = Math.toDegrees(relRad);

        // Map to the closest of the 8 discrete directions
        // 0°=forward, ±45°=fwd-left/right, ±90°=strafe, ±135°=back-left/right, 180°=back
        if (relDeg >= -22.5 && relDeg < 22.5)   return 0;       // forward
        if (relDeg >= 22.5 && relDeg < 67.5)    return 8192;    // forward-right
        if (relDeg >= 67.5 && relDeg < 112.5)   return 16384;   // strafe right
        if (relDeg >= 112.5 && relDeg < 157.5)  return 24576;   // back-right
        if (relDeg >= -67.5 && relDeg < -22.5)  return -8192;   // forward-left
        if (relDeg >= -112.5 && relDeg < -67.5) return -16384;  // strafe left
        if (relDeg >= -157.5 && relDeg < -112.5) return -24576; // back-left
        return 32768; // back
    }

    // --- UT angle conversions ---

    private static double utYawToRad(int utYaw) {
        return (utYaw & 0xFFFF) * TWO_PI / 65536.0;
    }

    private static double utOffsetToRad(int utOffset) {
        return utOffset * TWO_PI / 65536.0;
    }

    /** Normalize angle to [-π, π). */
    private static double normalizeAngle(double rad) {
        rad = rad % TWO_PI;
        if (rad > Math.PI) rad -= TWO_PI;
        if (rad <= -Math.PI) rad += TWO_PI;
        return rad;
    }

    // --- Movement offset computation (mirrors CommandController.computeMoveYaw) ---

    private static int computeMoveOffset(boolean forward, boolean back, boolean left, boolean right) {
        if (forward && !back) {
            if (left && !right)       return -8192;   // forward-left = -45°
            else if (right && !left)  return 8192;    // forward-right = +45°
            return 0;                                  // pure forward
        } else if (back && !forward) {
            if (left && !right)       return -24576;  // back-left = -135°
            else if (right && !left)  return 24576;   // back-right = +135°
            return 32768;                              // pure back = 180°
        } else if (left && !right) {
            return -16384;  // pure strafe left = -90°
        } else if (right && !left) {
            return 16384;   // pure strafe right = +90°
        }
        return 0;
    }

    private static double dodgeDirToWorldAngle(int dodgeDir, double viewYawRad) {
        return switch (dodgeDir) {
            case 1 -> viewYawRad;                                          // forward
            case 2 -> normalizeAngle(viewYawRad + Math.PI);               // back
            case 3 -> normalizeAngle(viewYawRad - HALF_PI);               // left
            case 4 -> normalizeAngle(viewYawRad + HALF_PI);               // right
            default -> viewYawRad;
        };
    }

    // --- Offset → boolean decoding ---

    private static boolean isForwardFromOffset(int offset) {
        // forward component: offsets 0, ±8192
        int abs = Math.abs(((offset + 32768) & 0xFFFF) - 32768);
        return abs < 16384; // within ±90° of forward
    }

    private static boolean isBackFromOffset(int offset) {
        int norm = ((offset + 32768) & 0xFFFF) - 32768;
        int abs = Math.abs(norm);
        return abs > 16384; // beyond ±90° of forward
    }

    private static boolean isLeftFromOffset(int offset) {
        int norm = ((offset + 32768) & 0xFFFF) - 32768;
        return norm < -4096 && norm > -28672; // left quadrant
    }

    private static boolean isRightFromOffset(int offset) {
        int norm = ((offset + 32768) & 0xFFFF) - 32768;
        return norm > 4096 && norm < 28672; // right quadrant
    }
}
