package aiplay.shared.view;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.SpawnPointDto;

/**
 * Shared target selection for the short window where every enemy is dead.
 *
 * <p>The selected target is an aim point above the enemy-team spawn floor location, not the raw
 * floor coordinate. That keeps pitch near the expected enemy eye/torso height instead of teaching
 * the view model to stare at the ground.</p>
 */
public final class EnemySpawnTargeting {

  public static final int DEFAULT_HOLD_TICKS = 90;

  private static final double SPAWN_MATCH_EPSILON_UU = 1.0;
  private static final double FALLBACK_EYE_HEIGHT_UU = 40.0;

  private EnemySpawnTargeting() {
  }

  public static CoordinatesDto resolveAimPoint(GameStateDto frame) {
    return resolveAimPoint(frame, null, 0);
  }

  public static CoordinatesDto resolveAimPoint(GameStateDto frame, TargetState state, int holdTicks) {
    SpawnPointDto spawn = resolveSpawn(frame, state, holdTicks);
    if (spawn == null || spawn.location == null) {
      return null;
    }
    CoordinatesDto target = spawn.location.deepCopy();
    target.z += expectedEnemyEyeHeight(frame);
    return target;
  }

  public static SpawnPointDto resolveSpawn(GameStateDto frame, TargetState state, int holdTicks) {
    if (!hasAllEnemiesDead(frame)
        || frame.playerPawn == null
        || frame.playerPawn.location == null
        || frame.spawnPoints == null
        || frame.spawnPoints.length == 0) {
      if (state != null) {
        state.reset();
      }
      return null;
    }

    int botTeam = frame.playerPawn.team;
    int effectiveHoldTicks = Math.max(0, holdTicks);
    SpawnPointDto chosen = null;

    if (state != null && state.hasHeldSpawn && state.ticksSinceSelection < effectiveHoldTicks) {
      chosen = findHeldSpawn(frame.spawnPoints, botTeam, state.heldSpawnX, state.heldSpawnY);
    }

    if (chosen == null) {
      chosen = findNearestEnemyTeamSpawn(frame, botTeam);
      if (chosen == null) {
        if (state != null) {
          state.reset();
        }
        return null;
      }
      if (state != null) {
        state.heldSpawnX = chosen.location.x;
        state.heldSpawnY = chosen.location.y;
        state.hasHeldSpawn = true;
        state.ticksSinceSelection = 0;
      }
      return chosen;
    }

    if (state != null) {
      state.ticksSinceSelection++;
    }
    return chosen;
  }

  public static boolean hasAllEnemiesDead(GameStateDto frame) {
    if (frame == null || frame.enemies == null || frame.enemies.length == 0) {
      return false;
    }
    for (PlayerDto enemy : frame.enemies) {
      if (enemy == null) {
        continue;
      }
      if (enemy.health > 0) {
        return false;
      }
    }
    // PlayerSlotConverter always initializes the enemy slot array. During respawn windows
    // the UT webservice may omit dead pawns entirely, leaving every slot null; that still
    // means there is no living enemy to aim at, so the spawn fallback should engage.
    return true;
  }

  private static SpawnPointDto findHeldSpawn(
      SpawnPointDto[] spawnPoints, int botTeam, double heldX, double heldY) {
    for (SpawnPointDto sp : spawnPoints) {
      if (sp == null || sp.location == null || sp.team == botTeam) {
        continue;
      }
      if (Math.abs(sp.location.x - heldX) < SPAWN_MATCH_EPSILON_UU
          && Math.abs(sp.location.y - heldY) < SPAWN_MATCH_EPSILON_UU) {
        return sp;
      }
    }
    return null;
  }

  private static SpawnPointDto findNearestEnemyTeamSpawn(GameStateDto frame, int botTeam) {
    SpawnPointDto best = null;
    double bestDistSq = Double.POSITIVE_INFINITY;
    for (SpawnPointDto sp : frame.spawnPoints) {
      if (sp == null || sp.location == null || sp.team == botTeam) {
        continue;
      }
      double dx = sp.location.x - frame.playerPawn.location.x;
      double dy = sp.location.y - frame.playerPawn.location.y;
      double distSq = dx * dx + dy * dy;
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = sp;
      }
    }
    return best;
  }

  private static double expectedEnemyEyeHeight(GameStateDto frame) {
    if (frame != null && frame.enemies != null) {
      for (PlayerDto enemy : frame.enemies) {
        if (enemy != null && Double.isFinite(enemy.baseEyeHeight) && enemy.baseEyeHeight > 0.0) {
          return enemy.baseEyeHeight;
        }
      }
    }
    if (frame != null
        && frame.playerPawn != null
        && Double.isFinite(frame.playerPawn.baseEyeHeight)
        && frame.playerPawn.baseEyeHeight > 0.0) {
      return frame.playerPawn.baseEyeHeight;
    }
    return FALLBACK_EYE_HEIGHT_UU;
  }

  public static final class TargetState {
    private double heldSpawnX;
    private double heldSpawnY;
    private boolean hasHeldSpawn;
    private int ticksSinceSelection;

    public void reset() {
      heldSpawnX = 0.0;
      heldSpawnY = 0.0;
      hasHeldSpawn = false;
      ticksSinceSelection = 0;
    }
  }
}
