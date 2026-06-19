package aiplay.mission;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.MissionConfig;
import aiplay.dto.GameStateDto;

/**
 * Detects when the bot is stuck against an obstacle.
 *
 * Sequential state machine with external timestamp and locomotion input.
 * Usable for runtime, realtime feature-enrichment, and offline CSV labeling.
 *
 * Stuck = locomotion is active + speed below threshold + forward collision blocked,
 * sustained for at least trigger_ms. Once triggered, holds recovery for recovery_ms.
 *
 * All thresholds are config-driven via runtime.mission.* config.
 */
public class StuckDetector {

    private long stuckStartMs = -1;
    private long recoveryUntilMs = -1;

    private final double speedThreshold;
    private final double fwdCollisionThreshold;
    private final double diagCollisionThreshold;
    private final int triggerMs;
    private final int recoveryMs;

    public StuckDetector() {
        MissionConfig cfg = GlobalConfigRepository.shared().mission();
        this.speedThreshold = cfg.antiStuckSpeedNormThreshold();
        this.fwdCollisionThreshold = cfg.antiStuckForwardCollisionThresholdNorm();
        this.diagCollisionThreshold = cfg.antiStuckForwardDiagCollisionThresholdNorm();
        this.triggerMs = cfg.antiStuckTriggerMs();
        this.recoveryMs = cfg.antiStuckRecoveryMs();
    }

    /**
     * Returns true if the bot is currently stuck or in forced recovery.
     *
     * @param state current game state frame
     * @param locomotionActive whether the bot is actively trying to move (derived from frame)
     * @param frameTimestampMs timestamp of the frame (use frame timestamp, not wall-clock)
     */
    public boolean isStuck(GameStateDto state, boolean locomotionActive, long frameTimestampMs) {
        // Still in forced recovery period
        if (recoveryUntilMs > 0 && frameTimestampMs < recoveryUntilMs) {
            return true;
        }

        // Recovery period just expired — check if still physically stuck
        boolean justExitedRecovery = recoveryUntilMs > 0;
        if (justExitedRecovery) {
            recoveryUntilMs = -1;
        }

        if (state == null || state.playerPawn == null || state.playerPawn.collisions == null) {
            resetStuckTimer();
            return false;
        }

        boolean speedLow = state.playerPawn.speed_norm < speedThreshold;
        boolean fwdBlocked = state.playerPawn.collisions.fwdCollision_norm < fwdCollisionThreshold;
        boolean diagBlocked =
            state.playerPawn.collisions.fwdLeft30Collision_norm < diagCollisionThreshold
            && state.playerPawn.collisions.fwdRight30Collision_norm < diagCollisionThreshold;
        boolean cornerBoxed = isCornerBoxed(state);

        boolean physicallyBlocked = speedLow && (fwdBlocked || diagBlocked || cornerBoxed);

        // Not trying to move and not physically blocked → intentionally idle, not stuck
        if (!locomotionActive && !physicallyBlocked) {
            resetStuckTimer();
            return false;
        }

        boolean stuckSignal = physicallyBlocked;

        if (!stuckSignal) {
            resetStuckTimer();
            return false;
        }

        // Recovery just expired but still stuck → immediately re-trigger
        // (no 3s gap between recovery attempts)
        if (justExitedRecovery) {
            recoveryUntilMs = frameTimestampMs + recoveryMs;
            return true;
        }

        // Start or continue stuck timer
        if (stuckStartMs < 0) {
            stuckStartMs = frameTimestampMs;
        }

        if (frameTimestampMs - stuckStartMs >= triggerMs) {
            recoveryUntilMs = frameTimestampMs + recoveryMs;
            return true;
        }

        return false;
    }

    private void resetStuckTimer() {
        stuckStartMs = -1;
    }

    /**
     * Returns true when the pawn is wedged in a corner: at least three of the four
     * cardinal directions (fwd/back/left/right yaw-relative) read low collision norm.
     *
     * The fwd-only check missed the common failure mode where forward is open but
     * the policy is hold-back / strafing into a wall — characteristic of spawn-corner
     * lock-in observed live.
     */
    private boolean isCornerBoxed(GameStateDto state) {
        if (state == null || state.playerPawn == null || state.playerPawn.collisions == null) {
            return false;
        }
        double t = diagCollisionThreshold;
        int blocked = 0;
        if (state.playerPawn.collisions.fwdCollision_norm < t) blocked++;
        if (state.playerPawn.collisions.backCollision_norm < t) blocked++;
        if (state.playerPawn.collisions.leftCollision_norm < t) blocked++;
        if (state.playerPawn.collisions.rightCollision_norm < t) blocked++;
        return blocked >= 3;
    }
}
