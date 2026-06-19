package aiplay.shared.field;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

/**
 * Shared half-field geometry along the home→enemy base axis.
 *
 * <p>Single source for both the reward path (java-rewards {@code RewardUtils} delegates here) and the
 * carrier staging-zone gate (java-model {@code CarrierObjectiveResolver}), so threat detection and map
 * scale cannot drift between the {@code navTarget} feature input and the dense {@code objective_progress}
 * reward (see CLAUDE.md "Objective dual source").
 */
public final class HalfFieldGeometry {

    private HalfFieldGeometry() {
    }

    /**
     * Maximum penetration of any LIVING enemy into the bot's own half along the home→enemy axis.
     * {@code 0.0} means no living enemy is past midfield onto our side, {@code 1.0} means an enemy is
     * at our home base. Dead enemies are ignored (a corpse is not a threat). Returns {@code 0.0} when
     * the team, bases, or enemy list cannot be resolved.
     */
    public static double enemyDepthInOwnHalf(GameStateDto state) {
        if (state == null || state.playerPawn == null || state.enemies == null) {
            return 0.0;
        }
        PlayerDto pawn = state.playerPawn;
        FlagDto ownFlag = (pawn.team == 0) ? state.redFlag : state.blueFlag;
        FlagDto enemyFlag = (pawn.team == 0) ? state.blueFlag : state.redFlag;
        if (ownFlag == null || ownFlag.baseLocation == null
                || enemyFlag == null || enemyFlag.baseLocation == null) {
            return 0.0;
        }
        CoordinatesDto home = ownFlag.baseLocation;
        CoordinatesDto enemyBase = enemyFlag.baseLocation;
        double axisX = enemyBase.x - home.x;
        double axisY = enemyBase.y - home.y;
        double axisLenSq = axisX * axisX + axisY * axisY;
        if (axisLenSq < 1e-9) {
            return 0.0;
        }
        double maxDepth = 0.0;
        for (PlayerDto e : state.enemies) {
            if (e == null || e.location == null || e.health <= 0) {
                continue;
            }
            // t = 0 at home base, 1 at enemy base
            double t = ((e.location.x - home.x) * axisX + (e.location.y - home.y) * axisY) / axisLenSq;
            if (t < 0.5) {
                // depth into own half: 0 at midfield (t=0.5), 1 at home base (t=0)
                double depth = (0.5 - t) * 2.0;
                if (depth > maxDepth) {
                    maxDepth = depth;
                }
            }
        }
        return Math.min(1.0, maxDepth);
    }

    /** 2D distance (UU) between the red and blue flag base locations, or {@code 0.0} if unavailable. */
    public static double interBaseDistance(GameStateDto state) {
        if (state == null) {
            return 0.0;
        }
        FlagDto red = state.redFlag;
        FlagDto blue = state.blueFlag;
        if (red != null && red.baseLocation != null && blue != null && blue.baseLocation != null) {
            double dx = red.baseLocation.x - blue.baseLocation.x;
            double dy = red.baseLocation.y - blue.baseLocation.y;
            return Math.sqrt(dx * dx + dy * dy);
        }
        return 0.0;
    }
}
