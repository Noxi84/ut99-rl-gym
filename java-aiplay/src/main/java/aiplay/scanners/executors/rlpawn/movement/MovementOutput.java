package aiplay.scanners.executors.rlpawn.movement;

import aiplay.rl.MovementPrimitive;

/**
 * Decoded output from the movement portion of the joint policy: locomotion
 * primitive (8-sector from world direction), auxiliary actions, and raw
 * logits/actions for diagnostics and experience recording.
 */
public final class MovementOutput {
    public final float[] actions;
    public final float[] logits;
    public final MovementPrimitive locomotionAction;
    public final boolean jump;
    public final boolean duck;
    public final boolean fire;
    public final boolean altFire;

    /** Tanh-squashed sin of world movement direction. */
    public final float worldDirSin;
    /** Tanh-squashed cos of world movement direction. */
    public final float worldDirCos;
    /** Dodge direction: 0=none, 1=fwd, 2=back, 3=left, 4=right. */
    public final int dodgeDir;
    /** True when the model voted to stand still (sigmoid(bIdle) crossed enter threshold,
     *  taking hysteresis into account). When idle, locomotionAction == MovementPrimitive.IDLE. */
    public final boolean idle;
    /** CONTINUE view-relatieve bewegingshoek (signed UT-units, [-32768,32768]) — de model-richting
     *  vóór de 8-sector-snap. De CommandController maakt hiervan de world-moveYaw via
     *  {@code sentYaw + moveYawRelativeUt} (identieke frame-logica als de sector-route, enkel
     *  un-gequantiseerd) zodat de bot een smalle brug precies kan volgen i.p.v. 45°-grof te driften. */
    public final int moveYawRelativeUt;

    public MovementOutput(float[] actions, float[] logits, MovementPrimitive locomotionAction,
                          boolean jump, boolean duck, boolean fire, boolean altFire,
                          float worldDirSin, float worldDirCos, int dodgeDir, boolean idle,
                          int moveYawRelativeUt) {
        this.actions = actions;
        this.logits = logits;
        this.locomotionAction = locomotionAction;
        this.jump = jump;
        this.duck = duck;
        this.fire = fire;
        this.altFire = altFire;
        this.worldDirSin = worldDirSin;
        this.worldDirCos = worldDirCos;
        this.dodgeDir = dodgeDir;
        this.idle = idle;
        this.moveYawRelativeUt = moveYawRelativeUt;
    }
}
