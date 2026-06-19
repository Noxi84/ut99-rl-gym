package aiplay.runtime.context;

/**
 * Per-thread player identity storage. Pure {@link ThreadLocal} container — no
 * config dependencies. Convenience lookups that combine identity with config
 * live in {@link aiplay.runtime.identity.IdentityLookups}.
 *
 * <p>Each bot thread must call {@link #setForCurrentThread(String, int, String)}
 * at startup so that converters within that thread resolve the correct bot
 * name, team, and role. Accessors throw if the ThreadLocal is unset —
 * silent fallbacks previously caused cross-bot identity contamination (wrong
 * self-player read from the shared StateFrame).
 *
 * <p>Role is part of the thread-local identity so the active rewardgroup
 * one-hot feature and rewardgroup merge in {@code rewards.json} can be
 * resolved without re-walking the bot config on every frame.
 */
public final class PlayerIdentityContext {

    private static final ThreadLocal<Identity> THREAD_IDENTITY = new ThreadLocal<>();

    private PlayerIdentityContext() {}

    public static void setForCurrentThread(String playerName, int team, String role) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        THREAD_IDENTITY.set(new Identity(playerName, team, role.trim()));
    }

    public static void init(String playerName, int team, String role) {
        setForCurrentThread(playerName, team, role);
    }

    public static String effectivePlayerName() {
        return requireIdentity().name;
    }

    public static int effectivePlayerTeam() {
        return requireIdentity().team;
    }

    public static String effectiveRole() {
        return requireIdentity().role;
    }

    /**
     * Returns the current thread's player name, or {@code null} when no
     * identity is set. Unlike {@link #effectivePlayerName()}, this tolerates an
     * unset ThreadLocal — for callers that need to behave reasonably during
     * startup (before bot threads exist) or for diagnostics.
     */
    public static String tryEffectivePlayerName() {
        Identity id = THREAD_IDENTITY.get();
        return id != null ? id.name : null;
    }

    private static Identity requireIdentity() {
        Identity id = THREAD_IDENTITY.get();
        if (id == null) {
            throw new IllegalStateException(
                "PlayerIdentityContext ThreadLocal not set on thread '"
                    + Thread.currentThread().getName()
                    + "'. Every bot thread must call setForCurrentThread() at startup — "
                    + "a missing call previously fell back to the default player and "
                    + "caused cross-bot identity contamination.");
        }
        return id;
    }

    /**
     * Clears the identity for the current thread.
     */
    public static void clearForCurrentThread() {
        THREAD_IDENTITY.remove();
    }

    private static final ThreadLocal<java.util.Map<String, String>> PREDICTOR_KEYS = ThreadLocal.withInitial(java.util.HashMap::new);

    public static void setPredictorKey(String modelKey, String predictorKey) {
        PREDICTOR_KEYS.get().put(modelKey, predictorKey);
    }

    public static String predictorKey(String modelKey) {
        return PREDICTOR_KEYS.get().getOrDefault(modelKey, modelKey);
    }

    private record Identity(String name, int team, String role) {}
}
