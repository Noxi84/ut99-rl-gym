package aiplay.scanners.model.feature;

import aiplay.dto.GameStateDto;
import aiplay.scanners.model.sample.AugmentedTrainingSample;

/**
 * Projects feature values for a training sample in the CSV-writer pipeline.
 */
public interface AugmentedFeatureResolver {

    float resolveFeatureValue(String featureId, AugmentedTrainingSample sample, GameStateDto frame);
}
