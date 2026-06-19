package aiplay.shared.view;

/**
 * Continuous yaw/pitch delta intent from the viewrotation model, applied
 * directly by {@link aiplay.scanners.executors.command.YawPitchAccumulator}.
 */
public class ViewTurnIntent {

    public final float yawDelta;        // continuous yaw delta [-1, 1]
    public final float pitchDelta;      // continuous pitch delta [-1, 1]
    public final float angularError;    // heading error in radians [-π, π]
    public final long timestampMs;

    public ViewTurnIntent(float yawDelta, float pitchDelta, float angularError, long timestampMs) {
        this.yawDelta = yawDelta;
        this.pitchDelta = pitchDelta;
        this.angularError = angularError;
        this.timestampMs = timestampMs;
    }
}
