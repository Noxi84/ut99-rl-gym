package aiplay.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-level config loader and cache. Loads the config tree from the split
 * structure (resources/config/ + resources/models/).
 *
 * Application code should not use this class directly — use GlobalConfigRepository
 * or ModelConfigRepository instead.
 */
public final class PropertyReaderUtils {

  private static final String ENV_VAR_PROJECT_ROOT = "UT99_PROJECT_ROOT";

  private static final AtomicReference<JsonNode> ROOT = new AtomicReference<>(null);
  private static final Object LOAD_LOCK = new Object();

  private PropertyReaderUtils() {
    throw new AssertionError("Utility class");
  }

  /**
   * Returns the raw JsonNode subtree at the given JSON pointer path.
   */
  public static JsonNode getSubtree(String jsonPointer) {
    JsonNode node = root().at(jsonPointer);
    if (node == null || node.isMissingNode()) {
      return null;
    }
    return node;
  }

  private static JsonNode root() {
    JsonNode cached = ROOT.get();
    if (cached != null) return cached;

    synchronized (LOAD_LOCK) {
      cached = ROOT.get();
      if (cached != null) return cached;

      File projectRoot = getProjectRoot();
      JsonNode splitRoot = aiplay.config.RootConfigLoader.tryLoadSplit(projectRoot);
      if (splitRoot != null) {
        ROOT.set(splitRoot);
        return splitRoot;
      }

      throw new IllegalStateException(
          "Split config structure not found under " + projectRoot.getAbsolutePath()
          + "/resources/config/ + /resources/models/. "
          + "Ensure the split config exists.");
    }
  }

  /**
   * Project-root op disk (UT99_PROJECT_ROOT env → cwd-detectie). Publiek voor code die
   * grote data-assets naast de merged config-tree van disk leest (bv. geodesic fields
   * onder resources/config/maps/) zonder ze in de config-tree te laden.
   */
  public static File projectRoot() {
    return getProjectRoot();
  }

  private static File getProjectRoot() {
    String configuredRoot = System.getenv(ENV_VAR_PROJECT_ROOT);
    if (configuredRoot != null && !configuredRoot.isBlank()) {
      return new File(configuredRoot.trim());
    }

    File userDir = new File(System.getProperty("user.dir"));
    if (new File(userDir, "resources").isDirectory() || new File(userDir, "train").isDirectory()) {
      return userDir;
    }

    File parent = userDir.getParentFile();
    if (parent != null) {
      if (new File(parent, "resources").isDirectory() || new File(parent, "train").isDirectory()) {
        return parent;
      }
    }

    return userDir;
  }
}
