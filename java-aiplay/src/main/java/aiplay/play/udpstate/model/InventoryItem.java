package aiplay.play.udpstate.model;

/** One carried inventory weapon: class hash + current/max ammo. */
public record InventoryItem(int classHash, int ammo, int maxAmmo) {
}
