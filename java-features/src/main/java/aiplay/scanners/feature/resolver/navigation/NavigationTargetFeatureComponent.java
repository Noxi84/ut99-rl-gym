package aiplay.scanners.feature.resolver.navigation;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Unified navigation target bearing — always points toward the current mission objective.
 *
 * Resolves the target location based on annotatedMission:
 *   CAPTURE_FLAG     → enemy flag location
 *   RETURN_HOME      → home base (own flag base)
 *   INTERCEPT_CARRIER → home flag location (tracks the carrier in real time)
 *   STUCK_RECOVER    → 0.0 (no directional target)
 *
 * This eliminates the need for the model to learn which bearing feature
 * to follow for each mission type — it always follows navTarget.
 *
 * Priority 5: runs after skill enrichment (3) and mission features (4),
 * so annotatedMission is guaranteed to be set.
 */
@TrainingFeatureComponent(priority = 5)
public class NavigationTargetFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "navTarget_relSin",
        "navTarget_relCos"
    );

    private final NavigationTargetFeatureValueResolver resolver = new NavigationTargetFeatureValueResolver();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
