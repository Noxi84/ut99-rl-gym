package aiplay.runtime.config;

import aiplay.config.PropertyReaderUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.JumpPadDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the static jump-pad list for a given map.
 *
 * <p>Source file: {@code resources/config/maps/<mapKey>.json}, key {@code jump_pads[]}:
 * {@code {"location":[x,y,z], "velocity":[vx,vy,vz]}}
 *
 * <p>Caller passes the map key explicitly — use
 * {@link aiplay.runtime.context.MapKey#active()} for the currently active map.
 * Maps without jump pads must still declare an empty array.
 */
public final class MapJumpPadsResolver {

    private static final ConcurrentHashMap<String, List<JumpPadDto>> CACHE = new ConcurrentHashMap<>();

    private MapJumpPadsResolver() {}

    public static List<JumpPadDto> resolve(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, MapJumpPadsResolver::load);
    }

    private static List<JumpPadDto> load(String mapKey) {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) {
            throw new IllegalStateException(
                "No /maps section found in config (resources/config/maps/ should hold one <mapKey>.json per map)");
        }

        String resolvedKey = findMapNodeKey(mapsNode, mapKey);
        if (resolvedKey == null) {
            throw new IllegalStateException("Map not found in resources/config/maps/: " + mapKey + ".json");
        }

        JsonNode padsNode = mapsNode.path(resolvedKey).path("jump_pads");
        if (padsNode.isMissingNode() || !padsNode.isArray()) {
            throw new IllegalStateException(
                "Missing or non-array 'jump_pads' in resources/config/maps/" + resolvedKey + ".json"
                + ". Run scripts/deploy/extract-map-bounds.sh to populate it (empty array is required for pad-less maps).");
        }

        CoordinatesConverter coords = new CoordinatesConverter();
        List<JumpPadDto> out = new ArrayList<>(padsNode.size());
        for (JsonNode entry : padsNode) {
            JsonNode loc = entry.path("location");
            JsonNode vel = entry.path("velocity");
            if (!loc.isArray() || loc.size() != 3) {
                throw new IllegalStateException(
                    "jump_pads entry missing 3-element 'location' array in resources/config/maps/"
                    + resolvedKey + ".json");
            }
            if (!vel.isArray() || vel.size() != 3) {
                throw new IllegalStateException(
                    "jump_pads entry missing 3-element 'velocity' array in resources/config/maps/"
                    + resolvedKey + ".json");
            }
            JumpPadDto dto = new JumpPadDto();
            // Location uses CoordinatesConverter so x_norm/y_norm/z_norm fields are populated
            // for downstream features.
            dto.location = coords.convert(
                loc.get(0).asDouble(),
                loc.get(1).asDouble(),
                loc.get(2).asDouble());
            // Velocity skips CoordinatesConverter — pad impulses (vz up to 12000 UU/s) would
            // blow past map bounds and spam warnings. Only raw components are needed for
            // ballistic landing prediction in JumpPadEnricher.
            CoordinatesDto vDto = new CoordinatesDto();
            vDto.x = vel.get(0).asDouble();
            vDto.y = vel.get(1).asDouble();
            vDto.z = vel.get(2).asDouble();
            dto.velocity = vDto;
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
