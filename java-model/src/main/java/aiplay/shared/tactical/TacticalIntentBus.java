package aiplay.shared.tactical;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bus for tactical spatial constraint intents. Written by MissionController,
 * read by the movement constraint applier and feature resolvers.
 */
public class TacticalIntentBus {
    private final AtomicReference<TacticalIntent> last =
        new AtomicReference<>(TacticalIntent.unconstrained(0L));

    public void publish(TacticalIntent intent) {
        if (intent == null) return;
        last.set(intent);
    }

    public TacticalIntent latest() {
        return last.get();
    }
}
