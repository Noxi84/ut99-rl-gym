package aiplay.play.udpstate.model;

/**
 * Collision probe block: 32 directional ray distances (chest height) + 8 signed
 * floor-elevation probes + 8 foot-height low rays.
 */
public record Collisions(
        int maxDist,
        int capsuleMargin,
        int[] distances,        // 32 entries; see UScript WriteCollisions for order
        int floorProbeDist,
        int floorMaxDrop,
        int[] floorDelta,       // 8 entries, SIGNED: +step-up / -drop; fwd, fwdRight, right, backRight, back, backLeft, left, fwdLeft
        int[] lowDistances) {   // 8 entries: foot-height horizontal rays; same 8 directions
}
