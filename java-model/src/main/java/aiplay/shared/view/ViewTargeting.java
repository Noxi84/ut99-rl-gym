package aiplay.shared.view;

import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.util.NormalizationUtils;

/**
 * Shared heading/pitch target resolution for view-related systems.
 *
 * <p>Keeps train-time labels, SAC rewards, and runtime-only diagnostic features
 * on the same target semantics.</p>
 */
public final class ViewTargeting {

  private static final double TAU = 2.0 * Math.PI;
  private static final int MAX_PITCH_UP = 18000;
  private static final int MAX_PITCH_DOWN = -16384;
  /** Dot threshold for "facing": enemy yaw within ~60° of the direction to the point (cos 60° = 0.5). */
  private static final double ENEMY_FACING_DOT = 0.5;

  private ViewTargeting() {
  }

  public static CoordinatesDto resolveHeadingTarget(GameStateDto frame) {
    if (frame == null || frame.playerPawn == null) {
      return null;
    }
    int playerTeam = frame.playerPawn.team;
    FlagDto ownFlag = (playerTeam == 1) ? frame.blueFlag : frame.redFlag;
    FlagDto enemyFlag = (playerTeam == 1) ? frame.redFlag : frame.blueFlag;

    CoordinatesDto attentionTarget = resolveAttentionTarget(frame, ownFlag, enemyFlag);
    if (attentionTarget != null) {
      return attentionTarget;
    }
    CoordinatesDto enemySpawnTarget = resolveEnemySpawnTarget(frame);
    if (enemySpawnTarget != null) {
      return enemySpawnTarget;
    }
    return resolveObjectiveTarget(frame, ownFlag, enemyFlag);
  }

  public static CoordinatesDto resolveAttentionTarget(GameStateDto frame, FlagDto ownFlag, FlagDto enemyFlag) {
    if (frame == null
        || frame.annotatedEngagement == null
        || frame.annotatedAttentionTarget == null) {
      return null;
    }
    return switch (frame.annotatedAttentionTarget) {
      case OBJECTIVE_ENEMY_FLAG -> resolveEnemyFlagTarget(frame, enemyFlag);
      case OBJECTIVE_HOME_BASE -> (ownFlag != null) ? ownFlag.baseLocation : null;
      case OBJECTIVE_HOME_FLAG -> (ownFlag != null) ? ownFlag.location : null;
      case ENEMY_PLAYER -> resolveEnemyPlayerTarget(frame);
      case ENEMY_CARRIER -> resolveEnemyCarrierTarget(frame);
      case ENEMY_NEAREST_TO_HOME_FLAG -> resolveEnemyNearestToHomeFlagTarget(frame, ownFlag);
      case ENEMY_NEAREST_TO_ATTACKER -> resolveEnemyNearestToAttackerTarget(frame);
      // The threat-to-self enemy is resolved by AimTargetSelector into annotatedAimEnemy (same
      // source as ENEMY_PLAYER), so reuse that read — keeps view/reward/feature on one target.
      case ENEMY_THREAT_TO_SELF -> resolveEnemyPlayerTarget(frame);
      case NONE -> null;
    };
  }

  /**
   * True when the enemy is roughly facing the world point {@code (targetX, targetY)} — its yaw
   * points within ~60° of the direction to that point. 2D only (yaw; pitch ignored). Shared by
   * threat/attention selection so "is this enemy aiming at us" is computed one consistent way.
   * Returns false when the enemy has no location/viewRotation.
   */
  public static boolean isEnemyFacingPoint(PlayerDto enemy, double targetX, double targetY) {
    if (enemy == null || enemy.location == null || enemy.viewRotation == null) {
      return false;
    }
    double dx = targetX - enemy.location.x;
    double dy = targetY - enemy.location.y;
    double dist = Math.hypot(dx, dy);
    if (dist < 1.0) {
      return true; // essentially co-located → treat as facing
    }
    double yawRad = NormalizationUtils.viewRotationXToRad(enemy.viewRotation.x);
    double dot = (Math.cos(yawRad) * dx + Math.sin(yawRad) * dy) / dist;
    return dot > ENEMY_FACING_DOT;
  }

