package aiplay.config.global;

/**
 * Per-model configuration for an AI bot.
 *
 * @param enabled  true = this model is active for this bot (inference runs).
 *                 false = this model is disabled for this bot.
 * @param snapshot Which weights to load. {@code "current"} means the live
 *                 trainingmodel ONNX. {@code "<modelKey>/<counter>"} pins
 *                 the bot to a frozen champion snapshot — used by self-play
 *                 to pit the current policy against an older one. Required
 *                 (no fallback): missing field crashes config load.
 */
public record BotModelConfig(
    boolean enabled,
    String snapshot
) {
}
