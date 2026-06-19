package aiplay.dto;

/**
 * Static jump pad (UT99 {@code Kicker} actor) loaded from
 * {@code resources/config/maps/<map>.json → jump_pads[]}. Defines world location and the
 * impulse velocity vector applied to a Pawn that touches the pad.
 */
public class JumpPadDto {
    public CoordinatesDto location;
    /** Impulse vector applied on touch (UU/s). Includes horizontal components for directional pads. */
    public CoordinatesDto velocity;

    public JumpPadDto deepCopy() {
        JumpPadDto c = new JumpPadDto();
        if (this.location != null) c.location = this.location.deepCopy();
        if (this.velocity != null) c.velocity = this.velocity.deepCopy();
        return c;
    }
}
