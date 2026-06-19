package aiplay.rl.rewards.core;

import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.field.HalfFieldGeometry;
import aiplay.shared.objective.CarrierObjectiveResolver;
import aiplay.shared.objective.CounterGrabResolver;
import aiplay.shared.objective.EscortObjectiveResolver;

/**
 * Shared static helpers used by multiple {@link RewardComponent} implementations.
 */
public final class RewardUtils {

  private RewardUtils() {
  }

  // ---- Distance helpers ----

  public static double distance(CoordinatesDto a, CoordinatesDto b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    double dz = a.z - b.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  public static double distance2d(CoordinatesDto a, CoordinatesDto b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    return Math.sqrt(dx * dx + dy * dy);
  }

  // ---- Flag helpers ----

  /**
   * True when the flag is being carried by an enemy.
   * Checks both status and hasHolder for robustness.
   */
  public static boolean isFlagCarried(FlagDto flag) {
    return flag.status == FlagStatusDto.CARRIED || flag.hasHolder;
  }

  // ---- Enemy helpers ----

  /**
   * Find the closest living enemy by actual 2D distance.
   */
  public static PlayerDto findClosestEnemy(GameStateDto state) {
    if (state.playerPawn == null || state.playerPawn.location == null) {
      return null;
    }
    CoordinatesDto botLoc = state.playerPawn.location;
    PlayerDto closest = null;
    double closestDist = Double.MAX_VALUE;
    if (state.enemies != null) {
      for (PlayerDto enemy : state.enemies) {
        if (enemy != null && enemy.health > 0 && enemy.location != null) {
          double dist = distance2d(botLoc, enemy.location);
          if (dist < closestDist) {
            closestDist = dist;
            closest = enemy;
          }
        }
      }
    }
    if (closest != null) {
      return closest;
    }
    if (state.player1 != null && state.player1.health > 0
        && state.player1.location != null) {
      return state.player1;
    }
    return null;
  }

  /**
   * Find the closest visible enemy.
   */
  public static PlayerDto findClosestVisibleEnemy(GameStateDto state) {
    if (state.enemies != null) {
      for (PlayerDto enemy : state.enemies) {
        if (enemy != null && enemy.health > 0
            && enemy.enemyVisible && enemy.location != null) {
          return enemy;
        }
      }
    }
    if (state.player1 != null && state.player1.health > 0
        && state.player1.enemyVisible && state.player1.location != null) {
      return state.player1;
    }
    return null;
  }

  // ---- Map geometry ----

  /**
   * Returns true if the given location is on the enemy half of the field.
   */
  public static boolean isOnEnemyHalf(CoordinatesDto location, CoordinatesDto homeBase, CoordinatesDto enemyBase) {
    double axisX = enemyBase.x - homeBase.x;
    double axisY = enemyBase.y - homeBase.y;
    double midX = (homeBase.x + enemyBase.x) / 2.0;
    double midY = (homeBase.y + enemyBase.y) / 2.0;
    double dot = (location.x - midX) * axisX + (location.y - midY) * axisY;
    return dot > 0;
  }

  /**
   * True when the bot's current position is on the enemy half of the home→enemy axis.
   * Returns false when the team / flag bases cannot be resolved.
   */
  public static boolean botOnEnemyHalf(GameStateDto state) {
    PlayerDto pawn = state.playerPawn;
    if (pawn == null || pawn.location == null) return false;
    FlagDto ownFlag = (pawn.team == 0) ? state.redFlag : state.blueFlag;
    FlagDto enemyFlag = (pawn.team == 0) ? state.blueFlag : state.redFlag;
    if (ownFlag == null || ownFlag.baseLocation == null
        || enemyFlag == null || enemyFlag.baseLocation == null) {
      return false;
    }
    return isOnEnemyHalf(pawn.location, ownFlag.baseLocation, enemyFlag.baseLocation);
  }

  /**
   * Bot's own penetration into the enemy half along the home→enemy axis. 0.0 means the bot is
   * exactly at midfield or in its own half, 1.0 means at the enemy home base. Mirrors
   * {@link #enemyDepthInOwnHalf} but projects the bot's location toward enemy base instead of
   * enemy positions toward own base. Used by the endgame catchup bonus to grade how deeply a
   * Defend/Cover bot has pushed into attack territory. Returns 0.0 when bases cannot be resolved.
   */
  public static double botDepthInEnemyHalf(GameStateDto state) {
    PlayerDto pawn = state.playerPawn;
    if (pawn == null || pawn.location == null) return 0.0;
    FlagDto ownFlag = (pawn.team == 0) ? state.redFlag : state.blueFlag;
    FlagDto enemyFlag = (pawn.team == 0) ? state.blueFlag : state.redFlag;
    if (ownFlag == null || ownFlag.baseLocation == null
        || enemyFlag == null || enemyFlag.baseLocation == null) {
      return 0.0;
    }
    CoordinatesDto home = ownFlag.baseLocation;
    CoordinatesDto enemyBase = enemyFlag.baseLocation;
    double axisX = enemyBase.x - home.x;
    double axisY = enemyBase.y - home.y;
    double axisLenSq = axisX * axisX + axisY * axisY;
    if (axisLenSq < 1e-9) return 0.0;
    // t = 0 at home base, 1 at enemy base
    double t = ((pawn.location.x - home.x) * axisX + (pawn.location.y - home.y) * axisY) / axisLenSq;
    if (t <= 0.5) return 0.0;
    double depth = (t - 0.5) * 2.0;
    return Math.min(1.0, depth);
  }

  /**
   * Maximum penetration of any living enemy into the bot's own half along the home→enemy axis.
   * Delegates to {@link HalfFieldGeometry#enemyDepthInOwnHalf} — the java-model single source shared
   * with the carrier staging-zone gate ({@link CarrierObjectiveResolver}) so threat detection cannot
   * drift between the dense reward and the feature.
   */
  public static double enemyDepthInOwnHalf(GameStateDto state) {
    return HalfFieldGeometry.enemyDepthInOwnHalf(state);
  }

  /**
   * Returns the first living teammate currently carrying a flag, or {@code null} when no teammate
   * carries. Cover-role shaping uses this as escort target: when an attacker grabbed the enemy
   * flag, cover stays close until it is captured.
   */
  public static PlayerDto findTeammateCarrier(GameStateDto state) {
    if (state.teammates == null) return null;
    for (PlayerDto t : state.teammates) {
      if (t == null || t.location == null || t.health <= 0) continue;
      if (t.hasFlag) return t;
    }
    return null;
  }

  /**
   * 2D distance between red and blue flag base locations. Delegates to {@link
   * HalfFieldGeometry#interBaseDistance} (java-model single source, shared with the carrier
   * staging-zone radius).
   */
  public static double computeInterBaseDistance(GameStateDto state) {
    return HalfFieldGeometry.interBaseDistance(state);
  }

  // ---- Objective resolution ----

  /**
   * Movement objective priority (distance-only reward).
   *
   * <ol start="0">
   *   <li>Bot CARRIES the enemy flag → {@link CarrierObjectiveResolver#carrierObjective}: own flag
   *       base (preserve + stage capture), or a nearby DROPPED own flag when returning it is a cheap
   *       on-route detour (unblocks the capture immediately). A moving EFC is never chased.</li>
   *   <li>Own flag DROPPED on enemy half → return it (Defender stays home)</li>
   *   <li>Own flag DROPPED on home half → return it ONLY if bot is also on home half</li>
   *   <li>Own flag CARRIED by enemy → counter-grab split (CounterGrabResolver): the bot closest to
   *       the enemy flag grabs it (defensive grab → standoff blocks the enemy capture); the rest
   *       intercept the EFC at an equal-speed cut-off point toward the enemy base.</li>
   *   <li>Defender, idle (no flag in play) → patrol own base</li>
   *   <li>Bot does not have enemy flag → go to enemy flag location</li>
   * </ol>
   *
   * <p>Must stay synchronized with
   * {@link aiplay.scanners.feature.resolver.navigation.NavigationTargetFeatureValueResolver}
   * — feature input and dense progress reward both consume this objective.
   */
  public static CoordinatesDto resolveMovementPrimaryObjective(GameStateDto state) {
    PlayerDto pawn = state.playerPawn;
    if (pawn == null) {
      return null;
    }

    int team = pawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    FlagDto enemyFlag = (team == 0) ? state.blueFlag : state.redFlag;

    // Priority 0 (carrier-first, 2026-05-29 user-fix): de bot draagt de enemy-vlag → naar huis
    // (preserve de vlag + stage bij base voor instant-capture), nooit een bewegende EFC achterna.
    // Verfijning 2026-05-31: ligt onze EIGEN vlag DROPPED ongeveer op de route naar huis, dan haalt
    // de carrier hem zelf op (kleine detour) — dat unblocked de capture meteen i.p.v. bij base te
    // wachten tot een teammate hem returnt. CarrierObjectiveResolver houdt dit byte-for-byte gelijk
    // aan de navTarget-feature. Een CARRIED vlag (bewegende EFC) wordt nooit gechased (zie resolver).
    if (pawn.hasFlag) {
      return CarrierObjectiveResolver.carrierObjective(state);
    }

    // Priority 1 & 2: own flag dropped
    if (ownFlag != null && ownFlag.status == FlagStatusDto.DROPPED
        && ownFlag.location != null && ownFlag.baseLocation != null
        && enemyFlag != null && enemyFlag.baseLocation != null) {

      boolean flagOnEnemyHalf = isOnEnemyHalf(ownFlag.location, ownFlag.baseLocation, enemyFlag.baseLocation);
      if (flagOnEnemyHalf) {
        // Don't drag the Defender across midfield — let Attack/Cover chase the
        // dropped flag on enemy half. Defender stays anchored at own base.
        if (isDefendRole()) {
          return ownFlag.baseLocation;
        }
        return ownFlag.location;
      } else {
        if (pawn.location != null
            && !isOnEnemyHalf(pawn.location, ownFlag.baseLocation, enemyFlag.baseLocation)) {
          // Own flag dropped on home half — every role goes for the recovery
          // touch, including the Defender (this IS the Defender's job).
          return ownFlag.location;
        }
      }
    }

    // Priority 3: own flag CARRIED by enemy (NON-carriers only; the carrier is already routed home
    // by priority 0). Counter-grab split (2026-05-31): the bot closest to the enemy flag grabs it
    // (defensive grab → standoff); the rest intercept the EFC at an equal-speed cut-off point toward
    // the enemy base instead of tail-chasing its current position. Delegated to the shared
    // CounterGrabResolver so this stays byte-for-byte aligned with the navTarget feature.
    boolean ourFlagCarried = ownFlag != null && ownFlag.status == FlagStatusDto.CARRIED
        && ownFlag.location != null;
    if (ourFlagCarried) {
      return CounterGrabResolver.carriedFlagObjective(state);
    }

    // (carrier-home is nu priority 0 hierboven — een flag-carrier komt hier nooit.)

    // Priority 6: role-conditioned default — Defenders patrol around own base when no
    // flag is in play. Without this the navTarget feature points at the enemy flag for
    // every bot regardless of role, dragging Defend-role bots out of position toward
    // the enemy half. Cover stays neutral (escort logic handles teammate-carrier
    // proximity) so falling through to enemy flag is still correct for them.
    if (isDefendRole() && ownFlag != null && ownFlag.baseLocation != null) {
      return ownFlag.baseLocation;
    }

    if (enemyFlag != null && enemyFlag.location != null) {
      return enemyFlag.location;
    }
    return enemyFlag != null ? enemyFlag.baseLocation : null;
  }

  private static boolean isDefendRole() {
    try {
      return "Defend".equals(PlayerIdentityContext.effectiveRole());
    } catch (IllegalStateException ignore) {
      return false;
    }
  }

  /**
   * True when {@link #resolveMovementPrimaryObjective} falls through to priority 6 with a TEAMMATE
   * carrying the enemy flag — i.e. the movement objective IS the live carrier position. Derived
   * from the same building blocks the chain itself uses (no second copy of the priority logic):
   * not a carrier (priority 0), no own-flag recovery priority (1–3, {@link
   * #isOwnFlagReturnPriority} covers both DROPPED paths and CARRIED), not the Defend base anchor
   * (priority 5). Consumers apply the {@link EscortObjectiveResolver} standoff/funnel decisions
   * only in this exact case, so e.g. a Defender anchored on his own base never gets an escort
   * floor glued around his base point.
   */
  public static boolean isTeammateCarrierEscortObjective(GameStateDto state) {
    if (state == null || state.playerPawn == null) {
      return false;
    }
    return EscortObjectiveResolver.hasTeammateCarrier(state)
        && !isOwnFlagReturnPriority(state)
        && !isDefendRole();
  }

  /**
   * True when our flag is away from home base — DROPPED on the field (and worth recovering)
   * or CARRIED by an enemy. In both cases capture is blocked until our flag returns, so
   * defenders and supporters get a recovery-shaped progress reward.
   */
  public static boolean isOwnFlagReturnPriority(GameStateDto state) {
    PlayerDto pawn = state.playerPawn;
    if (pawn == null) {
      return false;
    }
    int team = pawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    FlagDto enemyFlag = (team == 0) ? state.blueFlag : state.redFlag;
    if (ownFlag == null || ownFlag.location == null
        || ownFlag.baseLocation == null
        || enemyFlag == null || enemyFlag.baseLocation == null) {
      return false;
    }
    if (ownFlag.status == FlagStatusDto.CARRIED || ownFlag.hasHolder) {
      return true;
    }
    if (ownFlag.status != FlagStatusDto.DROPPED) {
      return false;
    }
    boolean flagOnEnemyHalf = isOnEnemyHalf(ownFlag.location, ownFlag.baseLocation, enemyFlag.baseLocation);
    if (flagOnEnemyHalf) {
      return true;
    }
    return pawn.location != null
        && !isOnEnemyHalf(pawn.location, ownFlag.baseLocation, enemyFlag.baseLocation);
  }

  // ---- View helpers ----

  /**
   * Time-based multiplier: 1.0 at match start, 0.1 floor at end.
   * Returns 1.0 when TimeLimit=0 (no countdown).
   */
  public static double timeMultiplier(GameStateDto state, RewardTuningConfig config) {
    if (state.mapInfo == null) {
      return 1.0;
    }
    if (state.mapInfo.timeLimit <= 0) {
      return 1.0;
    }
    double remaining = state.mapInfo.remainingTime;
    double matchDuration = config.getMatchDuration();
    if (matchDuration <= 0 || !Double.isFinite(remaining)) {
      return 1.0;
    }
    double ratio = remaining / matchDuration;
    return Math.max(0.1, Math.min(1.0, ratio));
  }

  /**
   * Compute 2D yaw dot product between a frame's view direction and direction to target.
   */
  public static double computeYawDot(GameStateDto frame, CoordinatesDto target) {
    double toDirX = target.x - frame.playerPawn.location.x;
    double toDirY = target.y - frame.playerPawn.location.y;
    double toDist = Math.sqrt(toDirX * toDirX + toDirY * toDirY);
    if (toDist < 1.0) {
      return 1.0;
    }
    toDirX /= toDist;
    toDirY /= toDist;

    double yawRad = (frame.playerPawn.viewRotation.x & 0xFFFF) * (2.0 * Math.PI / 65536.0);
    double viewDirX = Math.cos(yawRad);
    double viewDirY = Math.sin(yawRad);

    return viewDirX * toDirX + viewDirY * toDirY;
  }

  /**
   * Strict 3D aim dot: cosine between the full view direction (yaw + pitch) and
   * the vector from player location to a target (including height). Ranges in
   * [-1, 1]; 1.0 = perfect aim. Caller clamps to [0, 1] when using as an aim score.
   *
   * <p>Delegates to {@link aiplay.shared.view.ViewTargeting#computeAimDot3D} so
   * reward utility, BC label code, and feature resolvers share one definition.</p>
   */
  public static double computeAimDot3D(GameStateDto frame, CoordinatesDto target) {
    return aiplay.shared.view.ViewTargeting.computeAimDot3D(
        frame.playerPawn.location.x, frame.playerPawn.location.y, frame.playerPawn.location.z,
        frame.playerPawn.viewRotation.x, frame.playerPawn.viewRotation.y,
        target.x, target.y, target.z);
  }

  /**
   * True when the annotated attention target is an enemy.
   */
  public static boolean isEnemyAttentionTarget(GameStateDto frame) {
    if (frame.annotatedAttentionTarget == null) {
      return false;
    }
    return switch (frame.annotatedAttentionTarget) {
      case ENEMY_PLAYER, ENEMY_CARRIER, ENEMY_NEAREST_TO_HOME_FLAG, ENEMY_NEAREST_TO_ATTACKER,
           ENEMY_THREAT_TO_SELF -> true;
      default -> false;
    };
  }
}
