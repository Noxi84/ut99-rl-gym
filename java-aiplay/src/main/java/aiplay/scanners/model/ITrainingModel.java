package aiplay.scanners.model;

import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.dedup.DeduplicationPolicy;
import aiplay.scanners.model.feature.AugmentedFeatureResolver;
import aiplay.scanners.model.sample.TrainingSampleGenerator;
import aiplay.scanners.model.target.TrainingTargetProjector;
import aiplay.scanners.model.validation.WindowValidationPolicy;

import java.util.List;

public interface ITrainingModel {

    default int priority() {
        return 100;
    }

    String getModelKey();

    int getCsvFPS();

    int getCsvNumberOfColumns();

    List<String> getInputFeatures();

    List<String> getTargetFeatures();

    /**
     * Target features for CSV training output. Defaults to getTargetFeatures().
     * Override to filter out features not needed for BC training (e.g. dodge, log_std).
     */
    default List<String> getCsvTargetFeatures() {
        return getTargetFeatures();
    }

    /**
     * Phase 2 — aux target columns (e.g. target_index, target_index_confidence).
     * Written to CSV after the main target columns; consumed by aux losses in
     * Python BC training (NOT part of model.output_size). Default: empty.
     */
    default List<String> getCsvAuxTargetFeatures() {
        return java.util.List.of();
    }

    /**
     * Whether CSV generation is enabled for this model.
     */
    boolean isCsvEnabled();

    TrainingModelTrainingCsvWriter getTrainingModelCsvWriter();

    TrainingModelLogger getTrainingModelLogger();

    TrainingSampleGenerator getTrainingSampleGenerator();

    TrainingTargetProjector getTrainingTargetProjector();

    WindowValidationPolicy getWindowValidationPolicy();

    DeduplicationPolicy createDeduplicationPolicy();

    AugmentedFeatureResolver getAugmentedFeatureResolver();

    TrainingFeatureService getTrainingFeatureService();
}
