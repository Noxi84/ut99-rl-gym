package aiplay.shared.weapon;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-slot latest-wins bus for the weapon a bot should hold. The weapon-planner
 * lane (low Hz) publishes; the CommandController (controller rate) reads.
 *
 * <p>Default is {@code null} — "no weapon decision yet" — so the CommandController
 * sends nothing until the planner has produced a choice.
 */
public class WeaponSelectIntentBus {

  private final AtomicReference<WeaponSelectIntent> last = new AtomicReference<>(null);

  public void publish(WeaponSelectIntent intent) {
    if (intent == null) return;
    last.set(intent);
  }

  /** Latest intent, or {@code null} if the planner has not chosen yet. */
  public WeaponSelectIntent latest() {
    return last.get();
  }
}
