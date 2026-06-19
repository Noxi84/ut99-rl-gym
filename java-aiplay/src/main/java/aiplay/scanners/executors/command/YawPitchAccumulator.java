package aiplay.scanners.executors.command;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.command.PitchConfig;
import aiplay.config.global.command.YawHeadingConfig;

/**
 * Accumulates yaw/pitch state from continuous model-driven deltas.
 * Model outputs a [-1, 1] delta per tick which is multiplied by continuous_max_step
 * and added to the smoothed state. Handles server re-sync on init/respawn.
 */
public class YawPitchAccumulator {

    private int smoothedYaw = -1;
    private int smoothedPitch = 0;

    public int smoothedYaw() { return smoothedYaw; }
    public int smoothedPitch() { return smoothedPitch; }

    /** Re-sync yaw/pitch from server state (init or respawn). */
    public void sync(int serverYaw, int signedPitch) {
        smoothedYaw = serverYaw;
        smoothedPitch = signedPitch;
    }

    public boolean isInitialized() {
        return smoothedYaw >= 0;
    }

    /**
     * Apply continuous yaw delta directly (model-driven).
     * Returns yaw step and updates smoothedYaw.
     */
    public int applyContinuousYaw(float yawDelta) {
        YawHeadingConfig cfg = GlobalConfigRepository.shared().commandController().yawHeading();
        int maxStep = cfg.continuousMaxStep();
        float deadZone = (float) cfg.deadZoneRad();
        if (Math.abs(yawDelta) < deadZone) yawDelta = 0f;
        int yawStep = Math.round(yawDelta * maxStep);
        smoothedYaw = (smoothedYaw + yawStep) & 0xFFFF;
        return yawStep;
    }

    /**
     * Apply continuous pitch delta directly (model-driven).
     * Decays toward center when idle.
     */
    public void applyContinuousPitch(float pitchDelta) {
        PitchConfig cfg = GlobalConfigRepository.shared().commandController().pitch();
        int maxStep = cfg.continuousMaxStep();
        float deadZone = (float) cfg.deadZoneRad();
        if (Math.abs(pitchDelta) < deadZone) pitchDelta = 0f;
        int pitchStep = Math.round(pitchDelta * maxStep);
        if (pitchStep == 0 && smoothedPitch != 0) {
            pitchStep = (int) Math.round(-smoothedPitch * cfg.centerDecayRate());
        }
        smoothedPitch = clampPitch(smoothedPitch + pitchStep);
    }

    /** Sync pitch from server (when no turn intent). */
    public void syncPitchFromServer(int unsignedPitch) {
        smoothedPitch = toSignedPitch(unsignedPitch);
    }

    public int sentPitch() {
        return toUnsignedPitch(smoothedPitch);
    }

    // --- UT unit conversions ---

    static int shortestDeltaUt(int fromYaw, int toYaw) {
        return ((toYaw - fromYaw + 32768) & 0xFFFF) - 32768;
    }

    static int toSignedPitch(int unsignedPitch) {
        if (unsignedPitch <= 18000) return unsignedPitch;
        if (unsignedPitch >= 49152) return unsignedPitch - 65536;
        return 0;
    }

    static int toUnsignedPitch(int signedPitch) {
        return ((signedPitch % 65536) + 65536) % 65536;
    }

    private static int clampPitch(int signedPitch) {
        var cfg = GlobalConfigRepository.shared().commandController().pitch();
        return Math.max(cfg.minSigned(), Math.min(cfg.maxSigned(), signedPitch));
    }
}
