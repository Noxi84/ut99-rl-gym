package aiplay.scanners.feature.resolver.shootingintent;

import aiplay.dto.GameStateDto;
import aiplay.config.PropertyReaderUtils;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.shared.shooting.ShootIntent;
import aiplay.shared.shooting.ShootingIntentStateBus;
import aiplay.shared.shooting.ShootingTargetIndexBus;
import aiplay.shared.view.FireModeAimTargeting;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * Exposes the shooting policy's latest fire-mode decision to viewrotation.
 *
 * <p>Runtime uses the shooting intent bus. CSV generation uses the recorded human
 * bFire/bAltFire state, so BC and realtime keep the same semantics.</p>
 */
@TrainingFeatureComponent(priority = 10)
public class ShootingIntentFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "shootIntentFire",
        "shootIntentAltFire",
        "primaryAimPitchError_norm",
        "secondaryAimPitchError_norm",
        "shootIntentPitchError_norm"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
        "shootIntentFire",
        "shootIntentAltFire"
    );

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return BOOLEAN_FEATURES;
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(String sessionId, String modelKey,
                                                    String featureId, GameStateDto frame) {
        ShootIntent intent = ShootingIntentStateBus.latest(sessionId);
        boolean fire = intent.fire();
        boolean altFire = intent.altFire();
        int targetIndex = frame != null && frame.annotatedShootingTargetIndex >= 0
            ? frame.annotatedShootingTargetIndex
            : ShootingTargetIndexBus.latest(sessionId);
        return resolve(featureId, frame, targetIndex, fire, altFire);
    }

    @Override
    public Float resolveCsvWriterFeatureValue(String modelKey, String sessionId, String featureId,
                                              List<GameStateDto> gameStates, GameStateDto current) {
        boolean fire = current != null && current.playerPawn != null && current.playerPawn.fireActive;
        boolean altFire = current != null && current.playerPawn != null && current.playerPawn.altFireActive;
        int targetIndex = current != null ? current.annotatedShootingTargetIndex : -1;
        return resolve(featureId, current, targetIndex, fire, altFire);
    }

    private static Float resolve(String featureId, GameStateDto frame, int targetIndex,
                                 boolean fire, boolean altFire) {
        double flakPrimary = rewardConfigDouble("projectile_speed_flak_primary_uu", 2700.0);
        double flakSecondary = rewardConfigDouble("projectile_speed_flak_secondary_uu", 1200.0);
        double rocketAimHeight = rewardConfigDouble("rocket_primary_aim_target_height_uu", 0.0);
        // Houd de aim-target-height in lock-step met de reward-kant (RewardTuningConfig) zodat de
        // primaryAimPitchError-feature dezelfde sniper head-aim ziet als wat beloond wordt. Fallback
        // ~27 = baseEyeHeight (eye-aim) voor het geval het veld in een losse feature-run ontbreekt.
        double sniperAimHeight = rewardConfigDouble("sniper_primary_aim_target_height_uu", 27.0);
        return switch (featureId) {
            case "shootIntentFire" -> fire ? 1.0f : 0.0f;
            case "shootIntentAltFire" -> altFire ? 1.0f : 0.0f;
            case "primaryAimPitchError_norm" ->
                finite(FireModeAimTargeting.computePrimaryPitchErrorNorm(
                    frame, targetIndex, flakPrimary, flakSecondary, rocketAimHeight, sniperAimHeight));
            case "secondaryAimPitchError_norm" ->
                finite(FireModeAimTargeting.computeSecondaryPitchErrorNorm(
                    frame, targetIndex, flakPrimary, flakSecondary, rocketAimHeight, sniperAimHeight));
            case "shootIntentPitchError_norm" ->
                finite(FireModeAimTargeting.computeSelectedPitchErrorNorm(
                    frame, targetIndex, flakPrimary, flakSecondary, rocketAimHeight, sniperAimHeight,
                    fire, altFire));
            default -> null;
        };
    }

    private static float finite(double v) {
        return Double.isFinite(v) ? (float) v : 0.0f;
    }

    private static double rewardConfigDouble(String field, double fallback) {
        String modelKey = ModelRoleRegistry.shared().getModelKey(ModelRole.PAWN_POLICY);
        if (modelKey == null || modelKey.isBlank()) {
            return fallback;
        }
        JsonNode rewards = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/rewards");
        if (rewards == null || !rewards.path(field).isNumber()) {
            return fallback;
        }
        return rewards.path(field).asDouble();
    }
}
