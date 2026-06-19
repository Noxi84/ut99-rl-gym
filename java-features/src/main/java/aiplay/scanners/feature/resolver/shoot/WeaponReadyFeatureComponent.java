package aiplay.scanners.feature.resolver.shoot;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Publiceert de refire-klok van het vastgehouden wapen als feature
 * ({@code weaponReadyIn_norm}, 0 = nu schietbaar .. 1 = ≥1s te gaan) — zie
 * {@link WeaponReadyIncrementalEnricher} voor de reconstructie-semantiek.
 */
@TrainingFeatureComponent(priority = 10)
public class WeaponReadyFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of("weaponReadyIn_norm");

    private final WeaponReadyIncrementalEnricher enricher = new WeaponReadyIncrementalEnricher();

    private final TrainingFeatureValueResolver resolver = new TrainingFeatureValueResolver() {
        @Override
        public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
            if ("weaponReadyIn_norm".equals(featureId)) {
                return (f.playerPawn != null) ? f.playerPawn.weaponReadyInNorm : 0.0f;
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

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
