package aiplay.scanners.feature.resolver;

import aiplay.dto.CollisionsDto;
import aiplay.dto.DodgeState;
import aiplay.dto.PlayerDto;
import aiplay.dto.Ut99PhysicsType;

import java.util.Set;

/**
 * Shared feature resolver for PlayerDto fields. Used by
 * PlayerPawnBasicFeatureValueResolver (self: f.playerPawn),
 * EnemySlotFeatureValueResolver (f.enemies[slot]), and
 * TeammateSlotFeatureValueResolver (f.teammates[slot]).
 *
 * Adding a feature here automatically makes it available as a self feature
 * (e.g. "self_speed_norm"), an enemy slot feature (e.g. "enemy0_speed_norm"),
 * and a teammate slot feature (e.g. "teammate0_speed_norm").
 */
public class PlayerDtoFeatureResolver {

    /** All suffixes handled by this shared resolver. */
    public static final String[] SHARED_SUFFIXES = {
        // Location — alleen X/Y nog. Z is vervangen door egocentric features
        // (self_zAboveSpawn_norm, enemy/teammate_relZ_norm, self_floorBelow_norm,
        // self_ceilingAbove_norm) omdat map-specifieke Z-bounds geen overdraagbaar
        // signaal opleveren tussen maps.
        "locationX_norm", "locationY_norm",
        // Collision — cardinal (yaw-relative)
        "fwdCollision_norm", "backCollision_norm", "leftCollision_norm", "rightCollision_norm",
        // Collision — diagonal (yaw-relative)
        "fwdRight30Collision_norm", "fwdRight45Collision_norm", "fwdRight60Collision_norm",
        "backRight60Collision_norm", "backRight45Collision_norm", "backRight30Collision_norm",
        "backLeft30Collision_norm", "backLeft45Collision_norm", "backLeft60Collision_norm",
        "fwdLeft60Collision_norm", "fwdLeft45Collision_norm", "fwdLeft30Collision_norm",
        // Collision — world-axis cardinal
        "posXCollision_norm", "negXCollision_norm", "posYCollision_norm", "negYCollision_norm",
        // Collision — world-axis diagonal
        "posXPosY30Collision_norm", "posXPosY45Collision_norm", "posXPosY60Collision_norm",
        "negXPosY60Collision_norm", "negXPosY45Collision_norm", "negXPosY30Collision_norm",
        "negXNegY30Collision_norm", "negXNegY45Collision_norm", "negXNegY60Collision_norm",
        "posXNegY60Collision_norm", "posXNegY45Collision_norm", "posXNegY30Collision_norm",
        // Floor-elevation probes — yaw-relative 8-sector fan, signed: <0 drop, >0 step-up, ±1 void/wall.
        "fwdFloorDelta_norm", "fwdRightFloorDelta_norm", "rightFloorDelta_norm", "backRightFloorDelta_norm",
        "backFloorDelta_norm", "backLeftFloorDelta_norm", "leftFloorDelta_norm", "fwdLeftFloorDelta_norm",
        // Foot-height low rays — yaw-relative 8-sector fan, 0=blocked, 1=clear (catches low obstacles).
        "fwdLowCollision_norm", "fwdRightLowCollision_norm", "rightLowCollision_norm", "backRightLowCollision_norm",
        "backLowCollision_norm", "backLeftLowCollision_norm", "leftLowCollision_norm", "fwdLeftLowCollision_norm",
        // Velocity
        "speed_norm", "forwardVelocity_norm", "rightVelocity_norm",
        "velocityX_norm", "velocityY_norm", "velocityZ_norm",
        // Acceleration
        "accelerationX_norm", "accelerationY_norm", "accelerationZ_norm",
        "forwardAcceleration_norm", "rightAcceleration_norm",
        "forwardAccelVelocityMismatch_norm", "rightAccelVelocityMismatch_norm",
        // ViewRotation
        "viewRotationX_sin", "viewRotationX_cos", "viewRotationY_norm",
        // Status
        "hasFlag", "visible", "health_norm",
        // Dodge — continuous
        "dodgeCooldown_norm",
        "dodgeDir_sin", "dodgeDir_cos",
        // Idle duration — continuous (per-player)
        "timeSinceLastMove_norm",
        // Dodge — one-hot direction + state
        "dodgeDirForward", "dodgeDirBack", "dodgeDirLeft", "dodgeDirRight",
        "dodgeActive", "dodgeCooldown",
        // Physics state — one-hot. Walking = grounded (kan dodgen, normale strafe-control).
        // Falling = airborne (dodge werkt niet, gereduceerde air-control). Swimming = water.
        "physics_isWalking", "physics_isFalling", "physics_isSwimming",
        // Submersion — headUnderwater (hoofd onder water → verdrinkt),
        // breathRemaining_norm (resterende adem, 1.0 = vol).
        "headUnderwater", "breathRemaining_norm",
        // Misc
        "bFeigningDeath",
    };

