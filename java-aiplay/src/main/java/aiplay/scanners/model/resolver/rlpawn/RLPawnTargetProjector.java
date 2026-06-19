package aiplay.scanners.model.resolver.rlpawn;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.model.ModelConfig;
import aiplay.dto.GameStateDto;
import aiplay.rl.targeting.JointTargetAttribution;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.sample.AugmentedTrainingSample;
import aiplay.scanners.model.sample.TrainingSample;
import aiplay.scanners.model.target.TrainingTargetProjector;
import aiplay.shared.view.ViewTargeting;

/**
 * Full-joint movement+VR+shooting target projector. Combineert het 10-dim joint
 * action label-pad met het auxiliary target_index head: één projector emit alle
 * CSV-output kolommen voor de joint trainer.
 *
 * <h2>Geprojecteerde features</h2>
 * <ul>
 *   <li><b>moveDir_sin/cos</b>, <b>dodge</b>, <b>bJump</b>,
 *       <b>bDuck</b>, <b>bIdle</b> — exact dezelfde labels als het
 *       standalone movement model.</li>
 *   <li><b>yawDelta_norm</b>, <b>pitchDelta_norm</b> — continuous frame-delta
 *       labels.</li>
 *   <li><b>bFire</b>, <b>bAltFire</b> — binary 0/1 current-frame labels via
 *       feature service.</li>
 *   <li><b>target_index</b>, <b>target_index_confidence</b> — aux columns via
 *       post-hoc kill attribution + closest-cosine fallback.</li>
 * </ul>
 */
public class RLPawnTargetProjector implements TrainingTargetProjector {

    private static final String MOVE_DIR_SIN = "moveDir_sin";
    private static final String MOVE_DIR_COS = "moveDir_cos";
    private static final String DODGE = "dodge";
    private static final String B_JUMP = "bJump";
    private static final String B_DUCK = "bDuck";
    private static final String B_IDLE = "bIdle";
    private static final String YAW_DELTA_NORM = "yawDelta_norm";
    private static final String PITCH_DELTA_NORM = "pitchDelta_norm";
    public static final String B_FIRE = "bFire";
    public static final String B_ALT_FIRE = "bAltFire";
    public static final String TARGET_INDEX = "target_index";
    public static final String TARGET_INDEX_CONFIDENCE = "target_index_confidence";

    private final TrainingFeatureService trainingFeatureService;
    private final JointMovementTargetProjector movementProjector = new JointMovementTargetProjector();
    private final int targetLookaheadFrames;
    private final int maxYawStep;
    private final int maxPitchStep;

    public RLPawnTargetProjector(TrainingFeatureService trainingFeatureService) {
        this.trainingFeatureService = trainingFeatureService;
        ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
        this.targetLookaheadFrames = Math.max(1, cfg.trainingCsv().targetLookaheadFrames());

        int bcYawScale = cfg.trainingCsv().bcYawTargetScale();
        int bcPitchScale = cfg.trainingCsv().bcPitchTargetScale();
        this.maxYawStep = (bcYawScale > 0)
            ? bcYawScale
            : GlobalConfigRepository.shared().commandController().yawHeading().continuousMaxStep();
        this.maxPitchStep = (bcPitchScale > 0)
            ? bcPitchScale
            : GlobalConfigRepository.shared().commandController().pitch().continuousMaxStep();
    }

    @Override
    public float resolveTargetValue(String featureId, AugmentedTrainingSample sample) {
        if (isMovementTarget(featureId)) {
            return movementProjector.resolveTargetValue(featureId, sample);
        }
        if (YAW_DELTA_NORM.equals(featureId)) {
            return resolveYawDeltaNorm(sample);
        }
        if (PITCH_DELTA_NORM.equals(featureId)) {
            return resolvePitchDeltaNorm(sample);
        }
        if (TARGET_INDEX.equals(featureId)) {
            return resolveTargetIndexLabel(sample).slot();
        }
        if (TARGET_INDEX_CONFIDENCE.equals(featureId)) {
            return resolveTargetIndexLabel(sample).confidence();
        }
        return resolveFromFeatureService(featureId, sample);
    }

