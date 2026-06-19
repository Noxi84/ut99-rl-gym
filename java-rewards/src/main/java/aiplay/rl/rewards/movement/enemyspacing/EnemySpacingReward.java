package aiplay.rl.rewards.movement.enemyspacing;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.view.FireModeAimTargeting;

/**
 * Dense enemy spacing reward: maintains safe distance from enemies. Too close = penalty + escape
 * shaping, ideal band = stability bonus, too far = weak closing reward.
 */
public class EnemySpacingReward implements RewardComponent {

  private final EnemySpacingParams params;

  public EnemySpacingReward(EnemySpacingParams params) {
    if (params == null) {
      throw new IllegalArgumentException("EnemySpacingReward requires non-null EnemySpacingParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (ctx.curr().playerPawn.health <= 0) {
      return 0.0;
    }
    if (!params.enabled()) {
      return 0.0;
    }

    // Wapen-gegate: enemy_spacing is de mid-range positie-prior voor shock (combo + hitscan).
    // Geldt alleen wanneer de bot zelf een shock rifle vasthoudt (stock of RL-override), zodat de
    // prior het daadwerkelijk vastgehouden wapen volgt i.p.v. een globale weapon_profile-vlag.
    if (!FireModeAimTargeting.isShockRifleClass(ctx.curr().playerPawn.weaponClass)) {
      return 0.0;
    }

    PlayerDto closestEnemy = RewardUtils.findClosestEnemy(ctx.curr());
    if (closestEnemy == null
        || closestEnemy.location == null
        || ctx.curr().playerPawn.location == null) {
      return 0.0;
    }

    double interBaseDist = RewardUtils.computeInterBaseDistance(ctx.curr());
    if (interBaseDist < 1.0) {
      return 0.0;
    }

    double currDist =
        RewardUtils.distance2d(ctx.curr().playerPawn.location, closestEnemy.location)
            / interBaseDist;

    double minSpacing = params.minNorm();
    double idealMin = params.idealMinNorm();
    double idealMax = params.idealMaxNorm();
    double maxSpacing = params.maxNorm();

    double reward = 0.0;

    // Too close: penalty + reward for increasing distance
    if (currDist < minSpacing) {
      reward += params.tooClosePenalty();

      if (params.deltaScale() != 0.0 && ctx.prev().playerPawn.location != null) {
        PlayerDto prevEnemy = RewardUtils.findClosestEnemy(ctx.prev());
        if (prevEnemy != null && prevEnemy.location != null) {
          double prevDist =
              RewardUtils.distance2d(ctx.prev().playerPawn.location, prevEnemy.location)
                  / interBaseDist;
          reward += params.deltaScale() * (currDist - prevDist);
        }
      }
    }

    // Ideal band: stability bonus
    if (currDist >= idealMin && currDist <= idealMax) {
      reward += params.idealBonus();
    }

    // Too far: weak closing reward, only during pressure modes
    if (currDist > maxSpacing && maxSpacing > 0.0 && isPressureMode(ctx.curr())) {
      if (params.tooFarClosingScale() != 0.0 && ctx.prev().playerPawn.location != null) {
        PlayerDto prevEnemy = RewardUtils.findClosestEnemy(ctx.prev());
        if (prevEnemy != null && prevEnemy.location != null) {
          double prevDist =
              RewardUtils.distance2d(ctx.prev().playerPawn.location, prevEnemy.location)
                  / interBaseDist;
          reward += params.tooFarClosingScale() * (prevDist - currDist);
        }
      }
    }

    return reward;
  }

  private boolean isPressureMode(GameStateDto state) {
    PlayerDto pawn = state.playerPawn;
    if (pawn == null) {
      return false;
    }
    int team = pawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    return ownFlag != null && RewardUtils.isFlagCarried(ownFlag);
  }
}