    /** Boolean suffixes (0 or 1) within the shared set. */
    public static final String[] SHARED_BOOLEAN_SUFFIXES = {
        "hasFlag", "visible", "bFeigningDeath",
        "dodgeDirForward", "dodgeDirBack", "dodgeDirLeft", "dodgeDirRight",
        "dodgeActive", "dodgeCooldown",
        "physics_isWalking", "physics_isFalling", "physics_isSwimming",
        "headUnderwater",
    };

    /**
     * Resolve a feature value from a PlayerDto.
     * @param suffix the feature suffix (e.g. "speed_norm")
     * @param player the PlayerDto (can be player pawn or enemy)
     * @return the feature value, or null if the suffix is not handled
     */
    public static Float resolve(String suffix, PlayerDto player) {
        CollisionsDto collisions = (player != null) ? player.collisions : null;

        return switch (suffix) {
            // Location — Z vervangen door egocentric features (zie SHARED_SUFFIXES comment).
            case "locationX_norm" -> (player != null && player.location != null) ? (float) player.location.x_norm : 0.0f;
            case "locationY_norm" -> (player != null && player.location != null) ? (float) player.location.y_norm : 0.0f;

            // Collision — cardinal (yaw-relative)
            case "fwdCollision_norm" -> (collisions != null) ? (float) collisions.fwdCollision_norm : 0.0f;
            case "backCollision_norm" -> (collisions != null) ? (float) collisions.backCollision_norm : 0.0f;
            case "leftCollision_norm" -> (collisions != null) ? (float) collisions.leftCollision_norm : 0.0f;
            case "rightCollision_norm" -> (collisions != null) ? (float) collisions.rightCollision_norm : 0.0f;

            // Collision — diagonal (yaw-relative)
            case "fwdRight30Collision_norm" -> (collisions != null) ? (float) collisions.fwdRight30Collision_norm : 0.0f;
            case "fwdRight45Collision_norm" -> (collisions != null) ? (float) collisions.fwdRight45Collision_norm : 0.0f;
            case "fwdRight60Collision_norm" -> (collisions != null) ? (float) collisions.fwdRight60Collision_norm : 0.0f;
            case "backRight60Collision_norm" -> (collisions != null) ? (float) collisions.backRight60Collision_norm : 0.0f;
            case "backRight45Collision_norm" -> (collisions != null) ? (float) collisions.backRight45Collision_norm : 0.0f;
            case "backRight30Collision_norm" -> (collisions != null) ? (float) collisions.backRight30Collision_norm : 0.0f;
            case "backLeft30Collision_norm" -> (collisions != null) ? (float) collisions.backLeft30Collision_norm : 0.0f;
            case "backLeft45Collision_norm" -> (collisions != null) ? (float) collisions.backLeft45Collision_norm : 0.0f;
            case "backLeft60Collision_norm" -> (collisions != null) ? (float) collisions.backLeft60Collision_norm : 0.0f;
            case "fwdLeft60Collision_norm" -> (collisions != null) ? (float) collisions.fwdLeft60Collision_norm : 0.0f;
            case "fwdLeft45Collision_norm" -> (collisions != null) ? (float) collisions.fwdLeft45Collision_norm : 0.0f;
            case "fwdLeft30Collision_norm" -> (collisions != null) ? (float) collisions.fwdLeft30Collision_norm : 0.0f;

            // Collision — world-axis cardinal
            case "posXCollision_norm" -> (collisions != null) ? (float) collisions.posXCollision_norm : 0.0f;
            case "negXCollision_norm" -> (collisions != null) ? (float) collisions.negXCollision_norm : 0.0f;
            case "posYCollision_norm" -> (collisions != null) ? (float) collisions.posYCollision_norm : 0.0f;
            case "negYCollision_norm" -> (collisions != null) ? (float) collisions.negYCollision_norm : 0.0f;

            // Collision — world-axis diagonal
            case "posXPosY30Collision_norm" -> (collisions != null) ? (float) collisions.posXPosY30Collision_norm : 0.0f;
            case "posXPosY45Collision_norm" -> (collisions != null) ? (float) collisions.posXPosY45Collision_norm : 0.0f;
            case "posXPosY60Collision_norm" -> (collisions != null) ? (float) collisions.posXPosY60Collision_norm : 0.0f;
            case "negXPosY60Collision_norm" -> (collisions != null) ? (float) collisions.negXPosY60Collision_norm : 0.0f;
            case "negXPosY45Collision_norm" -> (collisions != null) ? (float) collisions.negXPosY45Collision_norm : 0.0f;
            case "negXPosY30Collision_norm" -> (collisions != null) ? (float) collisions.negXPosY30Collision_norm : 0.0f;
            case "negXNegY30Collision_norm" -> (collisions != null) ? (float) collisions.negXNegY30Collision_norm : 0.0f;
            case "negXNegY45Collision_norm" -> (collisions != null) ? (float) collisions.negXNegY45Collision_norm : 0.0f;
            case "negXNegY60Collision_norm" -> (collisions != null) ? (float) collisions.negXNegY60Collision_norm : 0.0f;
            case "posXNegY60Collision_norm" -> (collisions != null) ? (float) collisions.posXNegY60Collision_norm : 0.0f;
            case "posXNegY45Collision_norm" -> (collisions != null) ? (float) collisions.posXNegY45Collision_norm : 0.0f;
            case "posXNegY30Collision_norm" -> (collisions != null) ? (float) collisions.posXNegY30Collision_norm : 0.0f;

            // Floor/drop probes
            case "fwdFloorDelta_norm" -> (collisions != null) ? (float) collisions.fwdFloorDelta_norm : 0.0f;
            case "fwdRightFloorDelta_norm" -> (collisions != null) ? (float) collisions.fwdRightFloorDelta_norm : 0.0f;
            case "rightFloorDelta_norm" -> (collisions != null) ? (float) collisions.rightFloorDelta_norm : 0.0f;
            case "backRightFloorDelta_norm" -> (collisions != null) ? (float) collisions.backRightFloorDelta_norm : 0.0f;
            case "backFloorDelta_norm" -> (collisions != null) ? (float) collisions.backFloorDelta_norm : 0.0f;
            case "backLeftFloorDelta_norm" -> (collisions != null) ? (float) collisions.backLeftFloorDelta_norm : 0.0f;
            case "leftFloorDelta_norm" -> (collisions != null) ? (float) collisions.leftFloorDelta_norm : 0.0f;
            case "fwdLeftFloorDelta_norm" -> (collisions != null) ? (float) collisions.fwdLeftFloorDelta_norm : 0.0f;
            case "fwdLowCollision_norm" -> (collisions != null) ? (float) collisions.fwdLowCollision_norm : 0.0f;
            case "fwdRightLowCollision_norm" -> (collisions != null) ? (float) collisions.fwdRightLowCollision_norm : 0.0f;
            case "rightLowCollision_norm" -> (collisions != null) ? (float) collisions.rightLowCollision_norm : 0.0f;
            case "backRightLowCollision_norm" -> (collisions != null) ? (float) collisions.backRightLowCollision_norm : 0.0f;
            case "backLowCollision_norm" -> (collisions != null) ? (float) collisions.backLowCollision_norm : 0.0f;
            case "backLeftLowCollision_norm" -> (collisions != null) ? (float) collisions.backLeftLowCollision_norm : 0.0f;
            case "leftLowCollision_norm" -> (collisions != null) ? (float) collisions.leftLowCollision_norm : 0.0f;
            case "fwdLeftLowCollision_norm" -> (collisions != null) ? (float) collisions.fwdLeftLowCollision_norm : 0.0f;

            // Velocity
            case "speed_norm" -> (player != null) ? player.speed_norm : 0.0f;
            case "forwardVelocity_norm" -> (player != null) ? player.forwardVelocity_norm : 0.0f;
            case "rightVelocity_norm" -> (player != null) ? player.rightVelocity_norm : 0.0f;
            case "velocityX_norm" -> (player != null) ? player.velocityX_norm : 0.0f;
            case "velocityY_norm" -> (player != null) ? player.velocityY_norm : 0.0f;
            case "velocityZ_norm" -> (player != null) ? player.velocityZ_norm : 0.0f;

            // Acceleration
            case "accelerationX_norm" -> (player != null) ? player.accelerationX_norm : 0.0f;
            case "accelerationY_norm" -> (player != null) ? player.accelerationY_norm : 0.0f;
            case "accelerationZ_norm" -> (player != null) ? player.accelerationZ_norm : 0.0f;
            case "forwardAcceleration_norm" -> (player != null) ? player.forwardAcceleration_norm : 0.0f;
            case "rightAcceleration_norm" -> (player != null) ? player.rightAcceleration_norm : 0.0f;
            case "forwardAccelVelocityMismatch_norm" -> (player != null) ? player.forwardAccelVelocityMismatch_norm : 0.0f;
            case "rightAccelVelocityMismatch_norm" -> (player != null) ? player.rightAccelVelocityMismatch_norm : 0.0f;

            // ViewRotation
            case "viewRotationX_sin" -> (player != null && player.viewRotation != null) ? (float) player.viewRotation.x_sin : 0.0f;
            case "viewRotationX_cos" -> (player != null && player.viewRotation != null) ? (float) player.viewRotation.x_cos : 0.0f;
            case "viewRotationY_norm" -> (player != null && player.viewRotation != null) ? (float) player.viewRotation.y_norm : 0.0f;

            // Status
            case "hasFlag" -> (player != null) ? player.hasFlag_norm : 0.0f;
            case "visible" -> (player != null && player.enemyVisible) ? 1.0f : 0.0f;
            case "health_norm" -> (player != null && player.health > 0) ? Math.min(1.0f, player.health / 100.0f) : 0.0f;
            case "dodgeCooldown_norm" -> (player != null) ? player.dodgeCooldownNorm : 1.0f;
            case "timeSinceLastMove_norm" -> (player != null) ? player.timeSinceLastMoveNorm : 0.0f;
            case "bFeigningDeath" -> (player != null && player.bFeigningDeath) ? 1.0f : 0.0f;

            // Dodge state (legacy one-hot binary)
            case "dodgeDirForward" -> (player != null && player.dodgeState == DodgeState.FORWARD) ? 1.0f : 0.0f;
            case "dodgeDirBack"    -> (player != null && player.dodgeState == DodgeState.BACK)    ? 1.0f : 0.0f;
            case "dodgeDirLeft"    -> (player != null && player.dodgeState == DodgeState.LEFT)    ? 1.0f : 0.0f;
            case "dodgeDirRight"   -> (player != null && player.dodgeState == DodgeState.RIGHT)   ? 1.0f : 0.0f;
            case "dodgeActive"     -> (player != null && player.dodgeState == DodgeState.ACTIVE)  ? 1.0f : 0.0f;
            case "dodgeCooldown"   -> (player != null && player.dodgeState == DodgeState.DONE)    ? 1.0f : 0.0f;

            // Dodge direction (continuous, world-space)
            case "dodgeDir_sin" -> PlayerSlotDodgeTracker.dodgeDirComponent(player, true);
            case "dodgeDir_cos" -> PlayerSlotDodgeTracker.dodgeDirComponent(player, false);

            // Physics state (one-hot)
            case "physics_isWalking"  -> (player != null && player.physics == Ut99PhysicsType.WALKING)  ? 1.0f : 0.0f;
            case "physics_isFalling"  -> (player != null && player.physics == Ut99PhysicsType.FALLING)  ? 1.0f : 0.0f;
            case "physics_isSwimming" -> (player != null && player.physics == Ut99PhysicsType.SWIMMING) ? 1.0f : 0.0f;

            // Submersion / breath
            case "headUnderwater" -> (player != null && player.headUnderwater) ? 1.0f : 0.0f;
            case "breathRemaining_norm" -> (player != null) ? player.breathRemaining : 1.0f;

            default -> null;
        };
    }

}
