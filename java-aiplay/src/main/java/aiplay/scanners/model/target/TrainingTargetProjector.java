package aiplay.scanners.model.target;

import aiplay.scanners.model.sample.AugmentedTrainingSample;

/**
 * Per-model target projector that computes target values for an augmented sample.
 * Targets behave differently under augmentation than input features — movement
 * primitives must be rotated, turn classes must be recomputed, etc.
 */
public interface TrainingTargetProjector {

    /**
     * Resolves a target feature value for the given augmented sample.
     *
     * @param featureId the target feature name
     * @param sample    the augmented training sample
     * @return the target value
     */
    float resolveTargetValue(String featureId, AugmentedTrainingSample sample);

    /**
     * Returns true if this target feature should be formatted as integer (0/1)
     * rather than float. Used for one-hot encoded targets like movement primitives
     * and yaw turn classes.
     */
    default boolean isTargetBoolean(String featureId) {
        return false;
    }
}
