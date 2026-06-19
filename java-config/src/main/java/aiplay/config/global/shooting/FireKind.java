package aiplay.config.global.shooting;

/**
 * Per-mode fire semantiek. Bepaalt of de decoder de policy-bit doorlaat als
 * sustained-hold of via een UT99-cycle state machine clamped.
 *
 * <ul>
 *   <li>{@link #EDGE} — single-shot semantiek. State machine IDLE→FIRING(fire_duration_ms)
 *       →COOLDOWN(cooldown_ms)→IDLE. Bot's bit-1-tijdens-cooldown wordt onderdrukt.
 *       Bedoeld voor wapens waarvan UC's {@code state NormalFire} eigen animatie-Sleep
 *       heeft die tweede fire-edges stilletjes negeert (instagib, shock, flak, sniper).</li>
 *   <li>{@link #HOLD} — pass-through. {@code bit = modelWants} direct. Geen state-machine
 *       suppressie. Bedoeld voor wapens met sustained-fire (pulse-beam, minigun, ripper-primary)
 *       of charge/multi-load (bio-alt, eightball primary/secondary). UC's bFire/bAltFire blijft
 *       1 zolang policy wil, zodat UC's eigen Finish()-loop of charge-mechanic kan doorlopen.
 *       Single shot blijft mogelijk: 1 tick bit=1 → UC rising edge → 1 schot, daarna bit=0.</li>
 * </ul>
 */
public enum FireKind {
    EDGE,
    HOLD
}
