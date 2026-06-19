package aiplay.shared.movement;

import aiplay.rl.MovementPrimitive;

/**
 * Policy-level movement intent. Published by the movement policy executor,
 * consumed by the CommandController. This is an intent, not a command —
 * the controller decides how to execute it (dwell, dodge sequencing, etc.).
 */
public class MovementIntent {
    /** Sentinel voor "geen continue heading meegeleverd" → CommandController gebruikt de
     *  legacy 8-sector moveYaw (computeMoveYaw uit de keys). */
    public static final int NO_CONTINUOUS_YAW = Integer.MIN_VALUE;

    public final MovementPrimitive locomotion;
    public final boolean jump;
    public final boolean duck;
    public final boolean fire;
    public final boolean altFire;
    public final int dodgeDir;       // 0=none, 1=fwd, 2=back, 3=left, 4=right
    /** CONTINUE view-relatieve bewegingshoek (signed UT-units) of {@link #NO_CONTINUOUS_YAW}.
     *  Zie MovementOutput.moveYawRelativeUt — de CommandController maakt er {@code sentYaw + this}
     *  van zodat de bot een exacte (niet 45°-grof gequantiseerde) heading volgt. */
    public final int moveYawRelativeUt;
    public final long timestampMs;

    public MovementIntent(MovementPrimitive locomotion, boolean jump, boolean duck,
                          boolean fire, boolean altFire, int dodgeDir) {
        this(locomotion, jump, duck, fire, altFire, dodgeDir, NO_CONTINUOUS_YAW);
    }

    public MovementIntent(MovementPrimitive locomotion, boolean jump, boolean duck,
                          boolean fire, boolean altFire, int dodgeDir, int moveYawRelativeUt) {
        this.locomotion = locomotion != null ? locomotion : MovementPrimitive.IDLE;
        this.jump = jump;
        this.duck = duck;
        this.fire = fire;
        this.altFire = altFire;
        this.dodgeDir = dodgeDir;
        this.moveYawRelativeUt = moveYawRelativeUt;
        this.timestampMs = System.currentTimeMillis();
    }
}
