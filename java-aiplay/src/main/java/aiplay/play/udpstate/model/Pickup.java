package aiplay.play.udpstate.model;

/** Pickup TLV (tag 0x05): class, location, hidden/respawn state. */
public record Pickup(
        int classHash,
        double locX, double locY, double locZ,
        boolean hidden,
        // Seconden tot respawn. 0 als pickup beschikbaar is (hidden=false).
        double remainingRespawnSec) {
}
