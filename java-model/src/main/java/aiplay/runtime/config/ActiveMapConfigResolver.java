package aiplay.runtime.config;

import aiplay.config.global.MapNormConfig;
import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves map normalization parameters for a given map key.
 *
 * <p>Caller passes the map key explicitly — use
 * {@link aiplay.runtime.context.MapKey#active()} when you want the currently
 * active map. Results are cached per map key, so switching between maps
 * (as happens during multi-map CSV generation) is cheap after the first load.
 *
 * <p>Map norm values are read from {@code /maps/<mapKey>/map_norm/} in the merged
 * config tree, which is populated by {@link aiplay.config.RootConfigLoader} from
 * {@code resources/config/maps/<mapKey>.json}. Case-insensitive map name matching is supported.
 */
public final class ActiveMapConfigResolver {

    private static final ConcurrentHashMap<String, MapNormConfig> CACHE = new ConcurrentHashMap<>();

    private ActiveMapConfigResolver() {}

    public static MapNormConfig resolve(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, ActiveMapConfigResolver::load);
    }

    private static MapNormConfig load(String mapKey) {
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

        JsonNode mapNode = mapsNode.path(resolvedKey);
        JsonNode normNode = mapNode.path("map_norm");
        if (normNode.isMissingNode()) {
            throw new IllegalStateException(
                "No map_norm found in resources/config/maps/" + resolvedKey + ".json");
        }
        JsonNode symmetricNode = mapNode.path("symmetric");
        if (!symmetricNode.isBoolean()) {
            throw new IllegalStateException(
                "Missing or non-boolean 'symmetric' field in resources/config/maps/" + resolvedKey + ".json"
                + ". Run scripts/deploy/extract-map-bounds.sh to populate it.");
        }

        return new MapNormConfig(
            requireDouble(normNode, "bounds_min_x", mapKey),
            requireDouble(normNode, "bounds_max_x", mapKey),
            requireDouble(normNode, "bounds_min_y", mapKey),
            requireDouble(normNode, "bounds_max_y", mapKey),
            requireDouble(normNode, "bounds_min_z", mapKey),
            requireDouble(normNode, "bounds_max_z", mapKey),
            requireDouble(normNode, "edge", mapKey),
            requireDouble(normNode, "k", mapKey),
            symmetricNode.asBoolean()
        );
    }

    /** Case-insensitieve match van een mapKey op de geladen /maps-config-keys. Publiek
     *  zodat andere per-map resolvers (bv. {@link aiplay.runtime.geo.GeodesicFieldRepository})
     *  dezelfde resolutie gebruiken. */
    public static String findMapNodeKey(JsonNode mapsNode, String desiredKey) {
        if (mapsNode.has(desiredKey)) return desiredKey;
        Iterator<String> names = mapsNode.fieldNames();
        while (names.hasNext()) {
            String candidate = names.next();
            if (candidate.equalsIgnoreCase(desiredKey)) return candidate;
        }
        return null;
    }

    private static double requireDouble(JsonNode parent, String field, String mapKey) {
        JsonNode n = parent.path(field);
        if (!n.isNumber()) {
            throw new IllegalStateException("Missing " + field + " in map_norm for map: " + mapKey);
        }
        return n.asDouble();
    }

}
