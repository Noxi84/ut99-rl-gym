package aiplay.scanners.feature.resolver.playerpawn.yawangularvelocity;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Provides the yawAngularVelocity_norm feature: normalized yaw turn rate
 * between consecutive frames. Tells the movement model how fast the view
 * is currently rotating, enabling it to anticipate heading changes.
 */
@TrainingFeatureComponent(priority = 11)
public class YawAngularVelocityFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of("yawAngularVelocity_norm");

    private final YawAngularVelocityEnricher enricher = new YawAngularVelocityEnricher();

    private final TrainingFeatureValueResolver resolver = (featureId, f) -> {
        if ("yawAngularVelocity_norm".equals(featureId) && f != null && f.playerPawn != null) {
            return f.playerPawn.yawAngularVelocity_norm;
        }
        return null;
    };

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
