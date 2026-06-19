package aiplay.scanners.feature.resolver.teammate;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.PlayerDtoFeatureResolver;

import java.util.HashSet;
import java.util.Set;

/**
 * Dynamic feature component for all teammate slots: teammate{N}_*.
 *
 * Pre-registers feature IDs for slots 0..MAX_SLOTS-1. Only features
 * that appear in features.json are actually used.
 *
 * Feature suffixes are composed from two sources:
 * - Teammate-only suffixes (isAlive, egocentric/distance, pitchBearing)
 * - Shared PlayerDto suffixes from {@link PlayerDtoFeatureResolver}
 *   (collision, velocity, acceleration, viewRotation, hasFlag, etc.) —
 *   adding a feature there automatically makes it available for all teammate slots.
 *
 * Priority 10: runs after EnemyBasicFeatureJsonToDtoConverter (priority 0)
 * has populated dto.teammates[].
 */
@TrainingFeatureComponent(priority = 10)
public class TeammateSlotFeatureComponent implements ITrainingFeature {

    /** Max teammate slots to pre-register. Supports up to 8v8 (7 teammates). */
    public static final int MAX_SLOTS = 7;

    /** Teammate-only suffixes (not in shared PlayerDtoFeatureResolver). */
    private static final String[] TEAMMATE_ONLY_SUFFIXES = {
        "isAlive",
        "relSin", "relCos",
        "forwardDist_norm", "rightDist_norm",
        "distance_norm",
        "pitchBearing_norm",
        "relVelForward_norm", "relVelRight_norm", "relVelUp_norm",
        "relZ_norm",
    };

    /** Boolean suffixes among teammate-only features. */
    private static final String[] TEAMMATE_ONLY_BOOLEAN_SUFFIXES = {
        "isAlive",
    };

    /** All feature suffixes: teammate-only + shared PlayerDto features. */
    static final String[] FEATURE_SUFFIXES;

    private static final Set<String> CACHED_FEATURE_IDS;
    private static final Set<String> CACHED_BOOLEAN_FEATURES;

    static {
        String[] shared = PlayerDtoFeatureResolver.SHARED_SUFFIXES;
        FEATURE_SUFFIXES = new String[TEAMMATE_ONLY_SUFFIXES.length + shared.length];
        System.arraycopy(TEAMMATE_ONLY_SUFFIXES, 0, FEATURE_SUFFIXES, 0, TEAMMATE_ONLY_SUFFIXES.length);
        System.arraycopy(shared, 0, FEATURE_SUFFIXES, TEAMMATE_ONLY_SUFFIXES.length, shared.length);

        String[] sharedBools = PlayerDtoFeatureResolver.SHARED_BOOLEAN_SUFFIXES;
        String[] allBools = new String[TEAMMATE_ONLY_BOOLEAN_SUFFIXES.length + sharedBools.length];
        System.arraycopy(TEAMMATE_ONLY_BOOLEAN_SUFFIXES, 0, allBools, 0, TEAMMATE_ONLY_BOOLEAN_SUFFIXES.length);
        System.arraycopy(sharedBools, 0, allBools, TEAMMATE_ONLY_BOOLEAN_SUFFIXES.length, sharedBools.length);

        Set<String> ids = new HashSet<>(MAX_SLOTS * FEATURE_SUFFIXES.length);
        Set<String> bools = new HashSet<>(MAX_SLOTS * allBools.length);
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            String prefix = "teammate" + slot + "_";
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

    private final TeammateSlotFeatureValueResolver resolver = new TeammateSlotFeatureValueResolver();
    private final TeammateSlotRelativeBatchEnricher enricher = new TeammateSlotRelativeBatchEnricher();

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
}
