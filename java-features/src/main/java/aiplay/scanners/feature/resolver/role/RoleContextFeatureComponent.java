package aiplay.scanners.feature.resolver.role;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.util.NormalizationUtils;
import java.util.List;
import java.util.Set;

/**
 * Role-tactical context features for movement.
 *
 * <p>Three observable signals that let the policy condition on the strategic
 * picture without baking the role into reward shaping alone:
 *
 * <ul>
 *   <li>{@code self_proximityToOwnFlag_norm} — exp-decay proximity to the
 *       bot's own flag location (current location when carried/dropped, base
 *       when home). 1.0 at the flag, → 0.0 far away (tau = 400 UU).
 *       Used by Defender to "stay near base/flag".</li>
 *   <li>{@code enemy_depthInOwnHalf_norm} — deepest enemy's penetration
 *       into the bot's half along the home→enemy axis. 0.0 = no enemy past
 *       midfield into our side, 1.0 = enemy at our home base. Used by
 *       Defender to know when to engage (e.g. > 0.25 → past 25% midfield).
 *       Robust to map asymmetry because everything is scaled to the
 *       home→enemy axis length.</li>
 *   <li>{@code teammateCarrier_proximity_norm} — exp-decay proximity to
 *       the teammate currently carrying the enemy flag. 0.0 if no teammate
 *       carries (tau = 400 UU). Used by Cover/Attacker to escort the
 *       carrier even when nominal role differs.</li>
 * </ul>
 */
@TrainingFeatureComponent(priority = 10)
public class RoleContextFeatureComponent implements ITrainingFeature {

    private static final double PROXIMITY_TAU_UU = 400.0;

    private static final Set<String> FEATURE_IDS = Set.of(
        "self_proximityToOwnFlag_norm",
        "enemy_depthInOwnHalf_norm",
        "teammateCarrier_proximity_norm"
    );

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(
            String sessionId,
            String modelKey,
            String featureId,
            GameStateDto frame) {
        return resolve(featureId, frame);
    }

    @Override
    public Float resolveCsvWriterFeatureValue(
            String modelKey,
            String sessionId,
            String featureId,
            List<GameStateDto> gameStates,
            GameStateDto current) {
        return resolve(featureId, current);
    }

    private static Float resolve(String featureId, GameStateDto frame) {
        if (frame == null || frame.playerPawn == null || frame.playerPawn.location == null) {
            return 0.0f;
        }
        return switch (featureId) {
            case "self_proximityToOwnFlag_norm" -> proximityToOwnFlag(frame);
            case "enemy_depthInOwnHalf_norm" -> enemyDepthInOwnHalf(frame);
            case "teammateCarrier_proximity_norm" -> proximityToTeammateCarrier(frame);
            default -> 0.0f;
        };
    }

    private static float proximityToOwnFlag(GameStateDto frame) {
        PlayerDto pawn = frame.playerPawn;
        FlagDto ownFlag = (pawn.team == 0) ? frame.redFlag : frame.blueFlag;
        if (ownFlag == null) return 0.0f;
        CoordinatesDto target = (ownFlag.location != null) ? ownFlag.location : ownFlag.baseLocation;
        if (target == null) return 0.0f;
        double d = distance3D(pawn.location, target);
        return (float) (1.0 - NormalizationUtils.softDistance01(d, PROXIMITY_TAU_UU));
    }

    private static float enemyDepthInOwnHalf(GameStateDto frame) {
        PlayerDto pawn = frame.playerPawn;
        FlagDto ownFlag = (pawn.team == 0) ? frame.redFlag : frame.blueFlag;
        FlagDto enemyFlag = (pawn.team == 0) ? frame.blueFlag : frame.redFlag;
        if (ownFlag == null || enemyFlag == null
                || ownFlag.baseLocation == null || enemyFlag.baseLocation == null) {
            return 0.0f;
        }
        CoordinatesDto home = ownFlag.baseLocation;
        CoordinatesDto enemy = enemyFlag.baseLocation;
        double axisX = enemy.x - home.x;
        double axisY = enemy.y - home.y;
        double axisLenSq = axisX * axisX + axisY * axisY;
        if (axisLenSq < 1e-9) return 0.0f;

        if (frame.enemies == null) return 0.0f;
        double maxDepth = 0.0;
        for (PlayerDto e : frame.enemies) {
            if (e == null || e.location == null) continue;
            // t = 0 at home base, 1 at enemy base
            double t = ((e.location.x - home.x) * axisX + (e.location.y - home.y) * axisY) / axisLenSq;
            if (t < 0.5) {
                // depth into own half: 0 at midfield (t=0.5), 1 at home base (t=0)
                double depth = (0.5 - t) * 2.0;
                if (depth > maxDepth) maxDepth = depth;
            }
        }
        return (float) Math.min(1.0, maxDepth);
    }

    private static float proximityToTeammateCarrier(GameStateDto frame) {
        if (frame.teammates == null) return 0.0f;
        PlayerDto pawn = frame.playerPawn;
        for (PlayerDto t : frame.teammates) {
            if (t == null || !t.hasFlag || t.location == null) continue;
            double d = distance3D(pawn.location, t.location);
            return (float) (1.0 - NormalizationUtils.softDistance01(d, PROXIMITY_TAU_UU));
        }
        return 0.0f;
    }

    private static double distance3D(CoordinatesDto a, CoordinatesDto b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
