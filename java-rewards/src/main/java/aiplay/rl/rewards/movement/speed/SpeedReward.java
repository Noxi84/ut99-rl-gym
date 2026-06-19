package aiplay.rl.rewards.movement.speed;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.CoordinatesDto;

/**
 * Dense speed reward: proportional to 2D movement speed between ticks. Capped to prevent reward
 * from falls/launches.
 */
public class SpeedReward implements RewardComponent {

  private final SpeedParams params;

  public SpeedReward(SpeedParams params) {
    if (params == null) {
      throw new IllegalArgumentException("SpeedReward requires non-null SpeedParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (params.scale() == 0.0 || ctx.curr().playerPawn.health <= 0) {
      return 0.0;
    }

    CoordinatesDto prevLoc = ctx.prev().playerPawn.location;
    CoordinatesDto currLoc = ctx.curr().playerPawn.location;
    if (prevLoc == null || currLoc == null) {
      return 0.0;
    }

    double dx = currLoc.x - prevLoc.x;
    double dy = currLoc.y - prevLoc.y;
    double speed = Math.sqrt(dx * dx + dy * dy);
    speed = Math.min(speed, 20.0);
    return params.scale() * speed;
  }
}
