package aiplay.play.udpstate.model;

/** Projectile TLV (tag 0x04): class, location, velocity, speed, damage, instigator. */
public record Projectile(
        int classHash,
        double locX, double locY, double locZ,
        double velX, double velY, double velZ,
        int speed,
        int damage,
        int instigatorNameHash,
        int instigatorTeam,
        // UC's Actor.DrawScale (decoded from drawScale*64 byte). For most projectiles ~1.0;
        // for charged BioGlob alt-fire goes up to ~4.0 (= ChargeSize 4.1 → 1 + 0.8*4.1).
        double drawScale) {
}
