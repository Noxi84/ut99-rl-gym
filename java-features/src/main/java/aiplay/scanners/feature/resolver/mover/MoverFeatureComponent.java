package aiplay.scanners.feature.resolver.mover;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.HashSet;
import java.util.Set;

/**
 * Auto-discovered feature component for {@code self_mover{N}_*} features.
 * Handles lifts, doors, and platforms from UT99 maps.
 *
 * <p>Priority 11: enricher must run after PlayerPawnBasicFeatureJsonToDtoConverter
 * (priority 10) so playerPawn.location and viewRotation are populated.
 */
@TrainingFeatureComponent(priority = 11)
public class MoverFeatureComponent implements ITrainingFeature {

    public static final String[] MOVER_SUFFIXES = {
        "present",
        "relSin", "relCos",
        "distance_norm",
        "forwardDist_norm", "rightDist_norm",
        "zOffset_norm",
        "onPlatform",
        "isMoving",
        "moveProgress_norm",
        "destZOffset_norm", "destDistance_norm",
        "timeToArrive_norm",
        "travelRange_norm",
    };

    public static final String[] MOVER_BOOLEAN_SUFFIXES = {
        "present",
        "onPlatform",
        "isMoving",
    };

    private static final Set<String> CACHED_FEATURE_IDS;
    private static final Set<String> CACHED_BOOLEAN_FEATURES;

    static {
        Set<String> ids = new HashSet<>(MoverEnricher.MAX_SLOTS * MOVER_SUFFIXES.length);
        Set<String> bools = new HashSet<>(MoverEnricher.MAX_SLOTS * MOVER_BOOLEAN_SUFFIXES.length);
        for (int slot = 0; slot < MoverEnricher.MAX_SLOTS; slot++) {
            String prefix = "self_mover" + slot + "_";
            for (String suffix : MOVER_SUFFIXES) {
                ids.add(prefix + suffix);
            }
            for (String suffix : MOVER_BOOLEAN_SUFFIXES) {
                bools.add(prefix + suffix);
            }
        }
        CACHED_FEATURE_IDS = Set.copyOf(ids);
        CACHED_BOOLEAN_FEATURES = Set.copyOf(bools);
    }

    private final MoverFeatureValueResolver resolver = new MoverFeatureValueResolver();
    private final MoverEnricher enricher = new MoverEnricher();
    private final MoverJsonToDtoConverter converter = new MoverJsonToDtoConverter();

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
}
