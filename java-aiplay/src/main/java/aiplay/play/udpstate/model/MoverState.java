package aiplay.play.udpstate.model;

/** Mover TLV (tag 0x06): name hash, location, keyframe state, interpolation progress. */
public record MoverState(
        int nameHash,
        double locX, double locY, double locZ,
        int keyNum, int prevKeyNum, int numKeys,
        boolean opening,
        boolean delaying,
        // PhysAlpha 0.0–1.0: interpolation progress between keyframes.
        double moveProgress) {
}
