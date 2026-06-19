package aiplay.dto;

/**
 * Per-slot enriched relation between bot and a jump pad. Populated by
 * {@code JumpPadEnricher} for the N nearest pads each frame.
 *
 * <p>All "_norm" fields are clamped to [-1, +1] or [0, 1] depending on semantics.
 * Egocentric fields (relSin/relCos, forwardDist, rightDist, landing*) are computed
 * in the bot's view-frame and must be re-rotated by the augmenter for synthetic yaws.
 */
public class JumpPadRelationDto {

    // Pad position relative to bot
    public double relSin;
    public double relCos;
    public double distance_norm;
    public double forwardDist_norm;
    public double rightDist_norm;
    public double zOffset_norm;     // (pad.z - bot.z) normalized by map z half-width, clamped [-1, +1]

    // Predicted landing position (after pad launches the bot)
    public double landingForwardDist_norm;
    public double landingRightDist_norm;
    public double landingZOffset_norm;
    /** cos(angle) between pad→landing direction and bot→current-mission-goal direction.
     *  +1 = pad sends bot directly towards goal, -1 = directly away, 0 = perpendicular. */
    public double landingTowardsGoal_cos;

    public JumpPadRelationDto deepCopy() {
        JumpPadRelationDto c = new JumpPadRelationDto();
        c.relSin = this.relSin;
        c.relCos = this.relCos;
        c.distance_norm = this.distance_norm;
        c.forwardDist_norm = this.forwardDist_norm;
        c.rightDist_norm = this.rightDist_norm;
        c.zOffset_norm = this.zOffset_norm;
        c.landingForwardDist_norm = this.landingForwardDist_norm;
        c.landingRightDist_norm = this.landingRightDist_norm;
        c.landingZOffset_norm = this.landingZOffset_norm;
        c.landingTowardsGoal_cos = this.landingTowardsGoal_cos;
        return c;
    }
}
