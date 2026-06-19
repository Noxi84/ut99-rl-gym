package aiplay.scanners.executors.weapon;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.WeaponCatalog;
import aiplay.dto.GameStateDto;
import aiplay.dto.GridFrame;
import aiplay.dto.InventoryItemDto;
import aiplay.dto.PlayerDto;
import aiplay.logging.SessionRollingLogger;
import aiplay.play.WeaponClassNameTable;
import aiplay.runtime.identity.IdentityLookups;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.scanners.executors.PlayExecutorComponent;
import aiplay.scanners.executors.PlayExecutorLogger;
import aiplay.shared.weapon.WeaponSelectIntent;
import aiplay.shared.weapon.WeaponSelectIntentBus;
import aiplay.weapon.PreferredWeaponResolver;
import behaviortree.BehaviorTreeContext;
import java.util.List;
import java.util.logging.Logger;

/**
 * Weapon-planner lane: at a low rate, decides which weapon this bot should hold and
 * publishes it to the {@link WeaponSelectIntentBus}. The {@code CommandController}
 * edge-triggers the actual switch (only sends a select command when the chosen weapon
 * is not already active).
 *
 * <p>For now the decision is the bot's configured {@code preferred_weapon} token,
 * resolved against the live inventory (next-best-with-ammo on fallback via
 * {@link PreferredWeaponResolver}). Later the policy can replace this — the
 * lane → bus → actuator wiring stays the same.
 *
 * <p>Follows the {@code MissionPlayExecutor} pattern: overrides {@link #execute},
 * reads its slot's game-state frames from the blackboard, no AiController. The
 * executor is per-bot (PlayExecutionService is built per bot), so the
 * change-detection field is safe.
 */
@PlayExecutorComponent(priority = 4)
public class WeaponPlanExecutor implements IPlayExecutor {

  private Logger log;
  private String lastChosen;

  // Anti-flapping hysteresis state (per-bot; executor is per-bot).
  private String committed;
  private long committedAtMs;

  @Override
  public String getExecutorKey() {
    return "weapon-planner";
  }

  @Override
  public boolean isActive() {
    return true;
  }

  @Override
  public int getPredictionFps() {
    return GlobalConfigRepository.shared().weaponPlanner().fps();
  }

  @Override
  public void execute(BehaviorTreeContext context) {
    List<GridFrame> gameStates = context.getBlackboard().get(BlackboardKeys.WEAPON_GAMESTATES);
    if (gameStates == null || gameStates.isEmpty()) return;

    BotConfig cfg = IdentityLookups.currentBotConfig();
    if (cfg == null || cfg.preferredWeapon() == null) return; // UT99 / non-RL bot — no planning

    GameStateDto latest = gameStates.get(gameStates.size() - 1).state();
    if (latest == null || latest.playerPawn == null) return;
    PlayerDto self = latest.playerPawn;

    String ideal = PreferredWeaponResolver.resolve(cfg.preferredWeapon(), self.inventory);
    if (ideal == null) return; // nothing carried yet (boot / respawn frame)

    String chosen = applyHysteresis(ideal, cfg.preferredWeapon(), self.inventory);

    WeaponSelectIntentBus bus = context.getBlackboard().get(BlackboardKeys.WEAPON_SELECT_INTENT_BUS);
    if (bus == null) return;

    int hash = WeaponClassNameTable.fnv1a(chosen);
    bus.publish(new WeaponSelectIntent(chosen, hash, System.currentTimeMillis()));

    if (!chosen.equals(lastChosen)) {
      logger(context).info("WEAPON_PLAN bot=" + cfg.name()
          + " preferred=" + cfg.preferredWeapon() + " ideal=" + ideal + " chosen=" + chosen
          + " current=" + self.weaponClass);
      lastChosen = chosen;
    }
  }

  /**
   * Anti-flapping: switching TO the preferred weapon is always immediate (it's the goal
   * and doesn't flap — also resets after respawn with a fresh arsenal). A fallback↔fallback
   * change is held until the committed weapon becomes unusable (out of ammo / dropped) or
   * the dwell window elapses, damping rapid switching when a weapon's ammo hovers near zero.
   */
  private String applyHysteresis(String ideal, String preferredToken, List<InventoryItemDto> inventory) {
    long now = System.currentTimeMillis();
    boolean idealIsPreferred = WeaponCatalog.candidateClasses(preferredToken).contains(ideal);

    if (committed == null || idealIsPreferred) {
      committed = ideal;
      committedAtMs = now;
      return committed;
    }
    if (ideal.equals(committed)) {
      return committed;
    }

    int dwellMs = GlobalConfigRepository.shared().weaponPlanner().dwellMs();
    boolean committedUsable = PreferredWeaponResolver.isCarriedUsable(committed, inventory);
    if (!committedUsable || (now - committedAtMs) >= dwellMs) {
      committed = ideal;
      committedAtMs = now;
    }
    return committed;
  }

  private Logger logger(BehaviorTreeContext context) {
    if (log == null) {
      String sessionId = context.getBlackboard().get(BlackboardKeys.SESSION_ID);
      log = SessionRollingLogger.get(sessionId, "weapon");
    }
    return log;
  }

  @Override
  public PlayExecutorAiController getPlayExecutorAiController() {
    return null;
  }

  @Override
  public PlayExecutorLogger getPlayExecutorLogger() {
    return null;
  }
}
