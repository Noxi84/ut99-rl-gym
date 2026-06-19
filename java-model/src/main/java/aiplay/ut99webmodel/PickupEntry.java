package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Live pickup-state, één entry per Pickup-actor uit UC. Velden zijn dezelfde
 * String-encoding als {@link ProjectileEntry} en {@link Player} hanteren —
 * downstream code parseert ze waar nodig naar primitives.
 *
 * <p>Mapping naar de TLV tag 0x05 payload (zie {@code RLUdpStateSender.uc}):
 * <ul>
 *   <li>{@code ClassHash} ← FNV1a uint32 van de UC class-naam
 *   <li>{@code Location} ← "X,Y,Z" wereldcoördinaten (UU)
 *   <li>{@code BHidden} ← "0"/"1" — !available
 *   <li>{@code RemainingRespawnSec} ← seconden tot respawn als bHidden=1, anders "0"
 * </ul>
 *
 * <p>Live↔static matching gebeurt downstream in {@code PickupConfigRepository}:
 * de Java side bezit zowel {@code ClassHash} (uit deze entry) als de canonical
 * class-strings uit {@code pickup-types.json} en kan met FNV1a een omgekeerde
 * lookup doen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PickupEntry {
    public String ClassHash;
    public String Location;
    public String BHidden;
    public String RemainingRespawnSec;
}
