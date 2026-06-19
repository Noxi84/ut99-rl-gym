package aiplay.config;

import aiplay.config.model.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class ModelConfigRepository {

  private static final ModelConfigRepository SHARED = new ModelConfigRepository();

  public static ModelConfigRepository shared() {
    return SHARED;
  }

  private final ConcurrentHashMap<String, ModelConfig> cache = new ConcurrentHashMap<>();

  public ModelConfig get(String modelKey) {
    return cache.computeIfAbsent(modelKey, this::load);
  }

  private ModelConfig load(String modelKey) {
    JsonNode root = PropertyReaderUtils.getSubtree("/models/" + modelKey);
    if (root == null || !root.isObject()) {
      throw new IllegalStateException(
          "No model config found at /models/" + modelKey + " in config");
    }

    ModelRuntimeConfig runtime = parseRuntime(root.path("runtime"));
    ModelTrainingCsvConfig trainingCsv = parseTrainingCsv(root.path("training_csv"));
    ModelFeaturesConfig features = parseFeatures(root.path("features"), modelKey);

    validate(modelKey, features, trainingCsv, runtime);

    return new ModelConfig(modelKey, runtime, trainingCsv, features);
  }

  private static void validate(String modelKey, ModelFeaturesConfig features,
      ModelTrainingCsvConfig csv, ModelRuntimeConfig runtime) {
    if (features.inputFeatures().isEmpty()) {
      throw new IllegalStateException(modelKey + ": feature_groups / timeline_features is empty");
    }
    if (features.targetFeatures().isEmpty()) {
      throw new IllegalStateException(modelKey + ": target_features is empty");
    }
    if (!features.hasTemporalGroups() && csv.numberOfColumns() <= 0) {
      throw new IllegalStateException(modelKey + ": number_of_columns must be > 0 (or use feature_groups)");
    }
    if (runtime.predictionFps() <= 0) {
      throw new IllegalStateException(modelKey + ": prediction_fps must be > 0");
    }
  }

  // ===== section parsers =====

  private static ModelRuntimeConfig parseRuntime(JsonNode n) {
    return new ModelRuntimeConfig(
        requireInt(n, "prediction_fps"),
        requireInt(n, "dodge_cooldown_ms"),
        requireInt(n, "idle_duration_window_ms"),
        requireDouble(n, "idle_enter_threshold"),
        requireDouble(n, "idle_exit_threshold")
    );
  }

  private static ModelTrainingCsvConfig parseTrainingCsv(JsonNode n) {
    return new ModelTrainingCsvConfig(
        requireBoolean(n, "enabled"),
        requireInt(n, "csv_fps"),
        requireInt(n, "number_of_columns"),
        parseStringList(n.path("state_bucket_key")),
        requireInt(n, "target_lookahead_frames"),
        requireInt(n, "bc_yaw_target_scale"),
        requireInt(n, "bc_pitch_target_scale")
    );
  }

  private static ModelFeaturesConfig parseFeatures(JsonNode n, String modelKey) {
    List<String> legacyFeatures = parseStringList(n.path("timeline_features"));
    List<FeatureGroup> featureGroups = parseFeatureGroups(n.path("feature_groups"), modelKey);
    List<String> targetFeatures = parseTargetFeatureNames(n.path("target_features"), modelKey);
    // Phase 2: aux_target_features is optional — empty list when missing.
    JsonNode auxNode = n.path("aux_target_features");
    List<String> auxTargetFeatures = (auxNode == null || auxNode.isMissingNode() || !auxNode.isArray())
            ? List.of()
            : parseTargetFeatureNames(auxNode, modelKey);
    return new ModelFeaturesConfig(legacyFeatures, featureGroups, targetFeatures, auxTargetFeatures);
  }

  /**
   * Parses {@code target_features} as a list of {@code {"name": ..., "type": ...}}
   * objects and returns the names in declaration order. The {@code type} field
   * (steering | continuous | binary) drives the SAC export sanity probe in
   * Python and is intentionally ignored here — Java only needs the ordered name
   * list to build the model's output contract.
   */
  private static List<String> parseTargetFeatureNames(JsonNode n, String modelKey) {
    if (n == null || n.isMissingNode() || !n.isArray()) {
      return List.of();
    }
    List<String> names = new java.util.ArrayList<>(n.size());
    for (JsonNode entry : n) {
      if (!entry.isObject() || !entry.has("name")) {
        throw new IllegalStateException(
            modelKey + "/features.json: target_features entries must be objects"
                + " with a 'name' key (and a 'type' key consumed by the SAC probe);"
                + " got: " + entry);
      }
      names.add(entry.get("name").asText());
    }
    return Collections.unmodifiableList(names);
  }

  private static List<FeatureGroup> parseFeatureGroups(JsonNode n, String modelKey) {
    if (n == null || n.isMissingNode() || !n.isArray() || n.size() == 0) {
      return null;
    }
    List<FeatureGroup> groups = new java.util.ArrayList<>();
    for (JsonNode groupNode : n) {
      List<String> features = parseFeatureGroupFeatures(groupNode, modelKey);
      int firstFrames = requireInt(groupNode, "first_frames");
      int lastFrames = requireInt(groupNode, "last_frames");
      groups.add(new FeatureGroup(features, firstFrames, lastFrames));
    }
    return Collections.unmodifiableList(groups);
  }

  private static List<String> parseFeatureGroupFeatures(JsonNode groupNode, String modelKey) {
    String featuresFrom = optionalString(groupNode, "features_from");
    if ("rewardgroups".equals(featuresFrom)) {
      return RewardGroupConfig.featureNames(modelKey);
    }
    if (featuresFrom != null) {
      throw new IllegalStateException(modelKey + "/features.json: unsupported features_from='"
          + featuresFrom + "' (supported: rewardgroups)");
    }
    return parseStringList(groupNode.path("features"));
  }

  // ===== helpers (strict — no silent defaults; CLAUDE.md no-fallback rule) =====

  private static List<String> parseStringList(JsonNode n) {
    if (n == null || n.isMissingNode() || !n.isArray()) {
      return List.of();
    }
    return Collections.unmodifiableList(
        StreamSupport.stream(n.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toList())
    );
  }

  private static int requireInt(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isNumber()) {
      throw new IllegalStateException("model config: missing required int field '" + field + "'");
    }
    return n.asInt();
  }

  private static double requireDouble(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isNumber()) {
      throw new IllegalStateException("model config: missing required double field '" + field + "'");
    }
    return n.asDouble();
  }

  private static boolean requireBoolean(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isBoolean()) {
      throw new IllegalStateException("model config: missing required boolean field '" + field + "'");
    }
    return n.asBoolean();
  }

  /** Returns null if absent; used for genuinely optional discriminator fields. */
  private static String optionalString(JsonNode parent, String field) {
    if (parent == null) return null;
    JsonNode n = parent.path(field);
    return n.isTextual() ? n.asText() : null;
  }
}
