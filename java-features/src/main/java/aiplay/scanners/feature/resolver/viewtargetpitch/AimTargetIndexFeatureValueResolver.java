package aiplay.scanners.feature.resolver.viewtargetpitch;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Resolves {@code aim_target_index_onehot_0..4} from {@link GameStateDto#annotatedAimTargetIndex}
 * (set by {@link AimTargetEnricher}). One-hot signal of which enemy slot the role-aware aim
 * target lives in; complements (and may differ from) the shooting model's
 * {@code target_index_onehot_*} (which reads {@code annotatedShootingTargetIndex}).
 *
 * <p>Returns 1.0 only on the matched slot. When index is -1 (no aim target — typically because
 * attention is OBJECTIVE/NONE), all five outputs are 0.0 — same "no commitment" prior as the
 * shooting variant.</p>
 */
public class AimTargetIndexFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto frame) {
        if (frame == null) return 0.0f;
        int idx = frame.annotatedAimTargetIndex;
        if (idx < 0) return 0.0f;
        return switch (featureId) {
            case "aim_target_index_onehot_0" -> idx == 0 ? 1.0f : 0.0f;
            case "aim_target_index_onehot_1" -> idx == 1 ? 1.0f : 0.0f;
            case "aim_target_index_onehot_2" -> idx == 2 ? 1.0f : 0.0f;
            case "aim_target_index_onehot_3" -> idx == 3 ? 1.0f : 0.0f;
            case "aim_target_index_onehot_4" -> idx == 4 ? 1.0f : 0.0f;
            default -> 0.0f;
        };
    }
}
