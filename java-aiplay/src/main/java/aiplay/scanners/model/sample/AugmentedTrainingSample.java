package aiplay.scanners.model.sample;

/**
 * Wrapper around a canonical {@link TrainingSample} for the CSV-writer pipeline.
 */
public class AugmentedTrainingSample {

    private final TrainingSample baseSample;

    public AugmentedTrainingSample(TrainingSample baseSample) {
        this.baseSample = baseSample;
    }

    public static AugmentedTrainingSample identity(TrainingSample baseSample) {
        return new AugmentedTrainingSample(baseSample);
    }

    public TrainingSample getBaseSample() {
        return baseSample;
    }
}
