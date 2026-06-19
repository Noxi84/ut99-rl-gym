package aiplay.recordlauncher.x11;

/**
 * Factory for ProcessBuilder that injects the correct DISPLAY env var
 * from {@link CurrentDisplay} ThreadLocal. In single-instance (legacy) mode,
 * no DISPLAY override is set and the process inherits from the parent.
 */
public final class DisplayAwareProcessBuilder {

  private DisplayAwareProcessBuilder() {}

  public static ProcessBuilder create(String... command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    String display = CurrentDisplay.get();
    if (display != null && !display.isEmpty()) {
      pb.environment().put("DISPLAY", display);
    }
    return pb;
  }
}
