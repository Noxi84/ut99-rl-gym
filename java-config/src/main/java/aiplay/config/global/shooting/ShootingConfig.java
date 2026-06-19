package aiplay.config.global.shooting;

import java.util.Map;

/**
 * All shooting profiles, keyed by weapon class string (e.g. "Botpack.SuperShockRifle"). Lookup at runtime: {@code profileFor(frame.playerPawn.weaponClass)}.
 */
public record ShootingConfig(
    Map<String, WeaponFireProfile> profilesByWeaponClass
) {

  private static final Map<String, String> WEAPON_ALIASES = Map.of(
      "NeuralNetWebserver.RLEightball", "Botpack.UT_Eightball",
      "NeuralNetWebserver.RLPulseGun", "Botpack.PulseGun"
  );

  /**
   * Returns the fire profile for the given weapon class, or {@code null} if unknown.
   * RL weapon subclasses (RLEightball, RLPulseGun) are aliased to their Botpack parents.
   */
  public WeaponFireProfile profileFor(String weaponClass) {
      if (weaponClass == null) {
          return null;
      }
      WeaponFireProfile p = profilesByWeaponClass.get(weaponClass);
      if (p == null) {
          String alias = WEAPON_ALIASES.get(weaponClass);
          if (alias != null) p = profilesByWeaponClass.get(alias);
      }
      return p;
  }
}
