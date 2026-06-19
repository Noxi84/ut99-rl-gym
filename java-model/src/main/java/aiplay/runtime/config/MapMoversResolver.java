package aiplay.runtime.config;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the static mover list for a given map.
 *
 * <p>Source file: {@code resources/config/maps/<mapKey>.json}, key {@code movers[]}.
 * Each entry has name, key_positions, platform_bounds_local, move_time, stay_open_time, etc.
 *
 * <p>The name is hashed with FNV-1a to match runtime mover identity from UDP.
 */
public final class MapMoversResolver {

    private static final ConcurrentHashMap<String, List<StaticMover>> CACHE = new ConcurrentHashMap<>();

    private MapMoversResolver() {}

    public static List<StaticMover> resolve(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            return Collections.emptyList();
        }
        return CACHE.computeIfAbsent(mapKey, MapMoversResolver::load);
    }

    private static List<StaticMover> load(String mapKey) {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) return Collections.emptyList();

        String resolvedKey = findMapNodeKey(mapsNode, mapKey);
        if (resolvedKey == null) return Collections.emptyList();

        JsonNode moversNode = mapsNode.path(resolvedKey).path("movers");
        if (moversNode.isMissingNode() || !moversNode.isArray()) return Collections.emptyList();

        List<StaticMover> out = new ArrayList<>(moversNode.size());
        for (JsonNode entry : moversNode) {
            String name = entry.path("name").asText("");
            if (name.isEmpty()) continue;

            int nameHash = fnv1a(name);
            double[][] keyPositions = parseKeyPositions(entry.path("key_positions"));
            double[] boundsMin = parseVec3(entry.path("platform_bounds_local").path("min"));
            double[] boundsMax = parseVec3(entry.path("platform_bounds_local").path("max"));
            double moveTime = entry.path("move_time").asDouble(1.0);
            double stayOpenTime = entry.path("stay_open_time").asDouble(0.0);
            int numKeys = entry.path("num_keys").asInt(2);

            out.add(new StaticMover(nameHash, name, keyPositions, boundsMin, boundsMax,
                moveTime, stayOpenTime, numKeys));
        }
        return Collections.unmodifiableList(out);
    }

    private static double[][] parseKeyPositions(JsonNode node) {
        if (!node.isArray()) return new double[0][];
        double[][] out = new double[node.size()][];
        for (int i = 0; i < node.size(); i++) {
            out[i] = parseVec3(node.get(i));
        }
        return out;
    }

    private static double[] parseVec3(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 3) {
            return new double[]{0, 0, 0};
        }
        return new double[]{node.get(0).asDouble(), node.get(1).asDouble(), node.get(2).asDouble()};
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

    /** FNV-1a 32-bit, matching UC's FNV1aHash function. */
    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    public record StaticMover(
        int nameHash,
        String name,
        double[][] keyPositions,
        double[] boundsMin,
        double[] boundsMax,
        double moveTime,
        double stayOpenTime,
        int numKeys
    ) {}
}
