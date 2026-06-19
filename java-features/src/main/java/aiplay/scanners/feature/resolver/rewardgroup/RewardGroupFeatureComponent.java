package aiplay.scanners.feature.resolver.rewardgroup;

import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.GameStateDto;
import aiplay.config.model.RewardGroupConfig;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import java.util.Set;

/**
 * Static one-hot context for the bot's tactical role.
 *
 * <p>Feature names and order come from each model's {@code rewards.json}
 * {@code rewardgroups} declaration. The active group is selected from the
 * per-thread role and resolved as a one-hot vector — exactly one feature-id
 * is 1.0 per bot, the rest are 0.0.
 */
@TrainingFeatureComponent(priority = 10)
public class RewardGroupFeatureComponent implements ITrainingFeature {

    @Override
    public Set<String> getFeatureIds() {
        return RewardGroupConfig.featureNamesAcrossModels();
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return getFeatureIds();
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(
            String sessionId,
            String modelKey,
            String featureId,
            GameStateDto f) {
        return isActive(modelKey, featureId) ? 1.0f : 0.0f;
    }

    @Override
    public Float resolveCsvWriterFeatureValue(
            String modelKey,
            String sessionId,
            String featureId,
            java.util.List<GameStateDto> gameStates,
            GameStateDto current) {
        return isActive(modelKey, featureId) ? 1.0f : 0.0f;
    }

    private static boolean isActive(String modelKey, String featureId) {
        return featureId.equals(
                RewardGroupConfig.activeFeatureName(
                        modelKey,
                        PlayerIdentityContext.effectiveRole()));
    }
}
