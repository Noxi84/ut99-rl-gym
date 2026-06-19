package aiplay.shared.view;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;

/**
 * Runtime yaw fallback for the no-live-enemy respawn window.
 *
 * <p>The viewrotation actor normally controls yaw directly. If its current weights emit a
 * saturated yaw command while every enemy is dead, the spawn-target features alone cannot stop
 * the live bot until the model is retrained. This helper converts the same sticky enemy-spawn
 * target used by rewards/features into a bounded yaw command for that narrow window.</p>
 */
public final class EnemySpawnYawFallback {

  private static final double TAU = 2.0 * Math.PI;
  private static final double RESPONSE_TICKS = 4.0;
  private static final double SETTLE_RAD = Math.toRadians(3.0);
  private static final float MAX_YAW_DELTA = 0.35f;
  private static final float ATANH_LIMIT = 0.999f;

  private EnemySpawnYawFallback() {
  }

  public static Result resolve(
      GameStateDto frame,
      EnemySpawnTargeting.TargetState state,
      int holdTicks,
      int continuousMaxStep) {
    CoordinatesDto target = EnemySpawnTargeting.resolveAimPoint(frame, state, holdTicks);
    if (target == null
        || frame == null
        || frame.playerPawn == null
        || frame.playerPawn.location == null
        || frame.playerPawn.viewRotation == null
        || continuousMaxStep <= 0) {
      return Result.inactive();
    }

    double dx = target.x - frame.playerPawn.location.x;
    double dy = target.y - frame.playerPawn.location.y;
    if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.hypot(dx, dy) < 1.0) {
      return new Result(true, 0.0f, 0.0f, 0.0f, target);
    }

    double targetYaw = Math.atan2(dy, dx);
    double currentYaw = extractCurrentYawRad(frame);
    double angularError = wrapToPi(targetYaw - currentYaw);

    float yawDelta = 0.0f;
    if (Math.abs(angularError) >= SETTLE_RAD) {
      double errorUt = angularError * (65536.0 / TAU);
      yawDelta = clamp((float) (errorUt / (continuousMaxStep * RESPONSE_TICKS)),
          -MAX_YAW_DELTA, MAX_YAW_DELTA);
    }

    return new Result(true, yawDelta, atanh(yawDelta), (float) angularError, target);
  }

  private static float extractCurrentYawRad(GameStateDto frame) {
    int rawYaw = frame.playerPawn.viewRotation.x & 0xFFFF;
    float yawRad = (float) (rawYaw * (TAU / 65536.0));
    if (yawRad > (float) Math.PI) {
      yawRad -= (float) TAU;
    }
    return yawRad;
  }

  private static double wrapToPi(double rad) {
    if (!Double.isFinite(rad)) {
      return 0.0;
    }
    double r = rad % TAU;
    if (r > Math.PI) r -= TAU;
    if (r < -Math.PI) r += TAU;
    return r;
  }

  private static float atanh(float x) {
    float c = clamp(x, -ATANH_LIMIT, ATANH_LIMIT);
    return (float) (0.5 * Math.log((1.0 + c) / (1.0 - c)));
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public record Result(
      boolean active,
      float yawDelta,
      float yawRaw,
      float angularError,
      CoordinatesDto target) {

    private static Result inactive() {
      return new Result(false, 0.0f, 0.0f, 0.0f, null);
    }
  }
}
