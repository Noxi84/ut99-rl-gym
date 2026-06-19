package aiplay.shared.movement;

import aiplay.rl.MovementPrimitive;

import java.util.concurrent.atomic.AtomicReference;

public class MovementIntentBus {
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
