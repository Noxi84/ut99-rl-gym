package aiplay.config.global;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the logical weapon tokens used by the per-bot
 * {@code preferred_weapon} field in {@code gameplay.json}.
 *
 * <p>Maps each token to the concrete UT99 weapon class(es) that can appear in a
 * bot's inventory. A token lists more than one class when a stock Botpack weapon
 * has an RL override variant ({@code NeuralNetWebserver.RL*}) that single-weapon
 * arenas hand out instead of the stock class — both must resolve to the same
 * token so {@code preferred_weapon: "pulse"} works in every profile.
 *
 * <p>Pure data: no inventory/ammo logic. The inventory-aware "is this usable,
 * else next-best" resolution lives in {@code PreferredWeaponResolver} (java-model),
 * which depends on {@code InventoryItemDto}. This class only needs to be readable
 * by {@link GlobalConfigRepository} for fail-fast token validation at config load.
 *
 * <p>The class strings here must match {@code WeaponClassNameTable} exactly so the
 * FNV-1a hash sent in the select-weapon command round-trips to the same value the
 * UScript side computes via {@code FNV1aHash(string(W.Class))}.
 */
public final class WeaponCatalog {

  private static final Map<String, List<String>> TOKEN_TO_CLASSES = new LinkedHashMap<>();
  static {
    // Class strings MUST use the exact casing UScript's string(W.Class) emits — that
    // follows the .uc filename, not the "class X" declaration. Stock Botpack has several
    // lowercase filenames (enforcer, ripper, minigun2, doubleenforcer, ut_biorifle) and
    // "WarheadLauncher" (lowercase h). Wrong casing → FNV hash mismatch → the weapon never
    // matches the inventory and the select command targets a hash UScript can't find.
    // Kept in lock-step with WeaponClassNameTable.
    TOKEN_TO_CLASSES.put("instagib",       List.of("Botpack.SuperShockRifle"));
    TOKEN_TO_CLASSES.put("enforcer",       List.of("Botpack.enforcer"));
    // Dual-enforcer is a flag on the same enforcer actor in stock UT99; the
    // doubleenforcer class is listed first for completeness, enforcer as the
    // real fallback. You don't "select" dual — picking up a second enforcer
    // makes the held one dual automatically.
    TOKEN_TO_CLASSES.put("doubleenforcer", List.of("Botpack.doubleenforcer", "Botpack.enforcer"));
    TOKEN_TO_CLASSES.put("shock",          List.of("Botpack.ShockRifle", "NeuralNetWebserver.RLShockRifle"));
    TOKEN_TO_CLASSES.put("biorifle",       List.of("Botpack.ut_biorifle"));
    TOKEN_TO_CLASSES.put("sniper",         List.of("Botpack.SniperRifle"));
    TOKEN_TO_CLASSES.put("flak",           List.of("Botpack.UT_FlakCannon"));
    TOKEN_TO_CLASSES.put("rocket",         List.of("Botpack.UT_Eightball", "NeuralNetWebserver.RLEightball"));
    TOKEN_TO_CLASSES.put("ripper",         List.of("Botpack.ripper"));
    TOKEN_TO_CLASSES.put("minigun",        List.of("Botpack.minigun2"));
    TOKEN_TO_CLASSES.put("pulse",          List.of("Botpack.PulseGun", "NeuralNetWebserver.RLPulseGun"));
    TOKEN_TO_CLASSES.put("translocator",   List.of("Botpack.Translocator"));
    TOKEN_TO_CLASSES.put("impacthammer",   List.of("Botpack.ImpactHammer"));
    TOKEN_TO_CLASSES.put("redeemer",       List.of("Botpack.WarheadLauncher"));
  }

  /**
   * Fallback priority used when the preferred weapon is unavailable or out of ammo:
   * the first token in this list whose weapon is in the inventory <em>and</em> usable
   * wins. Reliable general-combat weapons first; melee impact hammer last. The impact
   * hammer has no AmmoType so it is always usable — it is the guaranteed terminal
   * fallback. The translocator is deliberately absent: it is a movement tool, not a
   * combat weapon, so we never auto-switch to it (it stays selectable only as an
   * explicit preferred_weapon). Order is a sensible default, intended to be tuned later
   * (or replaced by the policy).
   */
  private static final List<String> FALLBACK_ORDER = List.of(
      "flak", "rocket", "minigun", "shock", "pulse", "sniper", "ripper",
      "biorifle", "enforcer", "instagib", "redeemer", "impacthammer"
  );

  public static boolean isValidToken(String token) {
    return token != null && TOKEN_TO_CLASSES.containsKey(token);
  }

  public static Set<String> tokens() {
    return TOKEN_TO_CLASSES.keySet();
  }

  /** Concrete inventory class(es) that satisfy this token. Throws on unknown token. */
  public static List<String> candidateClasses(String token) {
    List<String> classes = TOKEN_TO_CLASSES.get(token);
    if (classes == null) {
      throw new IllegalArgumentException("Unknown weapon token: '" + token
          + "' (expected one of " + TOKEN_TO_CLASSES.keySet() + ")");
    }
    return classes;
  }

  /** Tokens in next-best order for fallback resolution. */
  public static List<String> fallbackOrder() {
    return FALLBACK_ORDER;
  }

  private WeaponCatalog() {}
}
