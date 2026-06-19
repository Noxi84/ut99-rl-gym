package aiplay.config.global.shooting;

/**
 * Cooldown configuration for a single fire mode (primary or secondary) of a weapon.
 *
 * <p>Timing convention (alleen relevant voor {@link FireKind#EDGE}):
 * {@code fireDurationMs + cooldownMs} moet ≥ de UT99 fire-cycle van het wapen + ~50 ms
 * marge. De cycle is de tijd dat UC's {@code state NormalFire} of {@code state AltFiring}
 * actief is — daarbinnen is {@code function Fire(F){}} een no-op (zie {@code
 * TournamentWeapon.uc:460}). Te korte totaalwaarde → decoder stuurt een nieuwe fire-edge
 * naar UC tijdens NormalFire, UC negeert hem stilletjes, schot is verloren.
 *
 * <p>Voor {@link FireKind#HOLD} worden {@code fireDurationMs} en {@code cooldownMs}
 * genegeerd — de bit gaat direct door naar UC. Velden mogen ingevuld blijven voor
 * documentatie/fallback.
 *
 * <p>Cycle is afgeleid uit {@code Botpack/<Weapon>.uc}:
 * <ul>
 *   <li>Animation-bound (default): {@code numFrames / (BaseRate × PlayRate)} waar
 *       {@code BaseRate} de {@code RATE} param uit {@code MESH SEQUENCE} is (default 15 fps),
 *       en {@code PlayRate} de tweede arg van {@code PlayAnim/LoopAnim}.</li>
 *   <li>Sleep-bound: sommige wapens (Minigun2, PulseGun) hebben een eigen {@code state NormalFire}
 *       met {@code Sleep(seconds)} die de cycle vastlegt onafhankelijk van de animatie.</li>
 * </ul>
 *
 * <p>Aanname: {@code FireAdjust=1.0} (default property, alleen bots met Skill&lt;2 krijgen
 * een lagere waarde via {@code TournamentWeapon.BecomeItem}). Onze RL-pawn is geen {@code Bot}
 * subclass dus FireAdjust blijft 1.0. Anders moeten cycles herrekend worden met de
 * actuele FireAdjust van de pawn.
 *
 * <p>Concrete waarden: zie {@code resources/config/runtime.json} → {@code shooting.weapons}.
 */
public record WeaponFireModeConfig(
    int fireDurationMs,
    int cooldownMs,
    FireKind kind
) {

}
