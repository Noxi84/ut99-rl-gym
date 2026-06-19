package aiplay.shared.view;

import java.util.concurrent.atomic.AtomicReference;

public class ViewTurnIntentBus {

    private final AtomicReference<ViewTurnIntent> last =
        new AtomicReference<>(new ViewTurnIntent(0f, 0f, 0f, 0L));

    public void publish(ViewTurnIntent intent) {
        if (intent == null) {
            return;
        }
        last.set(intent);
    }

    public ViewTurnIntent latest() {
        return last.get();
    }
}
