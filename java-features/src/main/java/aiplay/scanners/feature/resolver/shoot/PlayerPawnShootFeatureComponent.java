package aiplay.scanners.feature.resolver.shoot;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.TrainingFeatureLogger;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.playerpawn.basic.PlayerPawnBasicFeatureLogger;

import java.util.Set;

@TrainingFeatureComponent(priority = 10)
public class PlayerPawnShootFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
            "bFire",
            "bAltFire",
            "fireActive",
            "fireCooldown",
            "altFireActive",
            "altFireCooldown"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
            "bFire",
            "bAltFire",
            "fireActive",
            "fireCooldown",
            "altFireActive",
            "altFireCooldown"
    );

    private PlayerPawnShootFeatureValueResolver featureValueResolver = new PlayerPawnShootFeatureValueResolver();
    private PlayerPawnShootFeatureJsonToDtoConverter jsonToDtoConverter = new PlayerPawnShootFeatureJsonToDtoConverter();
    private FireCooldownIncrementalEnricher enricher = new FireCooldownIncrementalEnricher();
    private PlayerPawnBasicFeatureLogger logger = new PlayerPawnBasicFeatureLogger();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return BOOLEAN_FEATURES;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return featureValueResolver;
    }

    @Override
    public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
        return jsonToDtoConverter;
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }

    @Override
    public TrainingFeatureLogger getTrainingFeatureLogger() {
        return logger;
    }
}
