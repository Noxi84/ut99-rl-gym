package aiplay.shared.shooting;

import java.util.concurrent.atomic.AtomicReference;

public class ShootIntentBus {

    private final AtomicReference<ShootIntent> last =
        new AtomicReference<>(new ShootIntent(false, false, 0L));

    public void publish(ShootIntent intent) {
        if (intent == null) return;
        last.set(intent);
    }

    public ShootIntent latest() {
        return last.get();
    }
}
