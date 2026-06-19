package aiplay.runtime.context;

/**
 * Thread-local override of the active map key for map-normalized feature resolution.
 *
 * <p>At runtime the active map comes from {@code gameplay.mapName} in the config — a single
 * value per process. During CSV generation however, recordings from multiple different maps
 * can be processed back-to-back in one JVM; each recording's {@code MapInfo.MapName} should
 * drive the normalization (so {@link ActiveMapConfigResolver} and
 * {@link MapSpawnPointsResolver} return the correct per-map values).
 *
 * <p>Usage:
 * <pre>{@code
 * String prev = ActiveMapContext.get();
 * ActiveMapContext.set(gameState.MapInfo.MapName);
 * try { ... } finally { ActiveMapContext.set(prev); }
 * }</pre>
 */
public final class ActiveMapContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActiveMapContext() {}

    /** Returns the currently-set map key (already normalized), or null if no override is active. */
    public static String get() {
        return CURRENT.get();
    }

    /** Set the current map key. The raw name is normalized (query params and class suffix stripped). */
    public static void set(String rawMapName) {
        if (rawMapName == null) {
            CURRENT.remove();
            return;
        }
        String normalized = normalize(rawMapName);
        if (normalized.isEmpty()) {
            CURRENT.remove();
        } else {
            CURRENT.set(normalized);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Normalize a raw map name to the key used in {@code gameplay.json → maps.*}. Strips
     * query params ({@code ?game=...}) and optional class suffix ({@code .LevelInfo0}).
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        return s.trim();
    }
}
