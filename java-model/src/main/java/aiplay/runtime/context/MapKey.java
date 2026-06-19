package aiplay.runtime.context;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.GameStateDto;

/**
 * Resolves the active map key for config lookups.
 *
 * <p>Single source of truth for the fallback chain:
 * {@link ActiveMapContext} override → {@code gameplay.mapName} → hard default.
 *
 * <p>Resolvers in {@code aiplay.runtime.config.*} take a {@code mapKey} parameter
 * explicitly; callers use {@link #active()} (or {@link #fromFrame}) to get one.
 * Keeping the fallback here means the config-tier resolvers stay pure (no hidden
 * {@code ThreadLocal} reads) and testable.
 */
public final class MapKey {

    private static final String DEFAULT_MAP = "CTF-andACTION";

    private MapKey() {}

    /**
     * Active map key, normalized. Returns the {@link ActiveMapContext} override
     * if set, otherwise {@code gameplay.mapName} from global config, otherwise
     * the hard default.
     */
    public static String active() {
        String override = ActiveMapContext.get();
        if (override != null && !override.isBlank()) return override;
        String raw = GlobalConfigRepository.shared().gameplay().mapName();
        String normalized = ActiveMapContext.normalize(raw);
        return normalized.isEmpty() ? DEFAULT_MAP : normalized;
    }

    /**
     * Map key from a state frame's {@code mapInfo.mapName}, falling back to
     * {@link #active()} if absent. Used in feature-resolution code paths that
     * already carry a {@link GameStateDto}.
     */
    public static String fromFrame(GameStateDto frame) {
        if (frame != null && frame.mapInfo != null
                && frame.mapInfo.mapName != null && !frame.mapInfo.mapName.isBlank()) {
            String normalized = ActiveMapContext.normalize(frame.mapInfo.mapName);
            if (!normalized.isEmpty()) return normalized;
        }
        return active();
    }
}
