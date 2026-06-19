package aiplay.rl.rewards.movement.facing;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CoordinatesDto;

/**
 * Dense facing reward: encourages looking toward the current movement objective. Uses dot product
 * between view direction and direction-to-target.
 */
public class FacingReward implements RewardComponent {

  private final FacingParams params;

  public FacingReward(FacingParams params) {
    if (params == null) {
      throw new IllegalArgumentException("FacingReward requires non-null FacingParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (params.bonus() == 0.0 || ctx.curr().playerPawn.health <= 0) {
      return 0.0;
    }

    CoordinatesDto target = RewardUtils.resolveMovementPrimaryObjective(ctx.curr());
    if (target == null
        || ctx.curr().playerPawn.location == null
        || ctx.curr().playerPawn.viewRotation == null) {
      return 0.0;
    }

    double toDirX = target.x - ctx.curr().playerPawn.location.x;
    double toDirY = target.y - ctx.curr().playerPawn.location.y;
    double toDist = Math.sqrt(toDirX * toDirX + toDirY * toDirY);
    if (toDist < 1.0) {
      return params.bonus();
    }
    toDirX /= toDist;
    toDirY /= toDist;

    double yawRad = (ctx.curr().playerPawn.viewRotation.x & 0xFFFF) * (2.0 * Math.PI / 65536.0);
    double viewDirX = Math.cos(yawRad);
    double viewDirY = Math.sin(yawRad);

    double dot = viewDirX * toDirX + viewDirY * toDirY;
    return params.bonus() * Math.max(0.0, dot);
  }
}
