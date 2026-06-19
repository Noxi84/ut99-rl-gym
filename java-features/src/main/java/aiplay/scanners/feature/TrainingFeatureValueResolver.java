package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;

public interface TrainingFeatureValueResolver {

    Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f);
}
