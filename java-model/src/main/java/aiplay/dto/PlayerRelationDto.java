package aiplay.dto;

public class PlayerRelationDto {
    /** sin(target-right) — positief = target rechts van kijkrichting */
    public double relSin;
    /** cos(target-forward) — 1 = recht vooruit, -1 = recht achteruit */
    public double relCos;
    /** cos * softDist2D (1 - exp(-d2D/τ)) in [0..1) */
    public double forwardDist_norm;
    /** sin * softDist2D (1 - exp(-d2D/τ)) in [0..1) */
    public double rightDist_norm;
    /** log1p(3D afstand) (raw), handig als feature */
    public double relDistanceLog;
    /** genormaliseerde 3D afstand in [0,1] op basis van kaartdiagonaal */
    public double distance_norm;
    /** Verticale bearing van source-oog naar target-oog, in [-1,+1] (atan2(dz,dist2D) / (π/2)). Positief = target boven source. */
    public double pitchBearing_norm;

    /** Target velocity geprojecteerd op source-forward (bot view-frame), genormaliseerd op 1000 UU/s. Positief = target beweegt voorwaarts t.o.v. bot. */
    public double relVelForward_norm;
    /** Target velocity geprojecteerd op source-right (bot view-frame, RIGHT_IS_POSITIVE conventie), genormaliseerd op 1000 UU/s. Positief = target dwarst naar rechts. Dit is de lead-aim relevante component. */
    public double relVelRight_norm;
    /** Target velocity Z-component (wereldframe = bot-frame voor verticaal), genormaliseerd op 1000 UU/s. */
    public double relVelUp_norm;

    /** Egocentric verticale offset (target.z - self.z), tanh-geschaald op 512 UU. Positief = target boven bot. Map-onafhankelijk; complementair aan pitchBearing_norm voor short-range engagements waar pitch ambigu wordt. */
    public double relZ_norm;

    /**
     * 3D aim alignment (cosine van view-direction tegen eye-to-eye vector naar target),
     * geclampt op [-1, +1]. 1.0 = perfect op target gericht (yaw én pitch). Zelfde
     * definitie als {@code RewardUtils.computeAimDot3D} zodat de reward-target en de
     * model-input dezelfde waarde zien.
     */
    public double aimAlignmentDot_norm;

    public PlayerRelationDto deepCopy() {
        PlayerRelationDto c = new PlayerRelationDto();
        c.relSin = this.relSin;
        c.relCos = this.relCos;
        c.forwardDist_norm = this.forwardDist_norm;
        c.rightDist_norm = this.rightDist_norm;
        c.relDistanceLog = this.relDistanceLog;
        c.distance_norm = this.distance_norm;
        c.pitchBearing_norm = this.pitchBearing_norm;
        c.relVelForward_norm = this.relVelForward_norm;
        c.relVelRight_norm = this.relVelRight_norm;
        c.relVelUp_norm = this.relVelUp_norm;
        c.relZ_norm = this.relZ_norm;
        c.aimAlignmentDot_norm = this.aimAlignmentDot_norm;
        return c;
    }
}
