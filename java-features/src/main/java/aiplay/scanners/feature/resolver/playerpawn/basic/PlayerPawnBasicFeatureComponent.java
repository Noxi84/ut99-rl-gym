package aiplay.scanners.feature.resolver.playerpawn.basic;

import aiplay.scanners.feature.*;
import aiplay.scanners.feature.resolver.movement.dodge.DodgeDirTrackingEnricher;
import aiplay.scanners.feature.resolver.movement.idle.TimeSinceLastMoveTrackingEnricher;

import java.util.Set;

@TrainingFeatureComponent(priority = 10)
public class PlayerPawnBasicFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
            "self_name", "self_locationX", "self_locationY", "self_locationZ",
            "self_locationX_norm", "self_locationY_norm",
            "self_backCollision", "self_fwdCollision", "self_leftCollision", "self_rightCollision",
            "self_backCollision_norm", "self_fwdCollision_norm", "self_leftCollision_norm", "self_rightCollision_norm",
            "self_posXCollision", "self_negXCollision", "self_posYCollision", "self_negYCollision",
            "self_posXCollision_norm", "self_negXCollision_norm", "self_posYCollision_norm", "self_negYCollision_norm",
            "self_fwdRight30Collision", "self_fwdRight45Collision", "self_fwdRight60Collision",
            "self_backRight60Collision", "self_backRight45Collision", "self_backRight30Collision",
            "self_backLeft30Collision", "self_backLeft45Collision", "self_backLeft60Collision",
            "self_fwdLeft60Collision", "self_fwdLeft45Collision", "self_fwdLeft30Collision",
            "self_fwdRight30Collision_norm", "self_fwdRight45Collision_norm", "self_fwdRight60Collision_norm",
            "self_backRight60Collision_norm", "self_backRight45Collision_norm", "self_backRight30Collision_norm",
            "self_backLeft30Collision_norm", "self_backLeft45Collision_norm", "self_backLeft60Collision_norm",
            "self_fwdLeft60Collision_norm", "self_fwdLeft45Collision_norm", "self_fwdLeft30Collision_norm",
            "self_posXPosY30Collision", "self_posXPosY45Collision", "self_posXPosY60Collision",
            "self_negXPosY60Collision", "self_negXPosY45Collision", "self_negXPosY30Collision",
            "self_negXNegY30Collision", "self_negXNegY45Collision", "self_negXNegY60Collision",
            "self_posXNegY60Collision", "self_posXNegY45Collision", "self_posXNegY30Collision",
            "self_posXPosY30Collision_norm", "self_posXPosY45Collision_norm", "self_posXPosY60Collision_norm",
            "self_negXPosY60Collision_norm", "self_negXPosY45Collision_norm", "self_negXPosY30Collision_norm",
            "self_negXNegY30Collision_norm", "self_negXNegY45Collision_norm", "self_negXNegY60Collision_norm",
            "self_posXNegY60Collision_norm", "self_posXNegY45Collision_norm", "self_posXNegY30Collision_norm",
            "self_fwdFloorDelta", "self_fwdRightFloorDelta", "self_rightFloorDelta", "self_backRightFloorDelta",
            "self_backFloorDelta", "self_backLeftFloorDelta", "self_leftFloorDelta", "self_fwdLeftFloorDelta",
            "self_fwdFloorDelta_norm", "self_fwdRightFloorDelta_norm", "self_rightFloorDelta_norm",
            "self_backRightFloorDelta_norm", "self_backFloorDelta_norm", "self_backLeftFloorDelta_norm",
            "self_leftFloorDelta_norm", "self_fwdLeftFloorDelta_norm",
            "self_fwdLowCollision", "self_fwdRightLowCollision", "self_rightLowCollision", "self_backRightLowCollision",
            "self_backLowCollision", "self_backLeftLowCollision", "self_leftLowCollision", "self_fwdLeftLowCollision",
            "self_fwdLowCollision_norm", "self_fwdRightLowCollision_norm", "self_rightLowCollision_norm",
            "self_backRightLowCollision_norm", "self_backLowCollision_norm", "self_backLeftLowCollision_norm",
            "self_leftLowCollision_norm", "self_fwdLeftLowCollision_norm",
            "self_floorBelow", "self_ceilingAbove",
            "self_floorBelow_norm", "self_ceilingAbove_norm",
            "self_hasFlag", "self_viewRotationX", "self_viewRotationY",
            "self_viewRotationX_sin", "self_viewRotationX_cos", "self_viewRotationY_norm",
            "self_baseEyeHeight", "self_health", "self_health_norm", "self_team", "self_score", "self_bFeigningDeath",
            "self_oldLocationX", "self_oldLocationY", "self_oldLocationZ",
            "self_dodgeState", "self_dodgeCooldown_norm",
            "self_timeSinceLastMove_norm",
            "self_dodgeDirForward", "self_dodgeDirBack",
            "self_dodgeDirLeft", "self_dodgeDirRight", "self_dodgeActive", "self_dodgeCooldown",
            "self_physics_isWalking", "self_physics_isFalling", "self_physics_isSwimming",
            "self_headUnderwater", "self_breathRemaining_norm",
            "self_zAboveSpawn_norm",
            "self_speed_norm", "self_team_norm",
            "self_velocityX_norm", "self_velocityY_norm", "self_velocityZ_norm",
            "self_forwardVelocity_norm", "self_rightVelocity_norm",
            "self_accelerationX_norm", "self_accelerationY_norm", "self_accelerationZ_norm",
            "self_forwardAcceleration_norm", "self_rightAcceleration_norm",
            "self_forwardAccelVelocityMismatch_norm", "self_rightAccelVelocityMismatch_norm",
            "self_bIsSpectator", "self_bIsABot", "self_bWaitingPlayer"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
            "self_hasFlag",
            "self_dodgeDirForward", "self_dodgeDirBack",
            "self_dodgeDirLeft", "self_dodgeDirRight", "self_dodgeActive", "self_dodgeCooldown",
            "self_physics_isWalking", "self_physics_isFalling", "self_physics_isSwimming",
            "self_headUnderwater",
            "self_bFeigningDeath", "self_bIsSpectator", "self_bIsABot", "self_bWaitingPlayer"
    );

    private PlayerPawnBasicFeatureValueResolver featureValueResolver = new PlayerPawnBasicFeatureValueResolver();
    private PlayerPawnBasicFeatureJsonToDtoConverter jsonToDtoConverter = new PlayerPawnBasicFeatureJsonToDtoConverter();
    private PlayerPawnBasicFeatureLogger logger = new PlayerPawnBasicFeatureLogger();
    private TrainingFeatureEnricher movementStateEnricher = new CompositeTrainingFeatureEnricher(
        new DodgeDirTrackingEnricher(),
        new TimeSinceLastMoveTrackingEnricher()
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
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return featureValueResolver;
    }

    @Override
    public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
        return jsonToDtoConverter;
    }

    @Override
    public TrainingFeatureLogger getTrainingFeatureLogger() {
        return logger;
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return movementStateEnricher;
    }
}