    @Override
    public boolean isTargetBoolean(String featureId) {
        return DODGE.equals(featureId) || B_JUMP.equals(featureId)
            || B_DUCK.equals(featureId) || B_IDLE.equals(featureId)
            || B_FIRE.equals(featureId) || B_ALT_FIRE.equals(featureId);
    }

    private static boolean isMovementTarget(String featureId) {
        return MOVE_DIR_SIN.equals(featureId)
            || MOVE_DIR_COS.equals(featureId)
            || DODGE.equals(featureId)
            || B_JUMP.equals(featureId)
            || B_DUCK.equals(featureId)
            || B_IDLE.equals(featureId);
    }

    // ------------------------------------------------------------------
    // Yaw/pitch projection
    // ------------------------------------------------------------------

    private float resolveYawDeltaNorm(AugmentedTrainingSample sample) {
        TrainingSample base = sample.getBaseSample();
        int currentIndex = base.getCurrentIndex();
        int nextIndex = Math.min(currentIndex + targetLookaheadFrames, base.getSessionFrames().size() - 1);
        if (nextIndex <= currentIndex) return 0f;

        GameStateDto current = base.getSessionFrames().get(currentIndex);
        GameStateDto next = base.getSessionFrames().get(nextIndex);

        int currentYaw = extractYaw(current);
        int nextYaw = extractYaw(next);

        int delta = shortestArcDelta(currentYaw, nextYaw);
        return clamp(((float) delta) / maxYawStep);
    }

    private float resolvePitchDeltaNorm(AugmentedTrainingSample sample) {
        TrainingSample base = sample.getBaseSample();
        int currentIndex = base.getCurrentIndex();
        int nextIndex = Math.min(currentIndex + targetLookaheadFrames, base.getSessionFrames().size() - 1);
        if (nextIndex <= currentIndex) return 0f;

        GameStateDto current = base.getSessionFrames().get(currentIndex);
        GameStateDto next = base.getSessionFrames().get(nextIndex);

        int currentPitch = ViewTargeting.extractSignedPitch(current);
        int nextPitch = ViewTargeting.extractSignedPitch(next);
        int delta = nextPitch - currentPitch;
        return clamp(((float) delta) / maxPitchStep);
    }

    private float resolveFromFeatureService(String featureId, AugmentedTrainingSample sample) {
        TrainingSample base = sample.getBaseSample();
        GameStateDto resolveFrame = base.getLastFrame();
        try {
            Float resolved = trainingFeatureService.resolveCsvWriterFeatureValue(
                base.getModelKey(), base.getSessionId(), featureId,
                base.getSessionFrames(), resolveFrame);
            float v = (resolved != null) ? resolved : 0f;
            return Float.isFinite(v) ? v : 0f;
        } catch (Exception ex) {
            return 0f;
        }
    }

    // ------------------------------------------------------------------
    // target_index aux head
    // ------------------------------------------------------------------

    private JointTargetAttribution.TargetLabel resolveTargetIndexLabel(AugmentedTrainingSample sample) {
        TrainingSample base = sample.getBaseSample();
        JointTargetAttribution.TargetLabel label = JointTargetAttribution.offline(
            base.getSessionFrames(), base.getCurrentIndex(),
            JointTargetAttribution.KILL_ATTRIBUTION_WINDOW_TICKS);
        if (label.slot() < 0) {
            return new JointTargetAttribution.TargetLabel(0, JointTargetAttribution.CONF_MASKED);
        }
        return label;
    }

    private static int extractYaw(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null || frame.playerPawn.viewRotation == null) {
            return 0;
        }
        return frame.playerPawn.viewRotation.x & 0xFFFF;
    }

    private static int shortestArcDelta(int fromYaw, int toYaw) {
        return ((toYaw - fromYaw + 32768) & 0xFFFF) - 32768;
    }

    private static float clamp(float v) {
        if (v < -1f) return -1f;
        if (v > 1f) return 1f;
        return v;
    }
}
