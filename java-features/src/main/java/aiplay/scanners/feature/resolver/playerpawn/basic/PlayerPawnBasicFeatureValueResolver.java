package aiplay.scanners.feature.resolver.playerpawn.basic;

import aiplay.runtime.config.TeamSpawnAnchor;
import aiplay.dto.CollisionsDto;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.MapKey;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.PlayerDtoFeatureResolver;
import aiplay.util.NormalizationUtils;

public class PlayerPawnBasicFeatureValueResolver implements TrainingFeatureValueResolver {

    private static final boolean DEBUG_LOG = Boolean.getBoolean("ut99.debug.viewrotation");
    private static final String SELF_PREFIX = "self_";

    /** Tanh-schaal voor self_zAboveSpawn_norm. Bij dz=1024 → 0.76, dz=2048 → 0.96.
     *  Past per-map playable Z-span (mediaan 1124 UU over 10 maps, Morpheus/Face tot 3100 UU). */
    private static final double Z_ABOVE_SPAWN_TANH_SCALE = 1024.0;

    private long diagCount = 0;

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        if (featureId == null || !featureId.startsWith(SELF_PREFIX)) {
            return null;
        }
        String suffix = featureId.substring(SELF_PREFIX.length());

        CollisionsDto collisions = null;
        if (f != null && f.playerPawn != null) {
            collisions = f.playerPawn.collisions;
        }

