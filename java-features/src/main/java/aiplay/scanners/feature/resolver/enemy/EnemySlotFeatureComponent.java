package aiplay.scanners.feature.resolver.enemy;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.TrainingFeatureLogger;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.PlayerDtoFeatureResolver;
import aiplay.scanners.feature.resolver.enemy.enemyhasflag.EnemyBasicFeatureJsonToDtoConverter;
import aiplay.scanners.feature.resolver.enemy.enemyhasflag.EnemyBasicFeatureLogger;
import java.util.HashSet;
import java.util.Set;

/**
 * Dynamic feature component for all enemy slots: enemy{N}_*.
 * <p>
 * Pre-registers feature IDs for slots 0..MAX_SLOTS-1. Only features that appear in features.json are actually used. The resolver parses the slot index from the feature ID at runtime.
 * <p>
 * Feature suffixes are composed from two sources: - Enemy-only suffixes (isAlive, visible, egocentric, dodge) - Shared PlayerDto suffixes from {@link PlayerDtoFeatureResolver} (collision, velocity, acceleration, viewRotation, etc.) — adding a feature there automatically makes it available for all enemy slots.
 * <p>
 * Priority 10: runs after EnemyBasicFeatureJsonToDtoConverter (priority 0) has populated dto.enemies[].
 */
@TrainingFeatureComponent(priority = 10)
public class EnemySlotFeatureComponent implements ITrainingFeature {

  /**
   * Max enemy slots to pre-register. Supports up to 8v8 UT99 (7 enemies).
   */
  public static final int MAX_SLOTS = 7;

  /**
   * Enemy-only suffixes (not in shared PlayerDtoFeatureResolver).
   * visible + dodge features are shared — see {@link PlayerDtoFeatureResolver#SHARED_SUFFIXES}.
   */
  private static final String[] ENEMY_ONLY_SUFFIXES = {
      // Status
      "isAlive",
      // Egocentric (relative to bot's view direction)
      "relSin", "relCos",
      "forwardDist_norm", "rightDist_norm",
      "distance_norm",
      "pitchBearing_norm",
      "aimAlignmentDot_norm",
      // Egocentric velocity — target velocity in bot view-frame (lead-aim signal)
      "relVelForward_norm", "relVelRight_norm", "relVelUp_norm",
      // Egocentric vertical offset (map-onafhankelijk; tanh-geschaald op 512 UU)
      "relZ_norm",
  };

  /**
   * Boolean suffixes among enemy-only features.
   */
  private static final String[] ENEMY_ONLY_BOOLEAN_SUFFIXES = {
      "isAlive",
  };

  /**
   * All feature suffixes: enemy-only + shared PlayerDto features.
   */
  static final String[] FEATURE_SUFFIXES;

  private static final Set<String> CACHED_FEATURE_IDS;
  private static final Set<String> CACHED_BOOLEAN_FEATURES;

  static {
    // Build combined feature suffixes
    String[] shared = PlayerDtoFeatureResolver.SHARED_SUFFIXES;
    FEATURE_SUFFIXES = new String[ENEMY_ONLY_SUFFIXES.length + shared.length];
    System.arraycopy(ENEMY_ONLY_SUFFIXES, 0, FEATURE_SUFFIXES, 0, ENEMY_ONLY_SUFFIXES.length);
    System.arraycopy(shared, 0, FEATURE_SUFFIXES, ENEMY_ONLY_SUFFIXES.length, shared.length);

    // Build combined boolean suffixes
    String[] sharedBools = PlayerDtoFeatureResolver.SHARED_BOOLEAN_SUFFIXES;
    String[] allBools = new String[ENEMY_ONLY_BOOLEAN_SUFFIXES.length + sharedBools.length];
    System.arraycopy(ENEMY_ONLY_BOOLEAN_SUFFIXES, 0, allBools, 0, ENEMY_ONLY_BOOLEAN_SUFFIXES.length);
    System.arraycopy(sharedBools, 0, allBools, ENEMY_ONLY_BOOLEAN_SUFFIXES.length, sharedBools.length);

    // Build feature ID and boolean sets for all slots
    Set<String> ids = new HashSet<>(MAX_SLOTS * FEATURE_SUFFIXES.length);
    Set<String> bools = new HashSet<>(MAX_SLOTS * allBools.length);
    for (int slot = 0; slot < MAX_SLOTS; slot++) {
      String prefix = "enemy" + slot + "_";
      for (String suffix : FEATURE_SUFFIXES) {
        ids.add(prefix + suffix);
      }
      for (String suffix : allBools) {
        bools.add(prefix + suffix);
      }
    }
    CACHED_FEATURE_IDS = Set.copyOf(ids);
    CACHED_BOOLEAN_FEATURES = Set.copyOf(bools);
  }

  private final EnemySlotFeatureValueResolver resolver = new EnemySlotFeatureValueResolver();
  private final EnemySlotRelativeBatchEnricher enricher = new EnemySlotRelativeBatchEnricher();
  private final EnemyBasicFeatureJsonToDtoConverter converter = new EnemyBasicFeatureJsonToDtoConverter();
  private final EnemyBasicFeatureLogger logger = new EnemyBasicFeatureLogger();

  @Override
  public Set<String> getFeatureIds() {
    return CACHED_FEATURE_IDS;
  }

  @Override
  public Set<String> getBooleanFeatures() {
    return CACHED_BOOLEAN_FEATURES;
  }

  @Override
  public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
    return resolver;
  }

  @Override
  public TrainingFeatureEnricher getTrainingFeatureEnricher() {
    return enricher;
  }

  @Override
  public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
    return converter;
  }

  @Override
  public TrainingFeatureLogger getTrainingFeatureLogger() {
    return logger;
  }
}
