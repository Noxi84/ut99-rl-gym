package aiplay.runtime.geo;

import aiplay.config.PropertyReaderUtils;
import aiplay.runtime.config.ActiveMapConfigResolver;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Resolved het geodesische afstandsveld voor een map-key.
 *
 * <p>Activatie is expliciet per map via het VERPLICHTE boolean-veld {@code geodesic_field}
 * in {@code resources/config/maps/<mapKey>.json} (no-config-fallbacks: ontbreekt het veld,
 * dan crasht de lookup met een duidelijke melding):
 * <ul>
 *   <li>{@code false} — geen veld; callers gebruiken euclidische afstand (status quo).
 *   <li>{@code true} — het veld-bestand {@code resources/config/geodesic/<mapKey>.geodesic.json}
 *       (gebouwd door {@code BuildGeodesicFieldMain}) MOET bestaan; ontbreken is een fout.
 * </ul>
 *
 * <p>Het veld-bestand woont bewust NIET in {@code resources/config/maps/} — alles in die
 * directory wordt door {@code RootConfigLoader}/{@code MapIdentityResolver} als per-map
 * config geladen en gevalideerd (verplichte {@code map_id} — een veld-bestand daar laat de
 * JVM-start falen). Het wordt ook niet via de merged config-tree geladen (duizenden nodes —
 * te groot voor de property-tree) maar direct van disk onder
 * {@link PropertyReaderUtils#projectRoot()}. Resultaten zijn per map-key gecached.
 */
public final class GeodesicFieldRepository {

    private static final Logger LOG = Logger.getLogger(GeodesicFieldRepository.class.getName());

    /** Cache-waarde: Optional.empty() = expliciet uitgeschakeld voor deze map. */
    private static final ConcurrentHashMap<String, Optional<GeodesicField>> CACHE =
            new ConcurrentHashMap<>();

    private GeodesicFieldRepository() {}

    public static Optional<GeodesicField> forMap(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, GeodesicFieldRepository::load);
    }

    private static Optional<GeodesicField> load(String mapKey) {
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) {
            throw new IllegalStateException(
                "No /maps section found in config (resources/config/maps/ should hold one <mapKey>.json per map)");
        }
        String resolvedKey = ActiveMapConfigResolver.findMapNodeKey(mapsNode, mapKey);
        if (resolvedKey == null) {
            throw new IllegalStateException(
                "Map not found in resources/config/maps/: " + mapKey + ".json");
        }
        JsonNode flag = mapsNode.path(resolvedKey).path("geodesic_field");
        if (!flag.isBoolean()) {
            throw new IllegalStateException(
                "Missing or non-boolean 'geodesic_field' in resources/config/maps/" + resolvedKey
                + ".json. Set false (euclidische afstanden) of true (vereist "
                + resolvedKey + ".geodesic.json; bouw via scripts/deploy/build-geodesic-field.sh).");
        }
        if (!flag.asBoolean()) {
            LOG.info("[GeodesicField] map=" + resolvedKey
                + " geodesic_field=false → euclidische objective-afstanden");
            return Optional.empty();
        }

        Path file = PropertyReaderUtils.projectRoot().toPath()
            .resolve("resources/config/geodesic").resolve(resolvedKey + ".geodesic.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                "geodesic_field=true voor map " + resolvedKey + " maar veld-bestand ontbreekt: "
                + file + " (bouw via scripts/deploy/build-geodesic-field.sh, of zet de flag op false)");
        }
        try {
            GeodesicField field = GeodesicField.load(file);
            LOG.info("[GeodesicField] map=" + resolvedKey + " geladen: "
                + field.nodeCount() + " nodes, " + field.edgeCount() + " edges, voxel="
                + field.voxelSizeUu() + "uu (" + file.getFileName() + ")");
            return Optional.of(field);
        } catch (IOException e) {
            throw new IllegalStateException("Kan geodesic field niet laden: " + file, e);
        }
    }
}
