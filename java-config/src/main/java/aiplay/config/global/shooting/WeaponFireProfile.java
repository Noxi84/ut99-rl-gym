package aiplay.config.global.shooting;

/**
 * Per-weapon cooldown profile. {@code secondary} is {@code null} if the weapon has no alt-fire mode (e.g. instagib SuperShockRifle).
 */
public record WeaponFireProfile(
    String weaponClass,
    WeaponFireModeConfig primary,
    WeaponFireModeConfig secondary
) {

}