  public static CoordinatesDto resolveObjectiveTarget(GameStateDto frame, FlagDto ownFlag, FlagDto enemyFlag) {
    if (frame == null || frame.playerPawn == null) {
      return null;
    }
    if (frame.annotatedMission != null) {
      return switch (frame.annotatedMission) {
        case RETURN_HOME -> {
          if (ownFlag != null && ownFlag.baseLocation != null) {
            yield ownFlag.baseLocation;
          }
          yield (ownFlag != null) ? ownFlag.location : null;
        }
        case STUCK_RECOVER -> (ownFlag != null) ? ownFlag.location : null;
        case INTERCEPT_CARRIER -> {
          CoordinatesDto enemyCarrier = resolveEnemyCarrierTarget(frame);
          if (enemyCarrier != null) {
            yield enemyCarrier;
          }
          CoordinatesDto enemyPlayer = resolveEnemyPlayerTarget(frame);
          if (enemyPlayer != null) {
            yield enemyPlayer;
          }
          yield resolveEnemyFlagTarget(frame, enemyFlag);
        }
        default -> resolveEnemyFlagTarget(frame, enemyFlag);
      };
    }
    if (frame.playerPawn.hasFlag) {
      if (ownFlag != null && ownFlag.baseLocation != null) {
        return ownFlag.baseLocation;
      }
      return (ownFlag != null) ? ownFlag.location : null;
    }
    return resolveEnemyFlagTarget(frame, enemyFlag);
  }

  public static CoordinatesDto resolveEnemyPlayerTarget(GameStateDto frame) {
    if (frame == null) {
      return null;
    }
    // Sticky aim-target van AimTargetSelector (hysterese) — stabiel bij 2+ enemies.
    // Fallback naar slot 0 wanneer enricher (nog) niet gedraaid heeft of het frame
    // een pre-annotated snapshot is zonder aim-enricher.
    PlayerDto enemy = (frame.annotatedAimEnemy != null) ? frame.annotatedAimEnemy : frame.player1;
    return eyeTarget(enemy);
  }

  public static CoordinatesDto resolveEnemyCarrierTarget(GameStateDto frame) {
    if (frame == null || frame.enemies == null) {
      return null;
    }
    for (PlayerDto enemy : frame.enemies) {
      if (enemy != null && enemy.hasFlag && enemy.health > 0 && enemy.location != null) {
        return eyeTarget(enemy);
      }
    }
    return null;
  }

