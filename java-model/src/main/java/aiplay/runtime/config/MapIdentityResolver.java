package aiplay.runtime.config;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the stable categorical map id for a given map.
 *
 * <p>Caller passes the map key explicitly — use
 * {@link aiplay.runtime.context.MapKey#fromFrame} when you have a state frame
 * with {@code mapInfo.mapName}.
 *
 * <p>The id is configured per map in {@code resources/config/maps/<mapKey>.json}
 * as {@code map_id}. It is intentionally a categorical identifier: Python
 * converts the raw input column to a learned embedding before the policy LSTM
 * sees it, so the numeric value is not interpreted as an ordinal distance.
 */
public final class MapIdentityResolver {

    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    private MapIdentityResolver() {}

    public static int resolveMapId(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, MapIdentityResolver::loadMapId);
    }

    public static List<String> validateAllConfiguredMapIds() {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        List<String> errors = new ArrayList<>();
        if (mapsNode == null || !mapsNode.isObject()) {
            errors.add("No /maps section found in config");
            return errors;
        }

        Map<Integer, String> seen = new HashMap<>();
        Iterator<String> names = mapsNode.fieldNames();
        while (names.hasNext()) {
            String mapKey = names.next();
            JsonNode idNode = mapsNode.path(mapKey).path("map_id");
            if (!idNode.isInt() || idNode.asInt() < 0) {
                errors.add("resources/config/maps/" + mapKey
                    + ".json: missing non-negative integer map_id");
                continue;
            }
            int id = idNode.asInt();
            String previous = seen.putIfAbsent(id, mapKey);
            if (previous != null) {
                errors.add("Duplicate map_id " + id + " in resources/config/maps/"
                    + previous + ".json and " + mapKey + ".json");
            }
        }
        return errors;
    }

    private static int loadMapId(String mapKey) {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) {
            throw new IllegalStateException(
                "No /maps section found in config (resources/config/maps/ should hold one <mapKey>.json per map)");
        }

        String resolvedKey = findMapNodeKey(mapsNode, mapKey);
        if (resolvedKey == null) {
            throw new IllegalStateException(
                "Map not found in resources/config/maps/: " + mapKey + ".json");
        }

        JsonNode idNode = mapsNode.path(resolvedKey).path("map_id");
        if (!idNode.isInt() || idNode.asInt() < 0) {
            throw new IllegalStateException(
                "Missing or invalid non-negative integer 'map_id' in resources/config/maps/"
                    + resolvedKey + ".json");
        }
        return idNode.asInt();
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