        // Player-specific features (raw values, special naming, or extra logic)
        switch (suffix) {
            // Raw location (unnormalized)
            case "locationX":
                return (f.playerPawn != null && f.playerPawn.location != null) ? (float) f.playerPawn.location.x : 0.0f;
            case "locationY":
                return (f.playerPawn != null && f.playerPawn.location != null) ? (float) f.playerPawn.location.y : 0.0f;
            case "locationZ":
                return (f.playerPawn != null && f.playerPawn.location != null) ? (float) f.playerPawn.location.z : 0.0f;

            // Raw collision (unnormalized)
            case "backCollision":
                return (collisions != null) ? (float) collisions.backCollision : 0.0f;
            case "fwdCollision":
                return (collisions != null) ? (float) collisions.fwdCollision : 0.0f;
            case "leftCollision":
                return (collisions != null) ? (float) collisions.leftCollision : 0.0f;
            case "rightCollision":
                return (collisions != null) ? (float) collisions.rightCollision : 0.0f;

            case "posXCollision":
                return (collisions != null) ? (float) collisions.posXCollision : 0.0f;
            case "negXCollision":
                return (collisions != null) ? (float) collisions.negXCollision : 0.0f;
            case "posYCollision":
                return (collisions != null) ? (float) collisions.posYCollision : 0.0f;
            case "negYCollision":
                return (collisions != null) ? (float) collisions.negYCollision : 0.0f;

            case "fwdRight30Collision":
                return (collisions != null) ? (float) collisions.fwdRight30Collision : 0.0f;
            case "fwdRight45Collision":
                return (collisions != null) ? (float) collisions.fwdRight45Collision : 0.0f;
            case "fwdRight60Collision":
                return (collisions != null) ? (float) collisions.fwdRight60Collision : 0.0f;
            case "backRight60Collision":
                return (collisions != null) ? (float) collisions.backRight60Collision : 0.0f;
            case "backRight45Collision":
                return (collisions != null) ? (float) collisions.backRight45Collision : 0.0f;
            case "backRight30Collision":
                return (collisions != null) ? (float) collisions.backRight30Collision : 0.0f;
            case "backLeft30Collision":
                return (collisions != null) ? (float) collisions.backLeft30Collision : 0.0f;
            case "backLeft45Collision":
                return (collisions != null) ? (float) collisions.backLeft45Collision : 0.0f;
            case "backLeft60Collision":
                return (collisions != null) ? (float) collisions.backLeft60Collision : 0.0f;
            case "fwdLeft60Collision":
                return (collisions != null) ? (float) collisions.fwdLeft60Collision : 0.0f;
            case "fwdLeft45Collision":
                return (collisions != null) ? (float) collisions.fwdLeft45Collision : 0.0f;
            case "fwdLeft30Collision":
                return (collisions != null) ? (float) collisions.fwdLeft30Collision : 0.0f;

            case "posXPosY30Collision":
                return (collisions != null) ? (float) collisions.posXPosY30Collision : 0.0f;
            case "posXPosY45Collision":
                return (collisions != null) ? (float) collisions.posXPosY45Collision : 0.0f;
            case "posXPosY60Collision":
                return (collisions != null) ? (float) collisions.posXPosY60Collision : 0.0f;
            case "negXPosY60Collision":
                return (collisions != null) ? (float) collisions.negXPosY60Collision : 0.0f;
            case "negXPosY45Collision":
                return (collisions != null) ? (float) collisions.negXPosY45Collision : 0.0f;
            case "negXPosY30Collision":
                return (collisions != null) ? (float) collisions.negXPosY30Collision : 0.0f;
            case "negXNegY30Collision":
                return (collisions != null) ? (float) collisions.negXNegY30Collision : 0.0f;
            case "negXNegY45Collision":
                return (collisions != null) ? (float) collisions.negXNegY45Collision : 0.0f;
            case "negXNegY60Collision":
                return (collisions != null) ? (float) collisions.negXNegY60Collision : 0.0f;
            case "posXNegY60Collision":
                return (collisions != null) ? (float) collisions.posXNegY60Collision : 0.0f;
            case "posXNegY45Collision":
                return (collisions != null) ? (float) collisions.posXNegY45Collision : 0.0f;
            case "posXNegY30Collision":
                return (collisions != null) ? (float) collisions.posXNegY30Collision : 0.0f;

            case "fwdFloorDelta":
                return (collisions != null) ? (float) collisions.fwdFloorDelta : 0.0f;
            case "fwdRightFloorDelta":
                return (collisions != null) ? (float) collisions.fwdRightFloorDelta : 0.0f;
            case "rightFloorDelta":
                return (collisions != null) ? (float) collisions.rightFloorDelta : 0.0f;
            case "backRightFloorDelta":
                return (collisions != null) ? (float) collisions.backRightFloorDelta : 0.0f;
            case "backFloorDelta":
                return (collisions != null) ? (float) collisions.backFloorDelta : 0.0f;
            case "backLeftFloorDelta":
                return (collisions != null) ? (float) collisions.backLeftFloorDelta : 0.0f;
            case "leftFloorDelta":
                return (collisions != null) ? (float) collisions.leftFloorDelta : 0.0f;
            case "fwdLeftFloorDelta":
                return (collisions != null) ? (float) collisions.fwdLeftFloorDelta : 0.0f;

            case "fwdLowCollision":
                return (collisions != null) ? (float) collisions.fwdLowCollision : 0.0f;
            case "fwdRightLowCollision":
                return (collisions != null) ? (float) collisions.fwdRightLowCollision : 0.0f;
            case "rightLowCollision":
                return (collisions != null) ? (float) collisions.rightLowCollision : 0.0f;
            case "backRightLowCollision":
                return (collisions != null) ? (float) collisions.backRightLowCollision : 0.0f;
            case "backLowCollision":
                return (collisions != null) ? (float) collisions.backLowCollision : 0.0f;
            case "backLeftLowCollision":
                return (collisions != null) ? (float) collisions.backLeftLowCollision : 0.0f;
            case "leftLowCollision":
                return (collisions != null) ? (float) collisions.leftLowCollision : 0.0f;
            case "fwdLeftLowCollision":
                return (collisions != null) ? (float) collisions.fwdLeftLowCollision : 0.0f;

            // Egocentric vertical probes (raw UU + normalized).
            case "floorBelow":
                return (collisions != null) ? (float) collisions.floorBelow : 0.0f;
            case "ceilingAbove":
                return (collisions != null) ? (float) collisions.ceilingAbove : 0.0f;
            case "floorBelow_norm":
                return (collisions != null) ? (float) collisions.floorBelow_norm : 0.0f;
            case "ceilingAbove_norm":
                return (collisions != null) ? (float) collisions.ceilingAbove_norm : 0.0f;

            // Raw viewRotation (unnormalized)
            case "viewRotationX":
                return (f.playerPawn != null && f.playerPawn.viewRotation != null) ? (float) f.playerPawn.viewRotation.x : 0.0f;
            case "viewRotationY":
                return (f.playerPawn != null && f.playerPawn.viewRotation != null) ? (float) f.playerPawn.viewRotation.y : 0.0f;

            // viewRotationX_sin with debug logging (overrides shared version)
            case "viewRotationX_sin": {
                if (f.playerPawn != null && f.playerPawn.viewRotation != null) {
                    float sin = (float) f.playerPawn.viewRotation.x_sin;
                    if (DEBUG_LOG && (diagCount++ < 5 || diagCount % 5000 == 0)) {
                        System.out.println("VR_RESOLVE_DIAG x=" + f.playerPawn.viewRotation.x
                            + " x_sin=" + f.playerPawn.viewRotation.x_sin
                            + " x_cos=" + f.playerPawn.viewRotation.x_cos
                            + " y_norm=" + f.playerPawn.viewRotation.y_norm
                            + " ts=" + f.timestampMillis);
                    }
                    return sin;
                }
                return 0.0f;
            }

            // Raw dodge state
            case "dodgeState":
                return (f.playerPawn != null && f.playerPawn.dodgeState != null) ? (float) f.playerPawn.dodgeState.value : 0.0f;

            // Self-specific dodge one-hot
            case "dodgeDirForward":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.FORWARD) ? 1.0f : 0.0f;
            case "dodgeDirBack":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.BACK) ? 1.0f : 0.0f;
            case "dodgeDirLeft":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.LEFT) ? 1.0f : 0.0f;
            case "dodgeDirRight":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.RIGHT) ? 1.0f : 0.0f;
            case "dodgeActive":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.ACTIVE) ? 1.0f : 0.0f;
            case "dodgeCooldown":
                return (f.playerPawn != null && f.playerPawn.dodgeState == DodgeState.DONE) ? 1.0f : 0.0f;

            // Egocentric verticale offset t.o.v. team-spawn mediaan (map-onafhankelijk,
            // tanh-geschaald op 1024 UU). Positief = bot zit boven team-base niveau.
            case "zAboveSpawn_norm": {
                if (f.playerPawn == null || f.playerPawn.location == null) {
                    return 0.0f;
                }
                double anchorZ = TeamSpawnAnchor.medianTeamSpawnZ(MapKey.fromFrame(f), f.playerPawn.team);
                double dz = f.playerPawn.location.z - anchorZ;
                if (!Double.isFinite(dz)) return 0.0f;
                return (float) NormalizationUtils.clampM11(Math.tanh(dz / Z_ABOVE_SPAWN_TANH_SCALE));
            }

            // Player-only
            case "team_norm":
                return (f.playerPawn != null) ? (float) f.playerPawn.team : 0.0f;
            case "bIsSpectator":
                return (f.playerPawn != null && f.playerPawn.bIsSpectator) ? 1.0f : 0.0f;
            case "bIsABot":
                return (f.playerPawn != null && f.playerPawn.bIsABot) ? 1.0f : 0.0f;
            case "bWaitingPlayer":
                return (f.playerPawn != null && f.playerPawn.bWaitingPlayer) ? 1.0f : 0.0f;

            default:
                break;
        }

        // Delegate to shared PlayerDto resolver for all common features
        // (collision _norm, velocity, acceleration, viewRotation, location _norm, etc.)
        return PlayerDtoFeatureResolver.resolve(suffix, f.playerPawn);
    }
}
