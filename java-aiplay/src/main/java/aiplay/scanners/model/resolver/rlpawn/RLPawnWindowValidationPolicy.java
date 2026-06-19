package aiplay.scanners.model.resolver.rlpawn;

import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.model.sample.TrainingSample;
import aiplay.scanners.model.validation.WindowValidationPolicy;

/**
 * Joint VR+shooting requires lookahead frames for the yaw/pitch delta labels
 * (frame-current vs frame+lookahead).
 * Fire/altFire and target_index labels read the current frame plus a 30-tick
 * post-hoc kill-attribution window, but that window is best-effort (target
 * projector returns confidence 0 if outside session) and does not need to
 * gate window validity.
 */
public class RLPawnWindowValidationPolicy implements WindowValidationPolicy {

    @Override
    public Decision validate(TrainingSample sample) {
        int lookahead = Math.max(1, ModelRoleRegistry.shared()
            .resolve(ModelRole.PAWN_POLICY).trainingCsv().targetLookaheadFrames());
        if (sample.getCurrentIndex() + lookahead >= sample.getSessionFrames().size()) {
            return Decision.STOP;
        }
        return Decision.ACCEPT;
    }
}
