package aiplay.scanners.executors.rlpawn.movement;

import aiplay.rl.MovementPrimitive;
import aiplay.shared.movement.MovementIntent;

/**
 * Maps a {@link MovementOutput} to a {@link MovementIntent} for publication on
 * the policy intent bus. Stateless — safe to share across threads.
 */
public final class MovementIntentMapper {

    private MovementIntentMapper() {}

    public static MovementIntent map(MovementOutput output) {
        MovementPrimitive locomotion =
            output.locomotionAction != null ? output.locomotionAction : MovementPrimitive.IDLE;
        return new MovementIntent(locomotion, output.jump, output.duck,
            output.fire, output.altFire, output.dodgeDir, output.moveYawRelativeUt);
    }
}
