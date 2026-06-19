package aiplay.recordlauncher.x11;

import java.nio.file.Files;
import java.nio.file.Path;

/** Small helper to resolve external tool binaries even when PATH differs (IDE, service, etc.). */
public final class ExternalTools {

  private static final String ENV_XDOTOOL = "UT99_XDOTOOL";
  private static final String SYS_XDOTOOL = "ut99.xdotool";

  private ExternalTools() {
  }

  public static String xdotool() {
    String sys = System.getProperty(SYS_XDOTOOL);
    if (sys != null && !sys.isBlank()) {
      return sys.trim();
    }

    String env = System.getenv(ENV_XDOTOOL);
    if (env != null && !env.isBlank()) {
      return env.trim();
    }

    Path p1 = Path.of("/usr/bin/xdotool");
    if (Files.isExecutable(p1)) {
      return p1.toString();
    }
    Path p2 = Path.of("/bin/xdotool");
    if (Files.isExecutable(p2)) {
      return p2.toString();
    }

    return "xdotool";
  }

}
