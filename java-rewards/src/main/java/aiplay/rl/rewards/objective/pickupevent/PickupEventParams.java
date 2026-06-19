package aiplay.rl.rewards.objective.pickupevent;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

import java.util.Map;

/**
 * Sparse rewards rond statische pickup-events voor de joint VR+shooting policy.
 *
 * <p>Categorieën:
 * <ul>
 *   <li><b>High-value semantic</b> (shieldbelt / armor / thighpads / amp / megahealth):
 *       Δhealth+Δarmor-capped × weight. Amp is flat. Bestaande v1.
 *   <li><b>Pad-A heal pickups</b> (medbox / vial): zelfde Δ-formule. Vials hebben
 *       hogere cap (199 hp) zodat bot bij at-100 cap nog vials oppakt.
 *   <li><b>Pad-A weapon pickups</b>: conditional flat — wapen-eerst-keer-gepakt geeft
 *       {@code weaponNewFlat} (groot), wapen-al-bezit geeft {@code weaponOwnedFlat} (klein,
 *       = ammo refill). Ownership-check op prev-frame inventory.
 *   <li><b>Pad-A ammo pickups</b>: per ammo canonical (lowercase) een flat weight via
 *       {@code ammoWeights} map. Geen Δammo-berekening in v1 — flat geeft duidelijk
 *       signaal en is direct interpretabel; Δ-cap kan later.
 * </ul>
 *
 * <p>Instigator = de player binnen {@link #INSTIGATOR_RADIUS_UU} van de pickup-locatie
 * op de transition-frame (Java state-diff detector — geen UC event hook nodig).
 */
public record PickupEventParams(
    RewardMetadata metadata,
    double shieldbeltWeight,
    double shieldbeltCap,
    double armorWeight,
    double armorCap,
    double thighpadsWeight,
    double thighpadsCap,
    double ampFlatWeight,
    double megahealthWeight,
    double megahealthCap,
    /** Pad-A heal_pack (MedBox / HealthPack). */
    double medboxWeight,
    double medboxCap,
    /** Pad-A heal_vial (HealthVial). */
    double vialWeight,
    double vialCap,
    /** Pad-A weapon flat-reward — bot had het wapen NIET in inventory (prev frame). */
    double weaponNewFlat,
    /** Pad-A weapon flat-reward — bot had het wapen WEL (alleen ammo refill). */
    double weaponOwnedFlat,
    /** Pad-A ammo per-class flat weights (key = canonical lowercase). */
    Map<String, Double> ammoWeights)
    implements RewardBlock {

  /** Radius rond pickup-locatie waarbinnen we de instigator zoeken. 80 UU =
   *  iets ruimer dan stock pickup-touch (typisch 40 UU collision-radius). */
  public static final double INSTIGATOR_RADIUS_UU = 80.0;

  public PickupEventParams {
    if (metadata == null) {
      throw new IllegalArgumentException("PickupEventParams.metadata required");
    }
    if (ammoWeights == null) {
      throw new IllegalArgumentException("PickupEventParams.ammoWeights required (use empty Map.of() to disable)");
    }
    ammoWeights = Map.copyOf(ammoWeights);
  }

  @Override
  public boolean enabled() {
    if (shieldbeltWeight != 0.0 || armorWeight != 0.0 || thighpadsWeight != 0.0
        || ampFlatWeight != 0.0 || megahealthWeight != 0.0) return true;
    if (medboxWeight != 0.0 || vialWeight != 0.0
        || weaponNewFlat != 0.0 || weaponOwnedFlat != 0.0) return true;
    for (double w : ammoWeights.values()) if (w != 0.0) return true;
    return false;
  }
}
