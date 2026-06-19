package aiplay.scanners.feature.resolver.mover;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.MoverDto;
import aiplay.dto.PlayerDto;
import aiplay.runtime.config.MapMoversResolver;
import aiplay.runtime.config.MapMoversResolver.StaticMover;
import aiplay.runtime.context.MapKey;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.util.NormalizationUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches each frame's {@link GameStateDto#movers} list by:
 * <ol>
 *   <li>Matching runtime MoverDto (from UDP) to static map data via nameHash</li>
 *   <li>Populating static fields (keyPositions, platformBounds, moveTime, etc.)</li>
 *   <li>Computing egocentric spatial features (bearing, distance, zOffset, onPlatform, etc.)</li>
 *   <li>Sorting by distance and keeping only the top N nearest</li>
 * </ol>
 */
public class MoverEnricher implements TrainingFeatureEnricher {

    public static final int MAX_SLOTS = 4;

    private static final double DIST_TAU = 600.0;
    private static final double Z_NORM_HALF = 512.0;
    private static final double ON_PLATFORM_Z_THRESHOLD = 80.0;
    private static final double TIME_NORM_TAU = 5.0;
    private static final double TRAVEL_RANGE_TAU = 600.0;

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;
        String mapKey = MapKey.active();
        List<StaticMover> statics = MapMoversResolver.resolve(mapKey);
        Map<Integer, StaticMover> byHash = buildHashMap(statics);
        for (GameStateDto f : frames) {
            if (f != null) enrichOne(f, byHash);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        String mapKey = MapKey.active();
        List<StaticMover> statics = MapMoversResolver.resolve(mapKey);
        Map<Integer, StaticMover> byHash = buildHashMap(statics);
        for (GameStateDto f : frames) {
            if (f != null) enrichOne(f, byHash);
        }
    }

    private void enrichOne(GameStateDto f, Map<Integer, StaticMover> staticsByHash) {
        PlayerDto self = f.playerPawn;
        if (self == null || self.location == null || self.viewRotation == null) return;

        List<MoverDto> runtimeMovers = f.movers;
        if (runtimeMovers == null || runtimeMovers.isEmpty()) {
            f.movers = List.of();
            return;
        }

        CoordinatesDto bot = self.location;
        final int viewYawX = self.viewRotation.x & 0xFFFF;

        List<MoverWithDist> sorted = new ArrayList<>(runtimeMovers.size());
        for (MoverDto m : runtimeMovers) {
            StaticMover sm = staticsByHash.get(m.nameHash);
            if (sm != null) {
                m.keyPositions = sm.keyPositions();
                m.platformBoundsMin = sm.boundsMin();
                m.platformBoundsMax = sm.boundsMax();
                m.moveTime = sm.moveTime();
                m.stayOpenTime = sm.stayOpenTime();
            }

            double dx = m.locX - bot.x;
            double dy = m.locY - bot.y;
            double dz = m.locZ - bot.z;
            double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);

            computeEgocentricFeatures(m, bot, viewYawX, dist3D);
            sorted.add(new MoverWithDist(m, dist3D));
        }

