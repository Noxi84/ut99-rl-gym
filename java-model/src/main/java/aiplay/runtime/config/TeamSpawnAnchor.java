package aiplay.runtime.config;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.SpawnPointDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-team median spawn-Z anchor for a given map. Reads the team-aware spawn-Z
 * median via {@link MapSpawnPointsResolver}, caches per (mapKey, team), and
 * returns the value as anchor for the {@code self_zAboveSpawn_norm} feature.
 *
 * <p>Caller passes the map key explicitly — use
 * {@link aiplay.runtime.context.MapKey#active()} for the currently active map.
 *
 * <p>Waarom een team-mediaan? Op CTF-maps zit elk team's spawn-cluster op een
 * eigen Z-niveau (bv. CTF-andACTION: alle blauwe spawns op Z=-960). Een team-
 * relatieve anchor maakt de feature voor beide teams symmetrisch ("ik ben N UU
 * boven mijn base-niveau") en map-invariant. Op symmetrische CTF-maps (Coret,
 * Face) is de team-mediaan voor beide teams identiek.
 *
 * <p>Fallback: wanneer er geen spawns voor de gevraagde team bestaan (DM-maps
 * met team=255 / mixed-team setups), gebruikt de utility de mediaan over alle
 * spawn-points.
 */
public final class TeamSpawnAnchor {

    private static final ConcurrentHashMap<String, Double> CACHE = new ConcurrentHashMap<>();

    private TeamSpawnAnchor() {}

    /**
     * Median Z of team-spawnpoints for the given map. Falls back to the
     * map-wide median when no team-specific spawns are found.
     *
     * @param mapKey map identifier (typically {@code MapKey.active()})
     * @param team   team-id (0=red, 1=blue, otherwise → fallback)
     * @return median Z in raw UU
     */
    public static double medianTeamSpawnZ(String mapKey, int team) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        String cacheKey = mapKey + "#" + team;
        return CACHE.computeIfAbsent(cacheKey, k -> computeMedianTeamSpawnZ(mapKey, team));
    }

    private static double computeMedianTeamSpawnZ(String mapKey, int team) {
        List<SpawnPointDto> spawns = MapSpawnPointsResolver.resolve(mapKey);
        List<Double> teamZ = new ArrayList<>();
        List<Double> allZ = new ArrayList<>();
        for (SpawnPointDto sp : spawns) {
            CoordinatesDto loc = sp.location;
            if (loc == null) continue;
            allZ.add(loc.z);
            if (sp.team == team) {
                teamZ.add(loc.z);
            }
        }
        List<Double> pick = !teamZ.isEmpty() ? teamZ : allZ;
        if (pick.isEmpty()) {
            return 0.0;
        }
        Collections.sort(pick);
        int n = pick.size();
        if (n % 2 == 1) {
            return pick.get(n / 2);
        }
        return 0.5 * (pick.get(n / 2 - 1) + pick.get(n / 2));
    }
}
