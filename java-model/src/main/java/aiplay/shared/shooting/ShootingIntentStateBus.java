package aiplay.shared.shooting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-session view of the latest shooting decision. This is intentionally separate from
 * {@link ShootIntentBus}: command control owns the bus instance, while feature resolution only
 * receives a session id.
 */
public final class ShootingIntentStateBus {

    private static final ShootIntent EMPTY = new ShootIntent(false, false, 0L);
    private static final ConcurrentHashMap<String, AtomicReference<ShootIntent>> PER_SESSION =
        new ConcurrentHashMap<>();

    private ShootingIntentStateBus() {}

    public static void publish(String sessionId, ShootIntent intent) {
        if (sessionId == null || intent == null) return;
        PER_SESSION.computeIfAbsent(sessionId, k -> new AtomicReference<>(EMPTY)).set(intent);
    }

    public static ShootIntent latest(String sessionId) {
        if (sessionId == null) return EMPTY;
        AtomicReference<ShootIntent> ref = PER_SESSION.get(sessionId);
        return ref != null ? ref.get() : EMPTY;
    }

    public static void unregisterSession(String sessionId) {
        if (sessionId == null) return;
        PER_SESSION.remove(sessionId);
    }
}
