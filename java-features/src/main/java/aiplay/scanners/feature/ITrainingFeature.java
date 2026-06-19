package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;

import java.util.List;
import java.util.Set;

public interface ITrainingFeature {

    Set<String> getFeatureIds();

    default Set<String> getBooleanFeatures() {
        return Set.of();
    }

    default TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return null;
    }

    default TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return null;
    }

    default TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
        return null;
    }

    default TrainingFeatureLogger getTrainingFeatureLogger() {
        return null;
    }

    default Float getFeatureValue(String sessionId, String modelKey, String featureId, GameStateDto f) {
        Float result = resolveFeatureValueForRealTimePlay(sessionId, modelKey, featureId, f);
        if (result == null) {
            throw new IllegalStateException("Feature '" + featureId + "' niet geimplementeerd.");
        }
        if (getTrainingFeatureLogger() != null && isFeatureDebugEnabled()) {
            getTrainingFeatureLogger().onRealTimeResolve(sessionId, modelKey, featureId, f, result);
        }
        return result;
    }

    default Float resolveFeatureValueForRealTimePlay(String sessionId, String modelKey, String featureId, GameStateDto f) {
        if (getTrainingFeatureValueResolver() != null) {
            Float result = getTrainingFeatureValueResolver().resolveFeatureValueForRealTimePlay(featureId, f);
            if (getTrainingFeatureLogger() != null && isFeatureDebugEnabled()) {
                getTrainingFeatureLogger().onRealTimeResolve(sessionId, modelKey, featureId, f, result);
            }
            return result;
        }
        return null;
    }

    default Float resolveCsvWriterFeatureValue(String modelKey, String sessionId, String featureId, List<GameStateDto> gameStates, GameStateDto current) {
        if (getTrainingFeatureValueResolver() != null) {
            Float result = getTrainingFeatureValueResolver().resolveFeatureValueForRealTimePlay(featureId, current);
            if (getTrainingFeatureLogger() != null && isFeatureDebugEnabled()) {
                getTrainingFeatureLogger().onCsvResolve(sessionId, modelKey, featureId, gameStates, current, result);
            }
            return result;
        }
        return null;
    }

    /**
     * Enrich frames during CSV Batch training
     */
    default void enrichBatchFramesForCsvWriter(String sessionId, String modelKey, List<GameStateDto> frames) {
        if (getTrainingFeatureEnricher() != null) {
            getTrainingFeatureEnricher().enrichBatch(frames);
            if (getTrainingFeatureLogger() != null && isFeatureDebugEnabled()) {
                getTrainingFeatureLogger().onEnrichBatch(sessionId, modelKey, this, frames);
            }
        }
    }

    /**
     * Enrich incremental during gameplay for better performance
     *
     * @param frames
     */
    default void enrichIncrementalFramesForRealTimePlay(String sessionId, String modelKey, ITrainingFeature trainingFeature, List<GameStateDto> frames) {
        if (getTrainingFeatureEnricher() != null) {
            getTrainingFeatureEnricher().enrichIncremental(sessionId, frames);
            if (getTrainingFeatureLogger() != null && isFeatureDebugEnabled()) {
                getTrainingFeatureLogger().onEnrichIncremental(sessionId, modelKey, trainingFeature, frames);
            }
        }
    }

    default boolean isFeatureDebugEnabled() {
        List<String> debugFeatures =
                aiplay.config.global.GlobalConfigRepository.shared().debug().features();

        return debugFeatures != null
                && !debugFeatures.isEmpty()
                && getFeatureIds().stream().anyMatch(debugFeatures::contains);
    }

}
