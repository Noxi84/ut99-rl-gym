package aiplay.recordlauncher.x11;

/**
 * ThreadLocal holder for the X11 DISPLAY name (e.g. ":20").
 * Each bot thread sets this at the start of its tick so that ProcessBuilder calls
 * and xdotool invocations use the correct display without passing it through every method.
 */
public final class CurrentDisplay {

  private static final InheritableThreadLocal<String> DISPLAY = new InheritableThreadLocal<>();

  private CurrentDisplay() {}

  public static void set(String displayName) {
    DISPLAY.set(displayName);
  }

  /** Returns the display name for the current thread, or null if not set (single-instance legacy). */
  public static String get() {
    return DISPLAY.get();
  }

  public static void clear() {
    DISPLAY.remove();
  }
}
