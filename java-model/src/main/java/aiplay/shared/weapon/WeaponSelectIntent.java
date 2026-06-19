package aiplay.shared.weapon;

/**
 * The weapon a bot should currently hold, published by the weapon-planner lane and
 * read by the CommandController. {@code weaponClass} is the canonical UT99 class string
 * (e.g. {@code "Botpack.UT_FlakCannon"}); {@code classHash} is its FNV-1a hash, sent
 * over UDP in the select-weapon command and matched against inventory class hashes on
 * the UScript side.
 *
 * @param weaponClass canonical class string of the chosen weapon
 * @param classHash   FNV-1a hash of {@code weaponClass} (same routine as WeaponClassNameTable / UScript)
 * @param timestampMs publish time in ms (freshness marker)
 */
public record WeaponSelectIntent(String weaponClass, int classHash, long timestampMs) {}
