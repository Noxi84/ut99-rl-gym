package aiplay.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads the split config structure (resources/config/ + resources/models/)
 * and merges it into a single JsonNode root.
 *
 * Returns null only when the split structure doesn't exist at all.
 * Throws RuntimeException if the structure exists but is malformed or unreadable,
 * so a broken split config is never silently masked by a stale monolithic fallback.
 *
 * Model directories are governed by index.json — only models listed there are loaded.
 */
public final class RootConfigLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String[] CONFIG_SECTIONS = {"files", "runtime", "roles"};

  /**
   * Config files whose filename uses kebab-case but should appear under a snake_case top-level
   * key in the merged tree (so {@code GlobalConfigRepository} can read them via
   * {@code PropertyReaderUtils.getSubtree("/pickup_types")}).
   */
  private static final Map<String, String> KEBAB_CONFIG_FILES = Map.of(
      "pickup-types", "pickup_types",
      "ammo-deadlock-guard", "ammo_deadlock_guard"
  );

  private RootConfigLoader() {}

  /**
   * Try to load from split files. Returns null if the split structure doesn't exist.
   * Throws RuntimeException if the structure exists but cannot be loaded.
   */
  public static JsonNode tryLoadSplit(File projectRoot) {
    File configDir = new File(projectRoot, "resources/config");
    // UT99_MODEL_CONFIG_DIR overrides the models directory for variant config overlays.
    // When set, model configs are loaded from that directory instead of resources/models/.
    // Global configs (gameplay, runtime, files, etc.) still load from resources/config/.
    String modelConfigOverride = System.getenv("UT99_MODEL_CONFIG_DIR");
    File modelsDir = (modelConfigOverride != null && !modelConfigOverride.isBlank())
        ? new File(modelConfigOverride)
        : new File(projectRoot, "resources/models");
    if (!configDir.isDirectory() || !modelsDir.isDirectory()) {
      return null;
    }

    File gameplayFile = new File(configDir, "gameplay.json");
    if (!gameplayFile.exists()) {
      return null;
    }

    // Split structure detected — from here, errors are fatal (no silent fallback)
    try {
      ObjectNode root = MAPPER.createObjectNode();

      // 1. gameplay.json → merge all keys at root
      JsonNode gameplay = MAPPER.readTree(gameplayFile);
      if (gameplay.isObject()) {
        mergeInto(root, (ObjectNode) gameplay);
      }

      // 2. files.json, runtime.json → set as keyed sections
      for (String section : CONFIG_SECTIONS) {
        File sectionFile = new File(configDir, section + ".json");
        if (sectionFile.exists()) {
          root.set(section, MAPPER.readTree(sectionFile));
        }
      }

      // 2a. kebab-case files (pickup-types.json → root.pickup_types)
      for (Map.Entry<String, String> e : KEBAB_CONFIG_FILES.entrySet()) {
        File f = new File(configDir, e.getKey() + ".json");
        if (f.exists()) {
          root.set(e.getValue(), MAPPER.readTree(f));
        }
      }

      // 2b. resources/config/maps/<mapKey>.json → merged under root.maps.<mapKey>
      File mapsDir = new File(configDir, "maps");
      ObjectNode mapsNode = loadMapsDir(mapsDir);
      root.set("maps", mapsNode);

      // 3. models → governed by index.json
      ObjectNode modelsNode = MAPPER.createObjectNode();
      File indexFile = new File(modelsDir, "index.json");
      if (!indexFile.exists()) {
        throw new IOException("models/index.json not found in split config");
      }
      JsonNode index = MAPPER.readTree(indexFile);
      JsonNode modelList = index.path("models");
      if (!modelList.isArray()) {
        throw new IOException("models/index.json: 'models' must be an array");
      }
      for (JsonNode entry : modelList) {
        String modelKey = entry.path("model_key").asText(null);
        if (modelKey == null || modelKey.isBlank()) {
          continue;
        }
        File modelDir = new File(modelsDir, modelKey);
        if (!modelDir.isDirectory()) {
          throw new IOException("Model directory not found: " + modelDir.getAbsolutePath()
              + " (listed in index.json)");
        }
        ObjectNode modelNode = loadModelDir(modelDir, modelKey);
        modelsNode.set(modelKey, modelNode);
      }
      root.set("models", modelsNode);

      return root;
    } catch (IOException e) {
      throw new RuntimeException("Split config structure detected but failed to load: " + e.getMessage(), e);
    }
  }

  /**
   * Load every {@code <mapKey>.json} file under {@code resources/config/maps/} into a
   * single ObjectNode keyed by the filename (without {@code .json}). The directory must
   * exist; an empty directory is allowed (returns an empty node) so a fresh repo can boot
   * before the first {@code extract-map-bounds.sh} run.
   */
  private static ObjectNode loadMapsDir(File mapsDir) throws IOException {
    if (!mapsDir.isDirectory()) {
      throw new IOException("resources/config/maps directory not found: " + mapsDir.getAbsolutePath()
          + " (run scripts/deploy/extract-map-bounds.sh to populate it)");
    }
    ObjectNode out = MAPPER.createObjectNode();
    File[] files = mapsDir.listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return out;
    for (File f : files) {
      String mapKey = f.getName().substring(0, f.getName().length() - ".json".length());
      out.set(mapKey, MAPPER.readTree(f));
    }
    return out;
  }

  private static ObjectNode loadModelDir(File modelDir, String modelKey) throws IOException {
    ObjectNode model = MAPPER.createObjectNode();
    model.put("model_key", modelKey);

    // "probe" en "baseline" zijn joint-specifieke secties
    // (resources/models/rl_pawn/{probe.json,baseline.json}).
    String[] sections = {"runtime", "training_csv", "model", "bc", "sac", "rewards", "features", "promotion", "probe", "baseline"};
    for (String section : sections) {
      File f = new File(modelDir, section + ".json");
      if (f.exists()) {
        model.set(section, MAPPER.readTree(f));
      }
    }

    // extras.json → merge all keys into model root
    File extrasFile = new File(modelDir, "extras.json");
    if (extrasFile.exists()) {
      JsonNode extras = MAPPER.readTree(extrasFile);
      if (extras.isObject()) {
        mergeInto(model, (ObjectNode) extras);
      }
    }

    return model;
  }

  private static void mergeInto(ObjectNode target, ObjectNode source) {
    Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      target.set(entry.getKey(), entry.getValue());
    }
  }
}
