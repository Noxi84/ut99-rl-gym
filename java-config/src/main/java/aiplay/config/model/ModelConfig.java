package aiplay.config.model;

public record ModelConfig(
    String modelKey,
    ModelRuntimeConfig runtime,
    ModelTrainingCsvConfig trainingCsv,
    ModelFeaturesConfig features
) {
    /** Sequence length: derived from feature_groups when present, else from training_csv.number_of_columns. */
    public int sequenceLength() {
        return features.hasTemporalGroups() ? features.totalWindow() : trainingCsv.numberOfColumns();
    }
}
