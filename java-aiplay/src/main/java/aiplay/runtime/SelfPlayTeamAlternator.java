package aiplay.runtime;

import aiplay.config.global.BotConfig;
import aiplay.config.global.BotModelConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds an effective per-instance bot layout for current-vs-champion self-play.
 *
 * <p>The canonical {@code gameplay.json} stays simple: current bots can live on
 * one team and champion bots on the other. At runtime, odd instances flip team
 * 0/1 for the same bot names and snapshot specs. That gives the
 * DualKPIDeltaGate balanced current-vs-champion samples without manual config
 * rewrites.</p>
 */
public final class SelfPlayTeamAlternator {

  private SelfPlayTeamAlternator() {}

  public static List<BotConfig> effectiveBotsForInstance(
      List<BotConfig> activeRlBots,
      int instanceId,
      boolean mapSymmetric) {
    if (!shouldSwap(activeRlBots, instanceId, mapSymmetric)) {
      return List.copyOf(activeRlBots);
    }

    List<BotConfig> out = new ArrayList<>(activeRlBots.size());
    for (BotConfig bot : activeRlBots) {
      out.add(withTeam(bot, swappedTeam(bot.team())));
    }
    return List.copyOf(out);
  }

  public static boolean shouldSwap(
      List<BotConfig> activeRlBots,
      int instanceId,
      boolean mapSymmetric) {
    return mapSymmetric
        && instanceId % 2 == 1
        && hasSelfPlayCurrent(activeRlBots)
        && hasSelfPlayChampion(activeRlBots);
  }

  public static boolean isSelfPlay(List<BotConfig> activeRlBots) {
    return hasSelfPlayCurrent(activeRlBots) && hasSelfPlayChampion(activeRlBots);
  }

  private static boolean hasSelfPlayCurrent(List<BotConfig> bots) {
    if (bots == null) return false;
    for (BotConfig bot : bots) {
      if (bot == null || !bot.isRl() || bot.models() == null) continue;
      for (BotModelConfig model : bot.models().values()) {
        if (model != null && model.enabled() && "current".equals(model.snapshot())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasSelfPlayChampion(List<BotConfig> bots) {
    if (bots == null) return false;
    for (BotConfig bot : bots) {
      if (bot == null || !bot.isRl() || bot.models() == null) continue;
      for (BotModelConfig model : bot.models().values()) {
        if (model != null && model.enabled()
            && model.snapshot() != null
            && !"current".equals(model.snapshot())) {
          return true;
        }
      }
    }
    return false;
  }

  private static int swappedTeam(int team) {
    if (team == 0) return 1;
    if (team == 1) return 0;
    return team;
  }

  private static BotConfig withTeam(BotConfig bot, int team) {
    Map<String, BotModelConfig> models = bot.models() == null ? null : Map.copyOf(bot.models());
    return new BotConfig(
        bot.enabled(),
        bot.active(),
        bot.name(),
        team,
        bot.role(),
        bot.type(),
        bot.appearance(),
        bot.ut99Config(),
        models,
        bot.preferredWeapon());
  }
}
