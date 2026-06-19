package aiplay.scanners.feature.resolver.viewtargetpitch;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Priority 6: runs after {@link ViewTargetPitchFeatureComponent} (priority 5) which sets
 * {@code annotatedAimTargetIndex} via {@link AimTargetEnricher}. Exposes that index as a
 * 5-dim one-hot feature group {@code aim_target_index_onehot_0..4} for the viewrotation
 * model, complementing (and possibly differing from) shooting's {@code target_index_onehot_*}.
 */
@TrainingFeatureComponent(priority = 6)
public class AimTargetIndexFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "aim_target_index_onehot_0",
        "aim_target_index_onehot_1",
        "aim_target_index_onehot_2",
        "aim_target_index_onehot_3",
        "aim_target_index_onehot_4"
    );

    private final AimTargetIndexFeatureValueResolver resolver = new AimTargetIndexFeatureValueResolver();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
