package aiplay.config.model;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolveert rewardgroup-feature-id's per model en koppelt de bot-role aan de
 * bijbehorende groupkey.
 *
 * <p>De {@code rewards.json}-structuur bevat onder {@code rewardgroups}:
 *
 * <ul>
 *   <li>{@code default} — basis-blueprint (geleverd aan {@link aiplay.rl.rewards.catalog.json.JsonRewardCatalog});</li>
 *   <li>{@code rewardgroup0..N} — concrete groups met een verplicht {@code name}-veld
 *       (selector/displaynaam) en een optionele {@code rewards}-deelblok met overrides.</li>
 * </ul>
 *
 * <p>De feature-id is de groupkey ({@code rewardgroup0}, ...) — gebruikt door
 * {@link aiplay.scanners.feature.resolver.rewardgroup.RewardGroupFeatureComponent} voor one-hot
 * encoding. Reward-weight resolutie zelf gebeurt in {@code JsonRewardCatalog}, niet hier.
 */
public final class RewardGroupConfig {

  private RewardGroupConfig() {}

  public static List<String> featureNames(String modelKey) {
    return groupEntries(modelKey).stream().map(GroupEntry::featureName).toList();
  }

  public static Set<String> featureNamesAcrossModels() {
    JsonNode models = PropertyReaderUtils.getSubtree("/models");
    if (models == null || !models.isObject()) {
      return Set.of();
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    models.fields().forEachRemaining(entry -> names.addAll(featureNames(entry.getKey())));
    return Collections.unmodifiableSet(names);
  }

  /**
   * Resolves the feature-id (groupkey) for the given role. The role must
   * match exactly one rewardgroup's {@code name} (case- and punctuation-
   * insensitive) — failure to match crashes loudly so a misconfigured bot
   * never silently lands in the wrong reward group.
   */
  public static String activeFeatureName(String modelKey, String selector) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException(
          "RewardGroupConfig requires a non-blank role selector");
    }
    List<GroupEntry> groups = groupEntries(modelKey);
    String normalized = normalizeSelector(selector);
    for (GroupEntry entry : groups) {
      if (entry.matches(normalized)) {
        return entry.featureName();
      }
    }
    throw new IllegalStateException(
        "rewards.json ("
            + modelKey
            + "): no rewardgroup matches role '"
            + selector
            + "'. Available rewardgroups: "
            + groups.stream().map(GroupEntry::describe).toList());
  }

  private static List<GroupEntry> groupEntries(String modelKey) {
    JsonNode rewardsCfg = rewardsConfig(modelKey);
    JsonNode rewardgroups = rewardsCfg.path("rewardgroups");
    if (rewardgroups.isMissingNode() || !rewardgroups.isObject()) {
      throw new IllegalStateException(
          "rewards.json (" + modelKey + "): missing 'rewardgroups' object");
    }
    ArrayList<GroupEntry> groups = new ArrayList<>();
    rewardgroups
        .fields()
        .forEachRemaining(
            field -> {
              String key = field.getKey();
              if ("default".equals(key)) {
                return;
              }
              JsonNode node = field.getValue();
              if (!node.isObject()) {
                throw new IllegalStateException(
                    "rewards.json ("
                        + modelKey
                        + "): rewardgroups."
                        + key
                        + " must be an object");
              }
              String groupName =
                  textRequired(
                      node, "name", "rewards.json (" + modelKey + "): rewardgroups." + key);
              groups.add(
                  new GroupEntry(key, key, groupName, selectorsForGroup(key, groupName, node)));
            });
    if (groups.isEmpty()) {
      throw new IllegalStateException(
          "rewards.json (" + modelKey + "): rewardgroups must contain at least one non-default group");
    }
    return List.copyOf(groups);
  }

  private static JsonNode rewardsConfig(String modelKey) {
    if (modelKey == null || modelKey.isBlank()) {
      throw new IllegalArgumentException("modelKey must not be blank");
    }
    JsonNode rewardsCfg = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/rewards");
    if (rewardsCfg == null || !rewardsCfg.isObject()) {
      throw new IllegalStateException("No rewards.json found for model: " + modelKey);
    }
    return rewardsCfg;
  }

  private static String textRequired(JsonNode node, String field, String path) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException(path + "." + field + " must be a non-empty string");
    }
    return value.asText();
  }

  private static Set<String> selectorsForGroup(String key, String featureName, JsonNode node) {
    LinkedHashSet<String> selectors = new LinkedHashSet<>(selectorsFrom(key, featureName));
    addSelector(selectors, node.path("selector"));
    addSelector(selectors, node.path("role"));
    addSelectors(selectors, node.path("selectors"));
    addSelectors(selectors, node.path("roles"));
    return Collections.unmodifiableSet(selectors);
  }

  private static Set<String> selectorsFrom(String key, String featureName) {
    LinkedHashSet<String> selectors = new LinkedHashSet<>();
    addSelector(selectors, key);
    addSelector(selectors, featureName);
    return Collections.unmodifiableSet(selectors);
  }

  private static void addSelectors(Set<String> selectors, JsonNode values) {
    if (values == null || !values.isArray()) {
      return;
    }
    values.forEach(value -> addSelector(selectors, value));
  }

  private static void addSelector(Set<String> selectors, JsonNode value) {
    if (value != null && value.isTextual()) {
      addSelector(selectors, value.asText());
    }
  }

  private static void addSelector(Set<String> selectors, String value) {
    String normalized = normalizeSelector(value);
    if (!normalized.isBlank()) {
      selectors.add(normalized);
    }
  }

  private static String normalizeSelector(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  private record GroupEntry(String key, String featureName, String name, Set<String> selectors) {
    boolean matches(String normalizedSelector) {
      return selectors.contains(normalizedSelector);
    }

    String describe() {
      return key + "(name=" + name + ")";
    }
  }
}