        sorted.sort(Comparator.comparingDouble(mwd -> mwd.dist3D));
        int n = Math.min(MAX_SLOTS, sorted.size());
        List<MoverDto> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(sorted.get(i).mover);
        }
        f.movers = result;
    }

    private static void computeEgocentricFeatures(
            MoverDto m, CoordinatesDto bot, int viewYawX, double dist3D) {
        double dx = m.locX - bot.x;
        double dy = m.locY - bot.y;
        double dist2D = Math.hypot(dx, dy);
        double soft2D = NormalizationUtils.softDistance01(dist2D, DIST_TAU);

        double[] sc = NormalizationUtils.relativeAngleSinCos(viewYawX, bot.x, bot.y, m.locX, m.locY);
        double[] scStab = NormalizationUtils.stabilizeSinCosNear(sc[0], sc[1], soft2D, true);
        double sin = scStab[0], cos = scStab[1];
        double[] fr = NormalizationUtils.forwardRightDistNorm(sin, cos, soft2D);

        m.relSin = sin;
        m.relCos = cos;
        m.distanceNorm = NormalizationUtils.softDistance01(dist3D, DIST_TAU);
        m.forwardDistNorm = fr[0];
        m.rightDistNorm = fr[1];
        m.zOffsetNorm = Math.tanh((m.locZ - bot.z) / Z_NORM_HALF);

        // onPlatform: bot is within the mover's AABB (extended by threshold in Z)
        m.onPlatform = isOnPlatform(m, bot);

        // Destination features: where is the mover going?
        computeDestinationFeatures(m, bot, viewYawX);

        // Travel range: total vertical distance between first and last key position
        computeTravelRange(m);
    }

    private static boolean isOnPlatform(MoverDto m, CoordinatesDto bot) {
        if (m.platformBoundsMin == null || m.platformBoundsMax == null) return false;

        double relX = bot.x - m.locX;
        double relY = bot.y - m.locY;
        double relZ = bot.z - m.locZ;

        return relX >= m.platformBoundsMin[0] && relX <= m.platformBoundsMax[0]
            && relY >= m.platformBoundsMin[1] && relY <= m.platformBoundsMax[1]
            && relZ >= m.platformBoundsMin[2] - ON_PLATFORM_Z_THRESHOLD
            && relZ <= m.platformBoundsMax[2] + ON_PLATFORM_Z_THRESHOLD;
    }

    private static void computeDestinationFeatures(MoverDto m, CoordinatesDto bot, int viewYawX) {
        if (m.keyPositions == null || m.keyPositions.length < 2) {
            m.destZOffsetNorm = 0;
            m.destDistanceNorm = 0;
            m.timeToArriveNorm = 0;
            return;
        }

        // Destination = the keyframe the mover is heading towards.
        // If opening: keyNum is the target. If not: prevKeyNum or key 0 (returning).
        int destKey = m.opening ? m.keyNum : (m.keyNum == 0 ? 0 : 0);
        if (m.opening && m.keyNum < m.keyPositions.length) {
            destKey = m.keyNum;
        } else if (!m.opening && m.numKeys >= 2) {
            // Returning to base (key 0) or at rest
            destKey = 0;
        }
        destKey = Math.min(destKey, m.keyPositions.length - 1);
        double[] dest = m.keyPositions[destKey];

        double ddx = dest[0] - bot.x;
        double ddy = dest[1] - bot.y;
        double ddz = dest[2] - bot.z;
        double destDist3D = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);

        m.destZOffsetNorm = Math.tanh((dest[2] - bot.z) / Z_NORM_HALF);
        m.destDistanceNorm = NormalizationUtils.softDistance01(destDist3D, DIST_TAU);

        // Time to arrive: remaining movement time based on moveProgress
        double remainingProgress = m.opening ? (1.0 - m.moveProgress) : m.moveProgress;
        double remainingTime = remainingProgress * m.moveTime;
        m.timeToArriveNorm = 1.0 - Math.exp(-remainingTime / TIME_NORM_TAU);
    }

    private static void computeTravelRange(MoverDto m) {
        if (m.keyPositions == null || m.keyPositions.length < 2) {
            m.travelRangeNorm = 0;
            return;
        }
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] kp : m.keyPositions) {
            if (kp[2] < minZ) minZ = kp[2];
            if (kp[2] > maxZ) maxZ = kp[2];
        }
        double range = maxZ - minZ;
        m.travelRangeNorm = NormalizationUtils.softDistance01(range, TRAVEL_RANGE_TAU);
    }

    private static Map<Integer, StaticMover> buildHashMap(List<StaticMover> statics) {
        Map<Integer, StaticMover> map = new HashMap<>(statics.size() * 2);
        for (StaticMover sm : statics) {
            map.put(sm.nameHash(), sm);
        }
        return map;
    }

    private record MoverWithDist(MoverDto mover, double dist3D) {}
}
