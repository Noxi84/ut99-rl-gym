package aiplay.runtime.config;

import aiplay.config.PropertyReaderUtils;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.PickupTypeRegistry;
import aiplay.config.global.PickupTypeRegistry.AmmoClassEntry;
import aiplay.config.global.PickupTypeRegistry.ExtendedGroup;
import aiplay.config.global.PickupTypeRegistry.PickupTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves per-map static pickup-data for feature + reward computation.
 *
 * <p>Slot-indexering = vaste volgorde uit {@code maps/<key>.json:pickups[]}. Voor elk
 * semantic-type uit {@link PickupTypeRegistry#semanticGroups} (shieldbelt, armor,
 * thighpads, amp, megahealth) verzamelen we de eerste {@link #SLOTS_PER_TYPE} pickups
 * waarvan de canonical class in de semantic-groep zit. Ontbrekende slots → null
 * (feature-resolver vult mask-flag in).
 *
 * <p>Live↔static matching gebeurt via:
 * <ol>
 *   <li>FNV-1a 32-bit hash van de canonical class-string (zelfde algoritme als UC's
 *       {@code FNV1aHash} in {@code RLUdpStateSender.uc:1106-1119}).
 *   <li>Locatie-equality binnen {@link #MATCH_EPSILON_UU}: pickups kunnen dezelfde
 *       class hebben (bv. 2× ThighPads), de positie maakt ze uniek.
 * </ol>
 *
 * <p>Result is gecached per mapKey via {@link ConcurrentHashMap}; switching maps tijdens
 * multi-map CSV-generation is cheap na de eerste load. Geen fallbacks — een map zonder
 * {@code pickups[]} resulteert in een lege resolver (alle slots null), maar het
 * Pickup-types-config moet altijd bestaan.
 */
public final class PickupConfigRepository {

    /** Vast aantal slots per semantic-type (matched user-keuze: 2 per type). */
    public static final int SLOTS_PER_TYPE = 2;

    /** Distance tolerance for live-↔-static matching (UU). Pickups in stock maps zijn
     *  minimaal ~60 UU uit elkaar; 32 geeft een ruime marge zonder valse positives. */
    public static final double MATCH_EPSILON_UU = 32.0;

    /** Square of {@link #MATCH_EPSILON_UU} voor cheap distance checks. */
    private static final double MATCH_EPSILON_UU_SQ = MATCH_EPSILON_UU * MATCH_EPSILON_UU;

    /** Een statische pickup uit de map-JSON. {@code classHash} berekend met
     *  {@link #fnv1aHash} zodat het overeenkomt met wat UC verstuurt. */
    public record StaticPickup(
        String canonicalClass,
        String semanticType,
        int slotIndex,
        double x, double y, double z,
        int classHash,
        double respawnSeconds
    ) {}

    public static final class MapPickups {
        private final String mapKey;
        private final Map<String, List<StaticPickup>> slotsBySemantic;
        private final List<StaticPickup> allHighValuePickups;
        /** Pad-A: all pickups (high-value + extended categories), gebruikt voor matchLive
         *  en pickup-event detection. Insertion order = map-JSON volgorde. */
        private final List<StaticPickup> allPickups;
        /** Pad-A: per extended_group naam → alle statics van die canonicals (vaste lijst,
         *  niet K-truncated; enricher doet de top-K filtering per frame). */
        private final Map<String, List<StaticPickup>> staticsByExtendedGroup;
        /** Pad-A: per ammo canonical → alle statics (meestal ~1-3 per kaart). */
        private final Map<String, List<StaticPickup>> staticsByAmmoCanonical;

        MapPickups(String mapKey,
                   Map<String, List<StaticPickup>> slotsBySemantic,
                   List<StaticPickup> allHighValuePickups,
                   List<StaticPickup> allPickups,
                   Map<String, List<StaticPickup>> staticsByExtendedGroup,
                   Map<String, List<StaticPickup>> staticsByAmmoCanonical) {
            this.mapKey = mapKey;
            this.slotsBySemantic = slotsBySemantic;
            this.allHighValuePickups = allHighValuePickups;
            this.allPickups = allPickups;
            this.staticsByExtendedGroup = staticsByExtendedGroup;
            this.staticsByAmmoCanonical = staticsByAmmoCanonical;
        }

        public String mapKey() { return mapKey; }

        /** Slot [{@code 0..SLOTS_PER_TYPE-1}] voor een semantic, of {@link Optional#empty()}
         *  als de slot in deze map niet bestaat (te weinig pickups van dit semantic-type). */
        public Optional<StaticPickup> slot(String semantic, int slotIndex) {
            List<StaticPickup> slots = slotsBySemantic.get(semantic);
            if (slots == null || slotIndex < 0 || slotIndex >= slots.size()) {
                return Optional.empty();
            }
            return Optional.ofNullable(slots.get(slotIndex));
        }

        /** Alle high-value statische pickups in deze map (over alle semantic-types samen),
         *  in slot-volgorde — handig voor live↔static matching loops. */
        public List<StaticPickup> allHighValue() {
            return allHighValuePickups;
        }

        /** Pad-A: alle pickups (incl. heal/weapon/ammo) — voor matchLive bij elke
         *  categorie + event-detection in PickupEventReward. */
        public List<StaticPickup> allPickups() {
            return allPickups;
        }

        /** Pad-A: alle statics voor een extended_group (heal/weapon). Niet K-truncated. */
        public List<StaticPickup> staticsForExtendedGroup(String groupName) {
            return staticsByExtendedGroup.getOrDefault(groupName, List.of());
        }

        /** Pad-A: alle statics voor één ammo-canonical. */
        public List<StaticPickup> staticsForAmmoCanonical(String canonical) {
            return staticsByAmmoCanonical.getOrDefault(canonical, List.of());
        }

        /** Match een live UDP-pickup tegen de statische set. Retourneert empty als geen
         *  statische pickup binnen {@link #MATCH_EPSILON_UU} op dezelfde classHash zit.
         *  Bij meerdere matches binnen de epsilon (overlap) wordt het dichtste gekozen.
         *  Zoekt nu in {@link #allPickups()} (alle categorieën), niet alleen high-value. */
        public Optional<StaticPickup> matchLive(int classHash, double x, double y, double z) {
            StaticPickup best = null;
            double bestDistSq = MATCH_EPSILON_UU_SQ;
            for (StaticPickup sp : allPickups) {
                if (sp.classHash() != classHash) continue;
                double dx = sp.x() - x, dy = sp.y() - y, dz = sp.z() - z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 <= bestDistSq) {
                    bestDistSq = d2;
                    best = sp;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    private static final ConcurrentHashMap<String, MapPickups> CACHE = new ConcurrentHashMap<>();

    private PickupConfigRepository() {}

    /** Resolve for a given map. Use {@link aiplay.runtime.context.MapKey#active()} for the active map. */
    public static MapPickups forMap(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) {
            throw new IllegalArgumentException("mapKey must not be blank");
        }
        return CACHE.computeIfAbsent(mapKey, PickupConfigRepository::load);
    }

    private static MapPickups load(String mapKey) {
        PickupTypeRegistry registry = GlobalConfigRepository.shared().pickupTypes();
        JsonNode mapsNode = PropertyReaderUtils.getSubtree("/maps");
        if (mapsNode == null || !mapsNode.isObject()) {
            throw new IllegalStateException(
                "No /maps section found in config (resources/config/maps/ should hold one <mapKey>.json per map)");
        }
        JsonNode mapNode = findMapNode(mapsNode, mapKey);
        if (mapNode == null) {
            throw new IllegalStateException(
                "Map not found in resources/config/maps/: " + mapKey + ".json");
        }
        JsonNode pickupsArr = mapNode.path("pickups");
        // Build canonical → list of (loc) entries from map-JSON, preserving order.
        Map<String, List<double[]>> locsByCanonical = new HashMap<>();
        if (pickupsArr.isArray()) {
            for (JsonNode entry : pickupsArr) {
                String canonical = entry.path("type").asText(null);
                JsonNode loc = entry.path("location");
                if (canonical == null || !loc.isArray() || loc.size() != 3) continue;
                locsByCanonical.computeIfAbsent(canonical, k -> new ArrayList<>())
                    .add(new double[]{loc.get(0).asDouble(), loc.get(1).asDouble(), loc.get(2).asDouble()});
            }
        }

        // Voor elke semantic-group de eerste SLOTS_PER_TYPE pickups verzamelen, in
        // map-JSON volgorde (= vaste indexering per user-keuze).
        Map<String, List<StaticPickup>> slotsBySemantic = new LinkedHashMap<>();
        List<StaticPickup> all = new ArrayList<>();
        for (Map.Entry<String, List<String>> sg : registry.semanticGroups().entrySet()) {
            String semantic = sg.getKey();
            List<String> canonicals = sg.getValue();
            List<StaticPickup> slots = new ArrayList<>(SLOTS_PER_TYPE);
            outer:
            for (String canonical : canonicals) {
                List<double[]> locs = locsByCanonical.get(canonical);
                if (locs == null) continue;
                PickupTypeInfo info = registry.requireByCanonical(canonical);
                int classHash = fnv1aHash(canonical);
                for (double[] xyz : locs) {
                    if (slots.size() >= SLOTS_PER_TYPE) break outer;
                    StaticPickup sp = new StaticPickup(
                        canonical, semantic, slots.size(),
                        xyz[0], xyz[1], xyz[2],
                        classHash, info.respawnSeconds());
                    slots.add(sp);
                    all.add(sp);
                }
            }
            slotsBySemantic.put(semantic, Collections.unmodifiableList(slots));
        }

        // Pad-A: extended_groups (heal/weapon) — verzamel ALLE statics per canonical
        // (geen K-truncatie hier; enricher doet top-K per frame).
        Map<String, List<StaticPickup>> staticsByExtendedGroup = new LinkedHashMap<>();
        List<StaticPickup> allPickupsList = new ArrayList<>(all);
        for (Map.Entry<String, ExtendedGroup> eg : registry.extendedGroups().entrySet()) {
            String groupName = eg.getKey();
            ExtendedGroup group = eg.getValue();
            List<StaticPickup> groupStatics = new ArrayList<>();
            for (String canonical : group.canonicals()) {
                List<double[]> locs = locsByCanonical.get(canonical);
                if (locs == null) continue;
                PickupTypeInfo info = registry.requireByCanonical(canonical);
                int classHash = fnv1aHash(canonical);
                for (double[] xyz : locs) {
                    StaticPickup sp = new StaticPickup(
                        canonical, "ext:" + groupName, groupStatics.size(),
                        xyz[0], xyz[1], xyz[2],
                        classHash, info.respawnSeconds());
                    groupStatics.add(sp);
                    allPickupsList.add(sp);
                }
            }
            staticsByExtendedGroup.put(groupName, Collections.unmodifiableList(groupStatics));
        }

        // Pad-A: ammo_per_class — per canonical de statics (meestal 1-3 per map).
        Map<String, List<StaticPickup>> staticsByAmmoCanonical = new LinkedHashMap<>();
        for (Map.Entry<String, AmmoClassEntry> ape : registry.ammoPerClass().entrySet()) {
            String canonical = ape.getKey();
            List<double[]> locs = locsByCanonical.get(canonical);
            if (locs == null || locs.isEmpty()) {
                staticsByAmmoCanonical.put(canonical, List.of());
                continue;
            }
            PickupTypeInfo info = registry.requireByCanonical(canonical);
            int classHash = fnv1aHash(canonical);
            List<StaticPickup> ammoStatics = new ArrayList<>(locs.size());
            for (double[] xyz : locs) {
                StaticPickup sp = new StaticPickup(
                    canonical, "ammo:" + canonical, ammoStatics.size(),
                    xyz[0], xyz[1], xyz[2],
                    classHash, info.respawnSeconds());
                ammoStatics.add(sp);
                allPickupsList.add(sp);
            }
            staticsByAmmoCanonical.put(canonical, Collections.unmodifiableList(ammoStatics));
        }

        return new MapPickups(mapKey,
            Collections.unmodifiableMap(slotsBySemantic),
            Collections.unmodifiableList(all),
            Collections.unmodifiableList(allPickupsList),
            Collections.unmodifiableMap(staticsByExtendedGroup),
            Collections.unmodifiableMap(staticsByAmmoCanonical));
    }

    private static JsonNode findMapNode(JsonNode mapsNode, String mapKey) {
        if (mapsNode.has(mapKey)) return mapsNode.get(mapKey);
        var it = mapsNode.fieldNames();
        while (it.hasNext()) {
            String candidate = it.next();
            if (candidate.equalsIgnoreCase(mapKey)) return mapsNode.get(candidate);
        }
        return null;
    }

    /** FNV-1a 32-bit, identiek aan {@code RLUdpStateSender.uc:FNV1aHash(string)}. UC's
     *  signed-int representation van offset 2166136261 = -2128831035, met dezelfde
     *  signed-int overflow-wrap-around tijdens vermenigvuldigingen. */
    public static int fnv1aHash(String s) {
        int h = -2128831035;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 16777619;
        }
        return h;
    }
}
