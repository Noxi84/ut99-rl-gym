package aiplay.mission;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.objective.CounterGrabResolver;

/**
 * Immutable snapshot of derived world facts from a single game state frame.
 *
 * WorldFacts is the bridge between raw GameStateDto and policy decisions.
 * Policies operate on facts, not raw DTOs — this decouples decision logic
 * from DTO structure and makes the input contract explicit.
 */
public record WorldFacts(
        boolean hasFlag,
        boolean isStuck,
        // Enemy 0 (closest)
        boolean enemyPresent,
        boolean enemyVisible,
        double enemyDistanceNorm,
        boolean enemyFacingUs,
        boolean enemyFiring,
        // Flag carrier detection
        boolean enemyTeamHasOurFlag,
        boolean ownTeamHasEnemyFlag,
        boolean carrierIsPlayer1,
        // True when this bot is the designated counter-grabber: enemy carries our flag, we don't
        // hold theirs yet, and this bot is closest to the enemy flag. Drives the defensive-grab
        // mission split (CAPTURE_FLAG instead of INTERCEPT_CARRIER).
        boolean isCounterGrabber,
        // Carrier shadow facts — derived from flag carrier position on the longitudinal axis
        double carrierProgressNorm,
        long frameTimestampMs
) {

    /** Enemy distance threshold for "nearby" — roughly within engagement range. */
    private static final double NEARBY_THRESHOLD = 0.25;

    /** Closest enemy is present and within engagement range (regardless of visibility). */
    public boolean enemyNearby() {
        return enemyPresent && enemyDistanceNorm < NEARBY_THRESHOLD;
    }

    /**
     * Derive WorldFacts from a GameStateDto frame plus externally computed stuck state.
     *
     * @param state the raw game state frame
     * @param isStuck whether StuckDetector has flagged the bot as stuck
     * @param frameTimestampMs frame timestamp (not wall-clock)
     * @return derived facts, or null if state is invalid
     */
    public static WorldFacts derive(GameStateDto state, boolean isStuck, long frameTimestampMs) {
        if (state == null || state.playerPawn == null) {
            return null;
        }

        // Enemy 0 context (closest enemy = player1)
        boolean enemyPresent = false;
        boolean enemyVisible = false;
        boolean enemyHasFlag = false;
        double enemyDistanceNorm = 1.0;
        boolean enemyFacingUs = false;
        boolean enemyFiring = false;
        PlayerDto enemy = state.player1;
        if (enemy != null && enemy.health > 0) {
            enemyPresent = true;
            enemyVisible = enemy.enemyVisible;
            enemyHasFlag = enemy.hasFlag;
            enemyDistanceNorm = computeEnemyDistanceNorm(state.playerPawn, enemy);
            enemyFacingUs = computeEnemyFacingUs(state.playerPawn, enemy);
            enemyFiring = enemy.bFire != null && enemy.bFire.value_norm > 0.5f;
        }

        // Flag-based carrier detection
        boolean enemyTeamHasOurFlag = isOwnFlagCarried(state);
        boolean ownTeamHasEnemyFlag = isEnemyFlagCarried(state);
        boolean carrierIsPlayer1 = enemyHasFlag;
        boolean isCounterGrabber = CounterGrabResolver.isDesignatedGrabber(state);

        // Carrier shadow facts
        double carrierProgressNorm = -1.0;
        AxisContext axis = computeAxis(state);
        if (axis != null) {
            carrierProgressNorm = computeCarrierProgress(axis, state);
        }

        return new WorldFacts(
                state.playerPawn.hasFlag,
                isStuck,
                enemyPresent,
                enemyVisible,
                enemyDistanceNorm,
                enemyFacingUs,
                enemyFiring,
                enemyTeamHasOurFlag,
                ownTeamHasEnemyFlag,
                carrierIsPlayer1,
                isCounterGrabber,
                carrierProgressNorm,
                frameTimestampMs
        );
    }

    /**
     * Check if our own flag is being carried by the enemy team, using flag DTO state.
     * Independent of player1 — detects carrier even if they aren't the closest enemy.
     */
    private static boolean isOwnFlagCarried(GameStateDto state) {
        int botTeam = state.playerPawn.team;
        FlagDto ownFlag = (botTeam == 1) ? state.blueFlag : state.redFlag;
        if (ownFlag == null) return false;
        if (ownFlag.status == FlagStatusDto.CARRIED) return true;
        if (ownFlag.hasHolder) return true;
        return false;
    }

    /**
     * Check if the enemy flag is being carried by our team, using flag DTO state.
     * In CTF the enemy flag can only be carried by our team, so this implies a
     * teammate (or the bot itself) holds it. Independent of which teammate is closest.
     */
    private static boolean isEnemyFlagCarried(GameStateDto state) {
        int botTeam = state.playerPawn.team;
        FlagDto enemyFlag = (botTeam == 1) ? state.redFlag : state.blueFlag;
        if (enemyFlag == null) return false;
        if (enemyFlag.status == FlagStatusDto.CARRIED) return true;
        if (enemyFlag.hasHolder) return true;
        return false;
    }

    /**
     * Compute normalized 2D distance between self and enemy.
     * Uses normalized locations if available, falls back to 1.0.
     */
    private static double computeEnemyDistanceNorm(PlayerDto self, PlayerDto enemy) {
        if (self.location == null || enemy.location == null) {
            return 1.0;
        }
        double dx = self.location.x_norm - enemy.location.x_norm;
        double dy = self.location.y_norm - enemy.location.y_norm;
        double dist = Math.sqrt(dx * dx + dy * dy);
        return Math.min(dist, 1.0);
    }

  // ---- Axis context for longitudinal projection ----

  /**
   * Reusable axis context for projecting positions onto the home→enemy axis.
   */
  private record AxisContext(
      double axisUnitX, double axisUnitY, double axisLen,
      CoordinatesDto homeBase
  ) {

    /**
     * Project a world point onto the longitudinal axis. Returns [0..1]: 0=home, 1=enemy.
     */
    double projectLongitudinal(CoordinatesDto point) {
      double relX = point.x_norm - homeBase.x_norm;
      double relY = point.y_norm - homeBase.y_norm;
      double raw = (relX * axisUnitX + relY * axisUnitY) / axisLen;
      return Math.max(0.0, Math.min(1.0, raw));
    }
  }

    /**
     * Compute the home→enemy axis from flag base locations.
     * Returns null if base locations are unavailable.
     */
    private static AxisContext computeAxis(GameStateDto state) {
        int botTeam = state.playerPawn.team;
        FlagDto ownFlag = (botTeam == 1) ? state.blueFlag : state.redFlag;
        FlagDto enemyFlag = (botTeam == 1) ? state.redFlag : state.blueFlag;

        if (ownFlag == null || ownFlag.baseLocation == null
                || enemyFlag == null || enemyFlag.baseLocation == null
                || state.playerPawn.location == null) {
            return null;
        }

        CoordinatesDto homeBase = ownFlag.baseLocation;
        CoordinatesDto enemyBase = enemyFlag.baseLocation;

        double axisX = enemyBase.x_norm - homeBase.x_norm;
        double axisY = enemyBase.y_norm - homeBase.y_norm;
        double axisLen = Math.sqrt(axisX * axisX + axisY * axisY);

        if (axisLen < 1e-9) {
            return null;
        }

      return new AxisContext(axisX / axisLen, axisY / axisLen, axisLen, homeBase);
    }

  // ---- Carrier shadow computation ----

  /**
   * Project our flag's carrier position onto the home→enemy axis when carried.
   * <p>
   * When our flag status is CARRIED (or hasHolder is true), the flag's location IS
   * the carrier's actual position — most reliable source, no name-matching needed.
   *
   * @return carrier progress [0..1] or -1.0 when our flag is not carried.
   */
  private static double computeCarrierProgress(AxisContext axis, GameStateDto state) {
    int botTeam = state.playerPawn.team;
    FlagDto ownFlag = (botTeam == 1) ? state.blueFlag : state.redFlag;
    if (ownFlag == null) return -1.0;

    boolean flagCarried = ownFlag.status == FlagStatusDto.CARRIED || ownFlag.hasHolder;
    if (!flagCarried || ownFlag.location == null) return -1.0;

    return axis.projectLongitudinal(ownFlag.location);
  }

  /** Dot product threshold: ~60° cone (cos(60°) = 0.5). */
    private static final double FACING_DOT_THRESHOLD = 0.5;

    /**
     * Check if the enemy's view direction points toward our bot (within ~60° cone).
     * Uses raw UT rotation units on the enemy's viewRotation.
     */
    private static boolean computeEnemyFacingUs(PlayerDto self, PlayerDto enemy) {
        if (self.location == null || enemy.location == null || enemy.viewRotation == null) {
            return false;
        }
        // Enemy view direction from yaw
        double yawRad = (enemy.viewRotation.x & 0xFFFF) * (2.0 * Math.PI / 65536.0);
        double viewDirX = Math.cos(yawRad);
        double viewDirY = Math.sin(yawRad);

        // Direction from enemy to us (using raw coordinates)
        double dx = self.location.x - enemy.location.x;
        double dy = self.location.y - enemy.location.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1.0) return true; // on top of each other

        double dot = (viewDirX * dx + viewDirY * dy) / dist;
        return dot > FACING_DOT_THRESHOLD;
    }
}
