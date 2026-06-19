package aiplay.runtime.config;

import aiplay.config.PropertyReaderUtils;
import aiplay.dto.SpawnPointDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the static spawn-points list for a given map.
 *
 * <p>Source file: {@code resources/config/maps/<mapKey>.json}, key {@code spawn_points[]}:
 * {@code {"location":[x,y,z], "rotation":[pitch,yaw,roll], "team":0|1}}
 *
 * <p>Caller passes the map key explicitly — use
 * {@link aiplay.runtime.context.MapKey#active()} for the currently active map.
 *
 * <p>The list is populated by {@code scripts/deploy/extract-map-bounds.sh}
 * (via {@code aiplay.ExtractMapBoundsMain}) and is identical to what the
 * UC webservice would otherwise serialize from {@code Level.NavigationPointList}.
 * Reading statically means Python tooling, tests, and startup paths no longer
 * need a running server to know the spawn positions.
 */
public final class MapSpawnPointsResolver {

    private static final ConcurrentHashMap<String, List<SpawnPointDto>> CACHE = new ConcurrentHashMap<>();

    private MapSpawnPointsResolver() {}

    public static List<SpawnPointDto> resolve(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, MapSpawnPointsResolver::load);
    }

    private static List<SpawnPointDto> load(String mapKey) {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) {
            throw new IllegalStateException(
                "No /maps section found in config (resources/config/maps/ should hold one <mapKey>.json per map)");
        }

        String resolvedKey = findMapNodeKey(mapsNode, mapKey);
        if (resolvedKey == null) {
            throw new IllegalStateException("Map not found in resources/config/maps/: " + mapKey + ".json");
        }

        JsonNode spawnsNode = mapsNode.path(resolvedKey).path("spawn_points");
        if (spawnsNode.isMissingNode() || !spawnsNode.isArray()) {
            throw new IllegalStateException(
                "Missing or non-array 'spawn_points' in resources/config/maps/" + resolvedKey + ".json"
                + ". Run scripts/deploy/extract-map-bounds.sh to populate it.");
        }

        CoordinatesConverter coords = new CoordinatesConverter();
        List<SpawnPointDto> out = new ArrayList<>(spawnsNode.size());
        for (JsonNode entry : spawnsNode) {
            JsonNode loc = entry.path("location");
            if (!loc.isArray() || loc.size() != 3) {
                throw new IllegalStateException(
                    "spawn_points entry missing 3-element 'location' array in resources/config/maps/"
                    + resolvedKey + ".json");
            }
            if (!entry.path("team").isInt()) {
                throw new IllegalStateException(
                    "spawn_points entry missing integer 'team' field in resources/config/maps/"
                    + resolvedKey + ".json");
            }
            SpawnPointDto dto = new SpawnPointDto();
            dto.team = entry.get("team").asInt();
            dto.location = coords.convert(
                loc.get(0).asDouble(),
                loc.get(1).asDouble(),
                loc.get(2).asDouble());
            out.add(dto);
        }
        return Collections.unmodifiableList(out);
    }

    private static String findMapNodeKey(JsonNode mapsNode, String desiredKey) {
        if (mapsNode.has(desiredKey)) return desiredKey;
        Iterator<String> names = mapsNode.fieldNames();
        while (names.hasNext()) {
            String candidate = names.next();
            if (candidate.equalsIgnoreCase(desiredKey)) return candidate;
        }
        return null;
    }
}
