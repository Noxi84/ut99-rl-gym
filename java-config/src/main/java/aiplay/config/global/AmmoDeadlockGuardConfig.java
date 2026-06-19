package aiplay.config.global;

/**
 * Watchdog die detecteert wanneer alle levende RLBots in een match gelijktijdig
 * geen usable weapon meer hebben (typisch arena-modes zoals PulseOnlyArena waar
 * de bots geen ImpactHammer/Translocator fallback hebben). Bij overschrijden van
 * {@code thresholdSeconds} forceert de detector één bot tot suicide zodat de
 * match uit deadlock komt; de bot respawnt met DefaultInventory.
 *
 * <p>De keuze valt op de lexicografisch laagste RLBot-naam onder de no-ammo
 * bots — elke runtime komt lokaal tot dezelfde keuze, dus exact één bot suicidet
 * zonder cross-process synchronisatie.
 */
public record AmmoDeadlockGuardConfig(boolean enabled, double thresholdSeconds) {

  public long thresholdMillis() {
    return Math.round(thresholdSeconds * 1000.0);
  }
}
