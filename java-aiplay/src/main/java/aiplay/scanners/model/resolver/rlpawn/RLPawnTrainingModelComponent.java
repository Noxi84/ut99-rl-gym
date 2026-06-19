package aiplay.scanners.model.resolver.rlpawn;

import aiplay.config.model.ModelConfig;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.feature.contract.FeatureContractRepository;
import aiplay.scanners.model.ITrainingModel;
import aiplay.scanners.model.TrainingModelComponent;
import aiplay.scanners.model.TrainingModelLogger;
import aiplay.scanners.model.TrainingModelTrainingCsvWriter;
import aiplay.scanners.model.dedup.BucketDeduplicationPolicy;
import aiplay.scanners.model.dedup.DeduplicationPolicy;
import aiplay.scanners.model.feature.AugmentedFeatureResolver;
import aiplay.scanners.model.feature.DefaultAugmentedFeatureResolver;
import aiplay.scanners.model.resolver.rlshared.RLModelTrainingLogger;
import aiplay.scanners.model.sample.TrainingSampleGenerator;
import aiplay.scanners.model.target.TrainingTargetProjector;
import aiplay.scanners.model.validation.WindowValidationPolicy;

import java.util.List;

/**
 * Joint VR+shooting training-model component (vr-shooting-sac-merge.md Fase 1a).
 *
 * <p>Joint training-model component. Combineert VR's lookahead window-validation
 * + sample augmentation met shooting's bucket-dedup + target_index aux columns.</p>
 *
 * <p>Priority 23. Fase 4a (vr-shooting-sac-merge.md) heeft alle wirings
 * voltooid:
 * <ol>
 *   <li>{@code resources/models/rl_pawn/} directory met de configs
 *       (verhuisd van {@code resources/config/rl_pawn/}).</li>
 *   <li>{@code resources/models/index.json} entry voor {@code rl_pawn}.</li>
 *   <li>{@code resources/config/roles.json} binding {@code pawn_policy:
 *       "rl_pawn"}.</li>
 * </ol>
 * {@code cfg()} resolved nu op runtime zonder IllegalStateException; deze class
 * is daarmee actief in de scanner-pipeline.</p>
 */
@TrainingModelComponent(priority = 23)
public class RLPawnTrainingModelComponent implements ITrainingModel {

    private final RLModelTrainingLogger logger = new RLModelTrainingLogger();
    private final TrainingFeatureService trainingFeatureService = TrainingFeatureService.shared();

    private ModelConfig cfg() {
        return ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
    }

    @Override
    public String getModelKey() {
        return cfg().modelKey();
    }

    @Override
    public int getCsvFPS() {
        return cfg().trainingCsv().csvFps();
    }

    @Override
    public int getCsvNumberOfColumns() {
        return cfg().sequenceLength();
    }

    @Override
    public List<String> getInputFeatures() {
        return FeatureContractRepository.shared().get(getModelKey()).inputFeatures();
    }

    @Override
    public List<String> getTargetFeatures() {
        return FeatureContractRepository.shared().get(getModelKey()).targetFeatures();
    }

    @Override
    public List<String> getCsvAuxTargetFeatures() {
        return cfg().features().auxTargetFeatures();
    }

    @Override
    public boolean isCsvEnabled() {
        return cfg().trainingCsv().enabled();
    }

    @Override
    public TrainingModelTrainingCsvWriter getTrainingModelCsvWriter() {
        return new TrainingModelTrainingCsvWriter(this);
    }

    @Override
    public TrainingModelLogger getTrainingModelLogger() {
        return logger;
    }

    @Override
    public TrainingSampleGenerator getTrainingSampleGenerator() {
        return new RLPawnSampleGenerator();
    }

    @Override
    public TrainingTargetProjector getTrainingTargetProjector() {
        return new RLPawnTargetProjector(trainingFeatureService);
    }

    @Override
    public WindowValidationPolicy getWindowValidationPolicy() {
        return new RLPawnWindowValidationPolicy();
    }

    @Override
    public DeduplicationPolicy createDeduplicationPolicy() {
        return new BucketDeduplicationPolicy(
            trainingFeatureService, cfg().trainingCsv().stateBucketKey(), 4);
    }

    @Override
    public AugmentedFeatureResolver getAugmentedFeatureResolver() {
        return new DefaultAugmentedFeatureResolver(trainingFeatureService);
    }

    @Override
    public TrainingFeatureService getTrainingFeatureService() {
        return trainingFeatureService;
    }
}
