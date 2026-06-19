package aiplay.runtime.identity;

import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.runtime.context.PlayerIdentityContext;

/**
 * Convenience lookups that combine the per-thread player identity
 * ({@link PlayerIdentityContext}) with global configuration ({@link
 * GlobalConfigRepository}).
 *
 * <p>Kept separate from {@link PlayerIdentityContext} so the latter stays a
 * pure {@link ThreadLocal} container without config-tier dependencies.
 */
public final class IdentityLookups {

    private IdentityLookups() {}

    /**
     * BotConfig for the current thread's bot, or {@code null} when no
     * ThreadLocal identity is set (e.g. startup max-FPS calculation) or the
     * configured name no longer matches a bot in {@link GlobalConfigRepository}.
     */
    public static BotConfig currentBotConfig() {
        String name = PlayerIdentityContext.tryEffectivePlayerName();
        if (name == null) return null;
        return GlobalConfigRepository.shared().bots().stream()
            .filter(b -> b.name().equals(name))
            .findFirst()
            .orElse(null);
    }
}
