package aiplay.scanners.feature.resolver.shootingtarget;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Phase 2e: feature component that exposes the shooting model's target_index
 * as a 5-dim one-hot input feature for VR (and any other consumer that needs
 * to see which enemy slot the shooting model just picked).
 *
 * <p>Priority 5: runs after engagement (priority 4) so the bus has been
 * updated by the shooting executor before VR's input window is built.</p>
 */
@TrainingFeatureComponent(priority = 5)
public class ShootingTargetIndexFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "target_index_onehot_0",
        "target_index_onehot_1",
        "target_index_onehot_2",
        "target_index_onehot_3",
        "target_index_onehot_4"
    );

    private final ShootingTargetIndexFeatureValueResolver resolver = new ShootingTargetIndexFeatureValueResolver();
    private final ShootingTargetIndexEnricher enricher = new ShootingTargetIndexEnricher();

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

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
