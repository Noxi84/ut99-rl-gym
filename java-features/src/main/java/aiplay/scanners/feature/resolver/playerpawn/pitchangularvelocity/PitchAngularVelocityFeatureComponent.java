package aiplay.scanners.feature.resolver.playerpawn.pitchangularvelocity;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Provides the pitchAngularVelocity_norm feature: normalized pitch turn rate
 * between consecutive frames. Tells the movement/shooting model how fast the
 * vertical view is currently changing, enabling it to anticipate pitch dynamics.
 */
@TrainingFeatureComponent(priority = 12)
public class PitchAngularVelocityFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of("pitchAngularVelocity_norm");

    private final PitchAngularVelocityEnricher enricher = new PitchAngularVelocityEnricher();

    private final TrainingFeatureValueResolver resolver = (featureId, f) -> {
        if ("pitchAngularVelocity_norm".equals(featureId) && f != null && f.playerPawn != null) {
            return f.playerPawn.pitchAngularVelocity_norm;
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
