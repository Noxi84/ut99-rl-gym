package aiplay.weapon;

import aiplay.config.global.WeaponCatalog;
import aiplay.dto.InventoryItemDto;
import java.util.List;

/**
 * Decides which concrete weapon class a bot should hold, given its configured
 * preferred-weapon token and the weapons it currently carries.
 *
 * <p>Resolution:
 * <ol>
 *   <li>If a class matching the preferred token is in the inventory and usable, pick it.</li>
 *   <li>Otherwise walk {@link WeaponCatalog#fallbackOrder()} and pick the first usable
 *       weapon that is carried (next-best).</li>
 * </ol>
 *
 * <p>"Usable" = the weapon has ammo, or has no ammo type at all (impact hammer /
 * translocator → always usable, so the fallback always yields a result as long as the
 * inventory is non-empty).
 *
 * <p>Returns the canonical {@link WeaponCatalog} class string (equal to the inventory
 * item's {@code weaponClass}) so the caller can hash it with the same FNV-1a routine
 * the UScript side uses. Returns {@code null} only when nothing is carried.
 *
 * <p>Lives in java-model (next to {@link InventoryItemDto}); the token→class table is
 * the java-config {@link WeaponCatalog}, the single source of truth shared with config
 * validation.
 */
public final class PreferredWeaponResolver {

  /** @return the class to hold, or {@code null} when the inventory is empty/unreadable. */
  public static String resolve(String preferredToken, List<InventoryItemDto> inventory) {
    if (inventory == null || inventory.isEmpty()) {
      return null;
    }

    // 1. Preferred token first.
    String chosen = firstUsableCandidate(preferredToken, inventory);
    if (chosen != null) {
      return chosen;
    }

    // 2. Fallback: highest-priority usable weapon that is carried.
    for (String token : WeaponCatalog.fallbackOrder()) {
      chosen = firstUsableCandidate(token, inventory);
      if (chosen != null) {
        return chosen;
      }
    }
    return null;
  }

  /**
   * Whether the exact weapon class {@code weaponClass} is currently carried and usable.
   * Used by the planner's hysteresis: stay on the committed weapon while it remains usable.
   */
  public static boolean isCarriedUsable(String weaponClass, List<InventoryItemDto> inventory) {
    if (weaponClass == null || inventory == null) {
      return false;
    }
    for (InventoryItemDto item : inventory) {
      if (item != null && weaponClass.equals(item.weaponClass)) {
        return isUsable(item);
      }
    }
    return false;
  }

  /** First candidate class of {@code token} that is carried and usable, else null. */
  private static String firstUsableCandidate(String token, List<InventoryItemDto> inventory) {
    if (!WeaponCatalog.isValidToken(token)) {
      return null;
    }
    for (String candidateClass : WeaponCatalog.candidateClasses(token)) {
      for (InventoryItemDto item : inventory) {
        if (item != null && candidateClass.equals(item.weaponClass) && isUsable(item)) {
          return candidateClass;
        }
      }
    }
    return null;
  }

  /** Has ammo, or is an ammo-less weapon (maxAmmo==0 → impact hammer / translocator). */
  private static boolean isUsable(InventoryItemDto item) {
    return item.ammoAmount > 0 || item.maxAmmo == 0;
  }

  private PreferredWeaponResolver() {}
}
