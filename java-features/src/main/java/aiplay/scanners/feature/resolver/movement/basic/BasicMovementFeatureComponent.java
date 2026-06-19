package aiplay.scanners.feature.resolver.movement.basic;

import aiplay.scanners.feature.*;

import java.util.Set;

@TrainingFeatureComponent(priority = 10)
public class BasicMovementFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
            "moveIdle",
            "bDuck",
            "bJump"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
            "moveIdle",
            "bDuck",
            "bJump"
    );

    private BasicMovementFeatureValueResolver featureValueResolver = new BasicMovementFeatureValueResolver();
    private BasicMovementFeatureLogger featureLogger = new BasicMovementFeatureLogger();
    private BasicMovementFeatureJsonToDtoConverter jsonToDtoConverter = new BasicMovementFeatureJsonToDtoConverter();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return BOOLEAN_FEATURES;
    }

    @Override
    public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
        return jsonToDtoConverter;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return featureValueResolver;
    }

    @Override
    public TrainingFeatureLogger getTrainingFeatureLogger() {
        return featureLogger;
    }
}
