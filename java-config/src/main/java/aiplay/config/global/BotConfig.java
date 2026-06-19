package aiplay.config.global;

import java.util.Map;

/**
 * Configuration for a single bot in the server.
 *
 * @param enabled    For AI bots: always true (presence in ai_bots = joins server). For UT99 bots: true = bot joins the server, false = completely absent.
 * @param active     true = bot plays actively (only meaningful when enabled=true). false = bot is present on the server but does not play (no inference, no commands). For UT99 stock bots, active is always true.
 * @param name       unique bot name (becomes PlayerReplicationInfo.PlayerName in UT99)
 * @param team       team index: 0 = red, 1 = blue
 * @param role       single rewardgroup selector ({@code role} field in {@code gameplay.json}). Required for AI bots; UT99 stock bots get the {@code "ut99_stock"} stub since they don't run through the RL pipeline. Must match exactly one rewardgroup name in each model's {@code rewards.json}.
 * @param type       "rl" (Java-controlled) or "ut99" (stock UT99 AI)
 * @param appearance visual appearance (mesh class + skin + face + voice). Required for every bot.
 * @param ut99Config AI properties for UT99 stock bots (null for RL bots)
 * @param models     per-model enabled config for AI bots (null for UT99 bots)
 * @param preferredWeapon logical weapon token (see {@link WeaponCatalog}) the weapon-planner
 *                        lane should keep this AI bot holding. Required for AI bots; null for
 *                        UT99 stock bots (they manage their own weapon via {@code ut99Config}).
 */
public record BotConfig(
    boolean enabled,
    boolean active,
    String name,
    int team,
    String role,
    String type,
    BotAppearanceConfig appearance,
    Ut99BotConfig ut99Config,
    Map<String, BotModelConfig> models,
    String preferredWeapon
) {

  public boolean isRl() {
    return "rl".equals(type);
  }

  public boolean isUt99() {
    return "ut99".equals(type);
  }

  /**
   * Returns whether a specific model is enabled for this bot. Only valid for AI (rl) bots. Throws if the model key is not configured.
   */
  public boolean isModelEnabled(String modelKey) {
    if (models == null) {
      throw new IllegalStateException(
          "isModelEnabled called on non-AI bot '" + name + "' (type=" + type + ")");
    }
    BotModelConfig mc = models.get(modelKey);
    if (mc == null) {
      throw new IllegalStateException(
          "Model '" + modelKey + "' not configured in models map for bot '" + name + "'");
    }
    return mc.enabled();
  }

  /**
   * Returns the snapshot spec for a specific model. {@code "current"} means
   * live trainingmodel ONNX; {@code "<modelKey>/<counter>"} pins to a frozen
   * champion. Only valid for AI (rl) bots.
   */
  public String snapshotFor(String modelKey) {
    if (models == null) {
      throw new IllegalStateException(
          "snapshotFor called on non-AI bot '" + name + "' (type=" + type + ")");
    }
    BotModelConfig mc = models.get(modelKey);
    if (mc == null) {
      throw new IllegalStateException(
          "Model '" + modelKey + "' not configured in models map for bot '" + name + "'");
    }
    return mc.snapshot();
  }
}
