package aiplay.scanners.feature.resolver.viewtargetpitch;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.shared.view.ViewTargeting;
import java.util.Set;

/**
 * Priority 5: runs vóór alle value resolvers (priority 10+), zodat AimTargetEnricher
 * annotatedAimEnemy kan zetten voordat ViewTargeting wordt aangeroepen — via zowel
 * feature value resolution (hieronder) als via reward computation (RewardUtils,
 * PitchReward, ViewAlignmentReward). Sticky aim-target voorkomt pitch-oscillatie
 * bij 2+ enemies.
 *
 * <p>Exposeert pitch én yaw heading-features. De {@code _norm}-varianten zijn
 * absolute target-bearings (yaw is map-asymmetrisch en wordt nu niet als input
 * gebruikt); de {@code _error_norm}-varianten zijn signed-arc verschillen
 * tussen target-bearing en huidige view, zonder map-asymmetrie.</p>
 */
@TrainingFeatureComponent(priority = 5)
public class ViewTargetPitchFeatureComponent implements ITrainingFeature {

  private static final Set<String> FEATURE_IDS = Set.of(
      "headingTargetPitch_norm",
      "headingTargetPitchError_norm",
      "headingTargetYaw_norm",
      "headingTargetYawError_norm"
  );

  private final TrainingFeatureValueResolver resolver = (featureId, frame) -> switch (featureId) {
    case "headingTargetPitch_norm" -> (float) ViewTargeting.computeHeadingTargetPitchNorm(frame);
    case "headingTargetPitchError_norm" -> (float) ViewTargeting.computeHeadingTargetPitchErrorNorm(frame);
    case "headingTargetYaw_norm" -> (float) ViewTargeting.computeHeadingTargetYawNorm(frame);
    case "headingTargetYawError_norm" -> (float) ViewTargeting.computeHeadingTargetYawErrorNorm(frame);
    default -> null;
  };

  private final AimTargetEnricher enricher = new AimTargetEnricher();

  @Override
  public Set<String> getFeatureIds() {
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
