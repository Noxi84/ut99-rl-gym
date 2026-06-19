package aiplay.dto;

/**
 * Live pickup-state, één entry per UC-side {@code Pickup}-actor. Geconverteerd
 * uit {@link aiplay.ut99webmodel.PickupEntry} door {@code PickupsJsonToDtoConverter}.
 */
public class PickupDto {
    /** FNV-1a 32-bit hash van de UC class-naam (zelfde algoritme als UC's
     *  {@code FNV1aHash}). Matched tegen statische pickups via
     *  {@code PickupConfigRepository.matchLive}. */
    public int classHash;
    public double locX;
    public double locY;
    public double locZ;
    /** True = pickup is taken / wacht op respawn. False = beschikbaar om op te pakken. */
    public boolean hidden;
    /** Seconden tot respawn als hidden, anders 0. */
    public double remainingRespawnSec;

    public PickupDto deepCopy() {
        PickupDto c = new PickupDto();
        c.classHash = this.classHash;
        c.locX = this.locX;
        c.locY = this.locY;
        c.locZ = this.locZ;
        c.hidden = this.hidden;
        c.remainingRespawnSec = this.remainingRespawnSec;
        return c;
    }
}
