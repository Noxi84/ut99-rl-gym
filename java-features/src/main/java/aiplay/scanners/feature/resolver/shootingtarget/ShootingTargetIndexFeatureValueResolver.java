package aiplay.scanners.feature.resolver.shootingtarget;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Phase 2e: resolves {@code target_index_onehot_0..4} features from
 * {@code frame.annotatedShootingTargetIndex} (set by {@link ShootingTargetIndexEnricher}).
 *
 * <p>Returns 1.0 for the slot index that matches the model's chosen target,
 * 0.0 for all other slots. When target_index is -1 (no model choice yet),
 * all five outputs are 0.0 — matches the "no commitment" prior.</p>
 */
public class ShootingTargetIndexFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto frame) {
        if (frame == null) return 0.0f;
        int idx = frame.annotatedShootingTargetIndex;
        if (idx < 0) return 0.0f;
        return switch (featureId) {
            case "target_index_onehot_0" -> idx == 0 ? 1.0f : 0.0f;
            case "target_index_onehot_1" -> idx == 1 ? 1.0f : 0.0f;
            case "target_index_onehot_2" -> idx == 2 ? 1.0f : 0.0f;
            case "target_index_onehot_3" -> idx == 3 ? 1.0f : 0.0f;
            case "target_index_onehot_4" -> idx == 4 ? 1.0f : 0.0f;
            default -> 0.0f;
        };
    }
}
