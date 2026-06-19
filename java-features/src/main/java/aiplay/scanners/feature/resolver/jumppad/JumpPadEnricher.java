package aiplay.scanners.feature.resolver.jumppad;

import aiplay.config.global.MapNormConfig;
import aiplay.runtime.config.ActiveMapConfigResolver;
import aiplay.runtime.config.MapJumpPadsResolver;
import aiplay.runtime.context.MapKey;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.JumpPadDto;
import aiplay.dto.JumpPadRelationDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.mission.MissionType;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.util.NormalizationUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes per-frame relative features for the N nearest jump pads on the active map.
 *
 * <p>For each frame: looks up the static pad list via {@link MapJumpPadsResolver},
 * sorts pads by 3D distance to the bot, and populates {@code playerPawn.enrichments.jumpPadRels}
 * with up to {@link #MAX_SLOTS} relations.
 *
 * <p>Predicted-landing computation uses ballistic motion under UT99 gravity:
 * {@code t_total = 2 * vz / g} (assuming landing at pad height), {@code landing = pad + v * t_total}.
 * This is approximate (real ground levels vary) but gives the model a directly usable
 * "where will this pad send me" signal so it doesn't have to learn ballistics.
 *
 * <p>The {@code landingTowardsGoal_cos} feature inspects the active mission to choose
 * the goal direction (CAPTURE → enemy base, RETURN/RECOVER → home base, INTERCEPT →
 * skipped, returns 0).
 */
public class JumpPadEnricher implements TrainingFeatureEnricher {

    /** Max jump pad slots — covers maps like CTF-w00tabulousFixed (11 pads). */
    public static final int MAX_SLOTS = 4;

    /** UT99 gravity for Pawns (UU/s²). Default region.zoneGravity = -950. */
    private static final double GRAVITY = 950.0;

    /** Distance soft-normalization tau, matched to the rest of the codebase (600 UU). */
    private static final double DIST_TAU = 600.0;

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;
        String mapKey = MapKey.active();
        List<JumpPadDto> pads = MapJumpPadsResolver.resolve(mapKey);
        MapNormConfig mapNorm = ActiveMapConfigResolver.resolve(mapKey);
        for (GameStateDto f : frames) {
            if (f != null) enrichOne(f, pads, mapNorm);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        String mapKey = MapKey.active();
        List<JumpPadDto> pads = MapJumpPadsResolver.resolve(mapKey);
        MapNormConfig mapNorm = ActiveMapConfigResolver.resolve(mapKey);
        for (GameStateDto f : frames) {
            if (f != null) enrichOne(f, pads, mapNorm);
        }
    }

    private void enrichOne(GameStateDto f, List<JumpPadDto> pads, MapNormConfig mapNorm) {
        PlayerDto self = f.playerPawn;
        if (self == null || self.location == null || self.viewRotation == null) return;

        // Allocate the slot array. Always present — a pad-less map (or no playerPawn) leaves
        // null entries that the value resolver will translate into present=0.
        if (self.enrichments.jumpPadRels == null
                || self.enrichments.jumpPadRels.length != MAX_SLOTS) {
            self.enrichments.jumpPadRels = new JumpPadRelationDto[MAX_SLOTS];
        } else {
            for (int i = 0; i < MAX_SLOTS; i++) self.enrichments.jumpPadRels[i] = null;
        }

        if (pads.isEmpty()) return;

        CoordinatesDto bot = self.location;
        final int viewYawX = self.viewRotation.x & 0xFFFF;

        // Sort pads by 3D distance to bot (small lists, simple sort)
        List<PadWithDist> sorted = new ArrayList<>(pads.size());
        for (JumpPadDto pad : pads) {
            if (pad == null || pad.location == null) continue;
            double dx = pad.location.x - bot.x;
            double dy = pad.location.y - bot.y;
            double dz = pad.location.z - bot.z;
            double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
            sorted.add(new PadWithDist(pad, dist3D));
        }
        sorted.sort(Comparator.comparingDouble(p -> p.dist3D));

        // Goal direction (world-space) for landingTowardsGoal_cos
        double[] goalDir = computeGoalDirection(f);

        int n = Math.min(MAX_SLOTS, sorted.size());
        for (int i = 0; i < n; i++) {
            JumpPadDto pad = sorted.get(i).pad;
            self.enrichments.jumpPadRels[i] = buildRelation(
                bot, viewYawX, pad, mapNorm, goalDir);
        }
    }

    private static JumpPadRelationDto buildRelation(
            CoordinatesDto bot, int viewYawX, JumpPadDto pad,
            MapNormConfig mapNorm, double[] goalDir) {
        JumpPadRelationDto rel = new JumpPadRelationDto();
        CoordinatesDto pl = pad.location;

        // Pad bearing in bot view-frame
        double dx = pl.x - bot.x;
        double dy = pl.y - bot.y;
        double dist2D = Math.hypot(dx, dy);
        double dist3D = Math.sqrt(dx * dx + dy * dy + (pl.z - bot.z) * (pl.z - bot.z));
        double soft2D = NormalizationUtils.softDistance01(dist2D, DIST_TAU);

        double[] sc = NormalizationUtils.relativeAngleSinCos(viewYawX, bot.x, bot.y, pl.x, pl.y);
        double[] scStab = NormalizationUtils.stabilizeSinCosNear(sc[0], sc[1], soft2D, true);
        double sin = scStab[0], cos = scStab[1];
        double[] fr = NormalizationUtils.forwardRightDistNorm(sin, cos, soft2D);

        rel.relSin = sin;
        rel.relCos = cos;
        rel.distance_norm = NormalizationUtils.normalizeDistance3D(dist3D);
        rel.forwardDist_norm = fr[0];
        rel.rightDist_norm = fr[1];
        rel.zOffset_norm = normalizeZOffset(pl.z - bot.z, mapNorm);

        // Predicted landing — assume lands at pad height: t_total = 2 * vz / g
        // (vz <= 0 means the pad is purely horizontal/downward; predicted landing collapses
        // to the pad itself which is also a useful signal for the model.)
        double vx = pad.velocity != null ? pad.velocity.x : 0.0;
        double vy = pad.velocity != null ? pad.velocity.y : 0.0;
        double vz = pad.velocity != null ? pad.velocity.z : 0.0;
        double tTotal = (vz > 0.0) ? 2.0 * vz / GRAVITY : 0.0;
        double landX = pl.x + vx * tTotal;
        double landY = pl.y + vy * tTotal;
        double landZ = pl.z;  // approximation: lands at launch height

        double ldx = landX - bot.x;
        double ldy = landY - bot.y;
        double landDist2D = Math.hypot(ldx, ldy);
        double landSoft2D = NormalizationUtils.softDistance01(landDist2D, DIST_TAU);

        double[] lsc = NormalizationUtils.relativeAngleSinCos(viewYawX, bot.x, bot.y, landX, landY);
        double[] lscStab = NormalizationUtils.stabilizeSinCosNear(lsc[0], lsc[1], landSoft2D, true);
        double[] lfr = NormalizationUtils.forwardRightDistNorm(lscStab[0], lscStab[1], landSoft2D);
        rel.landingForwardDist_norm = lfr[0];
        rel.landingRightDist_norm = lfr[1];
        rel.landingZOffset_norm = normalizeZOffset(landZ - bot.z, mapNorm);

        // landingTowardsGoal_cos: compare pad→landing direction with bot→goal direction.
        // If goalDir is null/zero (e.g. INTERCEPT mission), or the pad has no horizontal
        // displacement, return 0 (no information).
        double padDx = landX - pl.x;
        double padDy = landY - pl.y;
        double padNorm = Math.hypot(padDx, padDy);
        if (padNorm > 1e-3 && goalDir != null) {
            double padDxN = padDx / padNorm;
            double padDyN = padDy / padNorm;
            rel.landingTowardsGoal_cos = NormalizationUtils.clampM11(
                padDxN * goalDir[0] + padDyN * goalDir[1]);
        } else {
            rel.landingTowardsGoal_cos = 0.0;
        }

        return rel;
    }

    /** Returns world-space (dx, dy) unit vector to current mission goal, or null if undefined. */
    private static double[] computeGoalDirection(GameStateDto f) {
        if (f.playerPawn == null || f.playerPawn.location == null) return null;

        CoordinatesDto target = resolveGoalLocation(f);
        if (target == null) return null;

        double dx = target.x - f.playerPawn.location.x;
        double dy = target.y - f.playerPawn.location.y;
        double n = Math.hypot(dx, dy);
        if (n < 1e-3) return null;
        return new double[]{dx / n, dy / n};
    }

    private static CoordinatesDto resolveGoalLocation(GameStateDto f) {
        // Mission can be null in early frames before MissionAnnotator has run.
        // Default behavior in that case: target enemy flag base (offensive bias).
        MissionType m = f.annotatedMission;
        int playerTeam = f.playerPawn.team;

        if (m == MissionType.RETURN_HOME || m == MissionType.STUCK_RECOVER) {
            // Go home — target own flag base
            if (playerTeam == 1 && f.blueFlag != null) return f.blueFlag.baseLocation;
            if (playerTeam == 0 && f.redFlag != null) return f.redFlag.baseLocation;
        }
        if (m == MissionType.INTERCEPT_CARRIER) {
            // Carrier-relative; jump pads are tactical here. Skip directional goal.
            return null;
        }
        // Default + CAPTURE_FLAG: target enemy flag (current location, since it might be carried).
        if (playerTeam == 1 && f.redFlag != null) return f.redFlag.location;
        if (playerTeam == 0 && f.blueFlag != null) return f.blueFlag.location;
        return null;
    }

    private static double normalizeZOffset(double dz, MapNormConfig mapNorm) {
        double half = mapNorm.halfWidthZ();
        if (!Double.isFinite(half) || half <= 1e-9) return 0.0;
        return NormalizationUtils.clampM11(dz / half);
    }

    private record PadWithDist(JumpPadDto pad, double dist3D) {}
}
