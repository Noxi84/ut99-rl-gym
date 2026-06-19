package aiplay.scanners.feature.resolver.flag.flagrelative;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.*;

import java.util.List;
import java.util.Set;

@TrainingFeatureComponent(priority = 20)
public class FlagRelativeFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
            // Home flag relative projection (moving flag location)
            "homeFlag_relSin",
            "homeFlag_relCos",

            // Home base relative projection (fixed base location)
            "homeBase_relSin",
            "homeBase_relCos",
            "homeBaseDistance_norm",

            // Enemy base relative projection (fixed base location)
            "enemyBase_relSin",
            "enemyBase_relCos",
            "enemyBaseDistance_norm",

            // Home flag status
            "homeFlagHasHolder",

            // Enemy flag relative projection
            "enemyFlag_relSin",
            "enemyFlag_relCos",

            // Enemy flag status
            "enemyFlagHasHolder",

            // Auto-return timer (egocentric mapping)
            "homeFlag_dropReturnRemaining_norm",
            "enemyFlag_dropReturnRemaining_norm"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
            "homeFlagHasHolder",
            "enemyFlagHasHolder"
    );

    private FlagRelativeFeatureValueResolver featureValueResolver = new FlagRelativeFeatureValueResolver();
    private FlagRelativeFeatureJsonToDtoConverter jsonToDtoConverter = new FlagRelativeFeatureJsonToDtoConverter();
    private final TrainingFeatureEnricher enricher = new TrainingFeatureEnricher() {
        private final FlagRelativeBatchEnricher relEnricher = new FlagRelativeBatchEnricher();
        private final FlagDropTimerEnricher dropTimerEnricher = new FlagDropTimerEnricher();

        @Override
        public void enrichBatch(List<GameStateDto> frames) {
            relEnricher.enrichBatch(frames);
            dropTimerEnricher.enrichBatch(frames);
        }

        @Override
        public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
            relEnricher.enrichIncremental(sessionId, frames);
            dropTimerEnricher.enrichIncremental(sessionId, frames);
        }
    };

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
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