  /**
   * Living enemy with smallest 2D distance to our team's Attack-role teammate. Used by
   * Cover-role attention so the bot tracks the threat that the attacker is most likely about
   * to engage. Returns null when no living Attack-teammate or no living enemies are available.
   */
  public static CoordinatesDto resolveEnemyNearestToAttackerTarget(GameStateDto frame) {
    if (frame == null || frame.playerPawn == null
        || frame.teammates == null || frame.enemies == null) {
      return null;
    }
    int myTeam = frame.playerPawn.team;
    PlayerDto attacker = findLivingTeammateByRole(frame, myTeam, "Attack");
    if (attacker == null || attacker.location == null) {
      return null;
    }
    PlayerDto best = null;
    double bestDistSq = Double.POSITIVE_INFINITY;
    for (PlayerDto enemy : frame.enemies) {
      if (enemy == null || enemy.health <= 0 || enemy.location == null) {
        continue;
      }
      double dx = enemy.location.x - attacker.location.x;
      double dy = enemy.location.y - attacker.location.y;
      double distSq = dx * dx + dy * dy;
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = enemy;
      }
    }
    return eyeTarget(best);
  }

  /**
   * Find a living teammate on the given team whose configured role matches {@code role}.
   * Role lookup goes through {@link GlobalConfigRepository} (the source of truth for
   * gameplay.json), since {@code PlayerDto} doesn't carry role information at runtime.
   * Returns the first match, or null if none.
   */
  public static PlayerDto findLivingTeammateByRole(GameStateDto frame, int team, String role) {
    if (frame == null || frame.teammates == null || role == null) {
      return null;
    }
    for (PlayerDto t : frame.teammates) {
      if (t == null || t.health <= 0 || t.location == null || t.name == null) {
        continue;
      }
      BotConfig cfg = lookupBotConfig(t.name);
      if (cfg != null && cfg.team() == team && role.equals(cfg.role())) {
        return t;
      }
    }
    return null;
  }

  private static BotConfig lookupBotConfig(String name) {
    if (name == null) return null;
    return GlobalConfigRepository.shared().bots().stream()
        .filter(b -> name.equals(b.name()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Living enemy with smallest 2D distance to our home flag location. Used by defender-role
   * attention so the bot pre-aims at the most likely incoming attacker before a flag steal
   * happens. Returns null when no living enemy or no home flag location is available.
   */
  public static CoordinatesDto resolveEnemyNearestToHomeFlagTarget(GameStateDto frame, FlagDto ownFlag) {
    if (frame == null || frame.enemies == null || ownFlag == null || ownFlag.location == null) {
      return null;
    }
    PlayerDto best = null;
    double bestDistSq = Double.POSITIVE_INFINITY;
    for (PlayerDto enemy : frame.enemies) {
      if (enemy == null || enemy.health <= 0 || enemy.location == null) {
        continue;
      }
      double dx = enemy.location.x - ownFlag.location.x;
      double dy = enemy.location.y - ownFlag.location.y;
      double distSq = dx * dx + dy * dy;
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = enemy;
      }
    }
    return eyeTarget(best);
  }

  public static CoordinatesDto resolveEnemyFlagTarget(GameStateDto frame, FlagDto enemyFlag) {
    if (enemyFlag == null) {
      return null;
    }
    boolean carryingEnemyFlag = frame != null
        && frame.playerPawn != null
        && frame.playerPawn.hasFlag
        && enemyFlag.hasHolder;
    return carryingEnemyFlag ? null : enemyFlag.location;
  }

  private static CoordinatesDto resolveEnemySpawnTarget(GameStateDto frame) {
    if (!EnemySpawnTargeting.hasAllEnemiesDead(frame)) {
      return null;
    }
    if (frame.annotatedEnemySpawnTarget != null) {
      return frame.annotatedEnemySpawnTarget;
    }
    return EnemySpawnTargeting.resolveAimPoint(frame);
  }

  public static double computeHeadingTargetPitchNorm(GameStateDto frame) {
    CoordinatesDto target = resolveHeadingTarget(frame);
    if (target == null) {
      return 0.0;
    }
    return computePitchNormToward(frame, target);
  }

  public static double computeHeadingTargetPitchErrorNorm(GameStateDto frame) {
    double desired = computeHeadingTargetPitchNorm(frame);
    double current = extractCurrentPitchNorm(frame);
    return NormalizationUtils.clampM11(desired - current);
  }

  /**
   * Absolute yaw naar het heading-target, in [0, 1) (zelfde modulo-conventie als
   * {@link NormalizationUtils#normalizeViewRotationX}). Wordt momenteel niet als
   * input feature gebruikt — yaw is map-asymmetrisch — maar geregistreerd voor
   * symmetrie met {@link #computeHeadingTargetPitchNorm}.
   */
  public static double computeHeadingTargetYawNorm(GameStateDto frame) {
    CoordinatesDto target = resolveHeadingTarget(frame);
    if (target == null) {
      return 0.0;
    }
    return computeYawNormToward(frame, target);
  }

  /**
   * Signed shortest-arc verschil tussen target-yaw en huidige view-yaw,
   * genormaliseerd op π → [-1, +1]. Tegenhanger van
   * {@link #computeHeadingTargetPitchErrorNorm}: laat shooting/VR direct zien
   * hoe ver de aim nog van het commit-target afligt zonder dat het model dat
   * uit per-slot bearings moet reconstrueren.
   */
  public static double computeHeadingTargetYawErrorNorm(GameStateDto frame) {
    if (frame == null || frame.playerPawn == null
        || frame.playerPawn.location == null
        || frame.playerPawn.viewRotation == null) {
      return 0.0;
    }
    CoordinatesDto target = resolveHeadingTarget(frame);
    if (target == null) {
      return 0.0;
    }
    double dx = target.x - frame.playerPawn.location.x;
    double dy = target.y - frame.playerPawn.location.y;
    if (Math.hypot(dx, dy) < 1.0) {
      return 0.0;
    }
    double targetYawRad = Math.atan2(dy, dx);
    double currentYawRad = NormalizationUtils.viewRotationXToRad(frame.playerPawn.viewRotation.x);
    double diff = wrapToPi(targetYawRad - currentYawRad);
    return NormalizationUtils.clampM11(diff / Math.PI);
  }

  public static double computeYawNormToward(GameStateDto frame, CoordinatesDto target) {
    if (frame == null || frame.playerPawn == null || frame.playerPawn.location == null || target == null) {
      return 0.0;
    }
    double dx = target.x - frame.playerPawn.location.x;
    double dy = target.y - frame.playerPawn.location.y;
    if (Math.hypot(dx, dy) < 1.0) {
      return 0.0;
    }
    double yawRad = Math.atan2(dy, dx);
    if (yawRad < 0.0) {
      yawRad += TAU;
    }
    return yawRad / TAU;
  }

  private static double wrapToPi(double rad) {
    if (!Double.isFinite(rad)) return 0.0;
    double r = rad % TAU;
    if (r > Math.PI) r -= TAU;
    if (r < -Math.PI) r += TAU;
    return r;
  }

  /**
   * 3D aim dot: cosine tussen view-direction (yaw + pitch) en de eenheidsvector
   * naar het target. Returnt 1.0 voor degenererende afstand. Range [-1, +1];
   * niet geclampt op [0,1] zodat callers zelf kunnen kiezen.
   *
   * <p>Centrale berekening voor reward-utility en feature-resolvers — voorkomt
   * drift tussen reward-target en wat het model als input ziet.</p>
   */
  public static double computeAimDot3D(
      double srcX, double srcY, double srcZ,
      int viewYawX, int viewPitchY,
      double tgtX, double tgtY, double tgtZ) {
    double dx = tgtX - srcX;
    double dy = tgtY - srcY;
    double dz = tgtZ - srcZ;
    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (!Double.isFinite(dist) || dist < 1.0) {
      return 1.0;
    }
    dx /= dist;
    dy /= dist;
    dz /= dist;

    double yawRad = NormalizationUtils.viewRotationXToRad(viewYawX);
    int signedPitchUt;
    if (viewPitchY <= MAX_PITCH_UP) {
      signedPitchUt = viewPitchY;
    } else if (viewPitchY >= 49152) {
      signedPitchUt = viewPitchY - 65536;
    } else {
      signedPitchUt = 0;
    }
    double pitchRad = signedPitchUt * (TAU / 65536.0);
    double cosPitch = Math.cos(pitchRad);
    double viewX = cosPitch * Math.cos(yawRad);
    double viewY = cosPitch * Math.sin(yawRad);
    double viewZ = Math.sin(pitchRad);

    return viewX * dx + viewY * dy + viewZ * dz;
  }

  public static double computePitchNormToward(GameStateDto frame, CoordinatesDto target) {
    if (frame == null || frame.playerPawn == null || frame.playerPawn.location == null || target == null) {
      return 0.0;
    }
    double eyeX = frame.playerPawn.location.x;
    double eyeY = frame.playerPawn.location.y;
    double eyeZ = frame.playerPawn.location.z + safeEyeHeight(frame.playerPawn);
    double dx = target.x - eyeX;
    double dy = target.y - eyeY;
    double dz = target.z - eyeZ;
    double horizontalDist = Math.hypot(dx, dy);
    if (!Double.isFinite(horizontalDist) || horizontalDist < 1.0) {
      return 0.0;
    }
    double pitchRad = Math.atan2(dz, horizontalDist);
    int signedPitch = radiansToSignedPitch(pitchRad);
    return signedPitch / 18000.0;
  }

  public static double extractCurrentPitchNorm(GameStateDto frame) {
    if (frame == null || frame.playerPawn == null || frame.playerPawn.viewRotation == null) {
      return 0.0;
    }
    return frame.playerPawn.viewRotation.y_norm;
  }

  public static int extractSignedPitch(GameStateDto frame) {
    if (frame == null || frame.playerPawn == null || frame.playerPawn.viewRotation == null) {
      return 0;
    }
    int raw = frame.playerPawn.viewRotation.y;
    if (raw <= MAX_PITCH_UP) {
      return raw;
    }
    if (raw >= 49152) {
      return raw - 65536;
    }
    return 0;
  }

  private static CoordinatesDto eyeTarget(PlayerDto player) {
    if (player == null || player.health <= 0 || player.location == null) {
      return null;
    }
    CoordinatesDto target = player.location.deepCopy();
    target.z += safeEyeHeight(player);
    return target;
  }

  private static double safeEyeHeight(PlayerDto player) {
    if (player == null || !Double.isFinite(player.baseEyeHeight)) {
      return 0.0;
    }
    return player.baseEyeHeight;
  }

  private static int radiansToSignedPitch(double radians) {
    if (!Double.isFinite(radians)) {
      return 0;
    }
    int ut = (int) Math.round(radians * 65536.0 / TAU);
    if (ut > MAX_PITCH_UP) {
      ut = MAX_PITCH_UP;
    }
    if (ut < MAX_PITCH_DOWN) {
      ut = MAX_PITCH_DOWN;
    }
    return ut;
  }
}
