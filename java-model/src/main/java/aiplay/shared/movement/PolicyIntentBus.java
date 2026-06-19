package aiplay.shared.movement;

import aiplay.rl.MovementPrimitive;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bus for raw policy intents (written by movement policy executor).
 * CommandController reads from this bus and publishes the effective
 * (post-dwell) intent to MovementIntentBus.
 *
 * Ownership: only the movement policy thread writes here.
 * Only the CommandController thread reads here.
 */
public class PolicyIntentBus {
    private final AtomicReference<MovementIntent> last =
            new AtomicReference<>(new MovementIntent(MovementPrimitive.IDLE, false, false, false, false, 0));

    public void publish(MovementIntent intent) {
        if (intent == null) return;
        last.set(intent);
    }

    public MovementIntent latest() {
        return last.get();
    }
}
