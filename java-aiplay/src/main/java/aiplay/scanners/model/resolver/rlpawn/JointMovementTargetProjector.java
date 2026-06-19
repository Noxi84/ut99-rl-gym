package aiplay.scanners.model.resolver.rlpawn;

import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.dto.KeyboardMoveDto;
import aiplay.scanners.model.sample.AugmentedTrainingSample;
import aiplay.scanners.model.sample.TrainingSample;
import aiplay.scanners.model.target.TrainingTargetProjector;

/**
 * Movement target projector: continuous world-direction output (moveDir_sin, moveDir_cos)
 * plus binary dodge initiation signal.
 *
 * <p>Direction targets are computed from the player's velocity vector — view-independent.
 * Under yaw augmentation the world-space velocity does NOT change, so the target is
 * the same for all augmented variants.
 *
 * <p>Dodge target is 1.0 only on the initiation frame (DodgeState transitions from
 * NONE to a directional state). The dodge direction is derived from moveDir_sin/cos
 * at runtime — no separate direction label needed.
 */
class JointMovementTargetProjector implements TrainingTargetProjector {

    private static final String MOVE_DIR_SIN = "moveDir_sin";
    private static final String MOVE_DIR_COS = "moveDir_cos";
    private static final String DODGE = "dodge";
    private static final String B_JUMP = "bJump";
    private static final String B_DUCK = "bDuck";
    private static final String B_IDLE = "bIdle";
    private static final float MIN_SPEED_NORM = 0.01f;

    @Override
    public float resolveTargetValue(String featureId, AugmentedTrainingSample sample) {
        if (DODGE.equals(featureId)) {
            return resolveDodgeInitiation(sample);
        }
        if (B_JUMP.equals(featureId)) {
            return resolveJump(sample);
        }
        if (B_DUCK.equals(featureId)) {
            return resolveDuck(sample);
        }
        if (B_IDLE.equals(featureId)) {
            return resolveIdle(sample);
        }

        GameStateDto last = sample.getBaseSample().getLastFrame();
        if (last == null || last.playerPawn == null) {
            return 0f;
        }

        float vx = last.playerPawn.velocityX_norm;
        float vy = last.playerPawn.velocityY_norm;
        float speed = (float) Math.sqrt(vx * vx + vy * vy);

        if (speed < MIN_SPEED_NORM) {
            return 0f;
        }

        return switch (featureId) {
            case MOVE_DIR_SIN -> vy / speed;
            case MOVE_DIR_COS -> vx / speed;
            default -> throw new IllegalArgumentException(
                    "Unknown movement target feature: " + featureId);
        };
    }

    @Override
    public boolean isTargetBoolean(String featureId) {
        return DODGE.equals(featureId) || B_JUMP.equals(featureId)
            || B_DUCK.equals(featureId) || B_IDLE.equals(featureId);
    }

    /**
     * Idle target: 1.0 when the player explicitly chose not to press any movement key
     * (KeyboardCapture.moveIdle). View-independent. Yaw-augmentation invariant.
     *
     * <p>Note: NOT speed-based. A bot stuck against a wall has speed≈0 but pressed
     * forward — that is "stuck", not "idle". By keying on player intent
     * (moveIdle key state) the target cleanly separates natural rest from blocked
     * motion. Stuck-frames carry bIdle=0 so the model learns "no velocity +
     * collision-features → not idle".</p>
     */
    private float resolveIdle(AugmentedTrainingSample sample) {
        GameStateDto last = sample.getBaseSample().getLastFrame();
        if (last == null || last.playerPawn == null || last.playerPawn.playerPawn == null) {
            return 0f;
        }
        KeyboardMoveDto moveIdle = last.playerPawn.playerPawn.moveIdle;
        return (moveIdle != null && moveIdle.value) ? 1f : 0f;
    }

    private float resolveJump(AugmentedTrainingSample sample) {
        GameStateDto last = sample.getBaseSample().getLastFrame();
        if (last == null || last.playerPawn == null || last.playerPawn.playerPawn == null) {
            return 0f;
        }
        KeyboardMoveDto jump = last.playerPawn.playerPawn.bJump;
        return (jump != null && jump.value) ? 1f : 0f;
    }

    private float resolveDuck(AugmentedTrainingSample sample) {
        GameStateDto last = sample.getBaseSample().getLastFrame();
        if (last == null || last.playerPawn == null) {
            return 0f;
        }
        KeyboardMoveDto duck = last.playerPawn.bDuck;
        return (duck != null && duck.value) ? 1f : 0f;
    }

    /**
     * Dodge initiation: 1.0 when DodgeState transitions from NONE to a directional state
     * (LEFT/RIGHT/FORWARD/BACK). This is the single frame where the player initiates a dodge.
     */
    private float resolveDodgeInitiation(AugmentedTrainingSample sample) {
        TrainingSample base = sample.getBaseSample();
        GameStateDto current = base.getLastFrame();
        if (current == null || current.playerPawn == null) {
            return 0f;
        }

        DodgeState currentState = current.playerPawn.dodgeState;
        if (currentState == null || currentState == DodgeState.NONE
                || currentState == DodgeState.ACTIVE || currentState == DodgeState.DONE) {
            return 0f;
        }

        // Current frame is directional (LEFT/RIGHT/FORWARD/BACK).
        // Check if previous frame was NONE → this is the initiation frame.
        int idx = base.getCurrentIndex();
        if (idx <= 0) {
            return 0f;
        }
        GameStateDto prev = base.getSessionFrames().get(idx - 1);
        if (prev == null || prev.playerPawn == null) {
            return 0f;
        }
        DodgeState prevState = prev.playerPawn.dodgeState;
        if (prevState == DodgeState.NONE) {
            return 1f;
        }
        return 0f;
    }
}
