package aiplay.scanners.feature.resolver.mapidentity;

import aiplay.runtime.config.MapIdentityResolver;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.MapKey;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

@TrainingFeatureComponent(priority = 10)
public class MapIdentityFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of("map_id");

    private final TrainingFeatureValueResolver resolver = new TrainingFeatureValueResolver() {
        @Override
        public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto frame) {
            if ("map_id".equals(featureId)) {
                return (float) MapIdentityResolver.resolveMapId(MapKey.fromFrame(frame));
            }
            return null;
        }
    };

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
