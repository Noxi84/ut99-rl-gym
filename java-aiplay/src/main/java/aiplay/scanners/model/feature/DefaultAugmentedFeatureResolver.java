package aiplay.scanners.model.feature;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.sample.AugmentedTrainingSample;
import aiplay.scanners.model.sample.TrainingSample;

public class DefaultAugmentedFeatureResolver implements AugmentedFeatureResolver {

    private final TrainingFeatureService trainingFeatureService;

    public DefaultAugmentedFeatureResolver(TrainingFeatureService trainingFeatureService) {
        this.trainingFeatureService = trainingFeatureService;
    }

    @Override
    public float resolveFeatureValue(String featureId, AugmentedTrainingSample sample, GameStateDto frame) {
        TrainingSample base = sample.getBaseSample();
        try {
            Float resolved = trainingFeatureService.resolveCsvWriterFeatureValue(
                    base.getModelKey(), base.getSessionId(), featureId,
                    base.getSessionFrames(), frame);
            float v = (resolved != null) ? resolved : 0f;
            return Float.isFinite(v) ? v : 0f;
        } catch (Exception ex) {
            return 0f;
        }
    }
}
