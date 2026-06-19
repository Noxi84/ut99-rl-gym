package aiplay.scanners.model.sample;

import java.util.Iterator;

/**
 * Per-model generator that produces the base variant plus augmented variants
 * for a canonical training sample. Returns a streaming iterator to avoid
 * bulk-materializing all variants for an entire session.
 */
public interface TrainingSampleGenerator {

    /**
     * Generates all sample variants (base + augmented) for the given canonical sample.
     * The base (identity) variant is always included as the first element.
     */
    Iterator<AugmentedTrainingSample> generateSamples(TrainingSample baseSample);
}
