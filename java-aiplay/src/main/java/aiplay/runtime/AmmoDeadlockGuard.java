package aiplay.runtime;

import aiplay.config.global.AmmoDeadlockGuardConfig;
import aiplay.dto.GameStateDto;
import aiplay.dto.InventoryItemDto;
import aiplay.dto.PlayerDto;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Per-runtime detector voor "alle levende RLBots gelijktijdig zonder usable
 * weapon" deadlocks. Wanneer de drempel passeert kiest hij lokaal — zonder
 * cross-process synchronisatie — de lexicografisch laagste no-ammo RLBot als
 * suicide-target; elke runtime komt tot dezelfde keuze omdat ze dezelfde
 * deterministische functie evalueren over de gedeelde game state.
 *
 * <p>Suicide-trigger wordt 3 seconden gecooldownd om dubbele triggers tijdens
 * de respawn-cycle te voorkomen wanneer een command verloren gaat.
 */
public final class AmmoDeadlockGuard {

  private static final Logger LOG = Logger.getLogger(AmmoDeadlockGuard.class.getName());

  private static final long POST_TRIGGER_COOLDOWN_MS = 3_000L;

  private final AmmoDeadlockGuardConfig config;
  private final String ownBotName;

  private long lastAnyRlBotHadAmmoMs = -1L;
  private long suppressUntilMs = -1L;

  public AmmoDeadlockGuard(AmmoDeadlockGuardConfig config, String ownBotName) {
    this.config = config;
    this.ownBotName = ownBotName;
  }

  /**
   * @return true wanneer deze bot zichzelf moet suiciden om de deadlock te
   *     doorbreken. Aanroeper is verantwoordelijk om het commando te sturen.
   */
  public boolean tick(GameStateDto state, long nowMs) {
    if (!config.enabled() || state == null || ownBotName == null) {
      return false;
    }
    if (nowMs < suppressUntilMs) {
      return false;
    }

    List<PlayerDto> rlBots = collectLivingRlBots(state);
    if (rlBots.isEmpty()) {
      return false;
    }

    List<String> noAmmoBots = new ArrayList<>();
    for (PlayerDto p : rlBots) {
      if (!hasUsableWeapon(p)) {
        noAmmoBots.add(p.name);
      }
    }

    if (noAmmoBots.size() < rlBots.size()) {
      lastAnyRlBotHadAmmoMs = nowMs;
      return false;
    }

    if (lastAnyRlBotHadAmmoMs < 0) {
      lastAnyRlBotHadAmmoMs = nowMs;
      return false;
    }

    long elapsed = nowMs - lastAnyRlBotHadAmmoMs;
    if (elapsed < config.thresholdMillis()) {
      return false;
    }

    String chosen = lowestName(noAmmoBots);
    if (!ownBotName.equals(chosen)) {
      return false;
    }

    LOG.warning("AmmoDeadlockGuard: triggering self-suicide for '" + ownBotName
        + "' — all " + rlBots.size() + " RLBots without ammo for " + elapsed
        + "ms (threshold=" + config.thresholdMillis() + "ms, candidates=" + noAmmoBots + ")");
    suppressUntilMs = nowMs + POST_TRIGGER_COOLDOWN_MS;
    return true;
  }

  private static List<PlayerDto> collectLivingRlBots(GameStateDto state) {
    List<PlayerDto> out = new ArrayList<>();
    addIfLivingRl(out, state.playerPawn);
    if (state.enemies != null) {
      for (PlayerDto p : state.enemies) addIfLivingRl(out, p);
    }
    if (state.teammates != null) {
      for (PlayerDto p : state.teammates) addIfLivingRl(out, p);
    }
    return out;
  }

  private static void addIfLivingRl(List<PlayerDto> out, PlayerDto p) {
    if (p == null) return;
    if (!p.bIsRLControlled) return;
    if (p.health <= 0) return;
    if (p.name == null || p.name.isBlank()) return;
    out.add(p);
  }

  private static boolean hasUsableWeapon(PlayerDto p) {
    if (p.inventory == null || p.inventory.isEmpty()) {
      return p.weaponAmmo > 0;
    }
    for (InventoryItemDto inv : p.inventory) {
      if (inv == null) continue;
      if (inv.maxAmmo <= 0) return true;
      if (inv.ammoAmount > 0) return true;
    }
    return false;
  }

  private static String lowestName(List<String> names) {
    String lowest = null;
    for (String n : names) {
      if (lowest == null || n.compareTo(lowest) < 0) {
        lowest = n;
      }
    }
    return lowest;
  }
}
