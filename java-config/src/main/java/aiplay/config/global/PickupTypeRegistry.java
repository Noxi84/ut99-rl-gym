package aiplay.config.global;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical UT99 pickup-class registry loaded from
 * {@code resources/config/pickup-types.json}.
 *
 * <p>Single source of truth voor:
 * <ul>
 *   <li>{@code respawn_seconds} — gebruikt door feature-normalisatie ({@code remaining_respawn_norm})
 *       en reward-cap (delta_health/armor clamping).
 *   <li>{@code category} — gebruikt voor map-summary en hypothetische slot-filtering.
 *   <li>{@code class_aliases} — T3D-class strings (lowercase) die naar dezelfde canonical
 *       Botpack class mappen. Multi-alias support voor mod-variants
 *       ({@code Eightball}/{@code UT_Eightball}, {@code RocketCan}/{@code RocketPack}, etc).
 * </ul>
 *
 * <p>Geen defaults / fallbacks (per CLAUDE.md): lookups op een onbekende canonical name
 * gooien {@code IllegalArgumentException}. Een T3D class-string die niet in de aliasing-tabel
 * voorkomt wordt door {@link #lookupByClassAlias} als {@link Optional#empty()} terug-gegeven
 * — de extract-tool slaat zo'n actor stil over.
 */
public final class PickupTypeRegistry {

    public record PickupTypeInfo(String canonical, String category, double respawnSeconds) {}

    /** Pad-A extension group: dynamic top-K nearest slot-vulling per categorie. */
    public record ExtendedGroup(
        String name,
        List<String> canonicals,
        int slotCount,
        String sortMode,
        /** Voor 'heal'-sort: per canonical de hoeveelheid HP die het pickup verleent. */
        Map<String, Double> healAmounts,
        /** Voor 'heal'-sort: per canonical de HP-cap waarboven het pickup geen effect heeft. */
        Map<String, Double> healCaps
    ) {}

    /** Pad-A ammo-class-config: één slot per canonical ammo class met weapon-feed mapping. */
    public record AmmoClassEntry(String canonical, List<String> feedsWeapons) {}

    private final Map<String, PickupTypeInfo> byCanonical;
    private final Map<String, PickupTypeInfo> byClassAlias; // lowercase keys
    private final Map<String, List<String>> semanticGroups; // semantic → ordered canonicals
    private final Map<String, ExtendedGroup> extendedGroups; // group name → cfg
    private final Map<String, AmmoClassEntry> ammoPerClass;  // canonical → cfg (insertion-order)

    private PickupTypeRegistry(Map<String, PickupTypeInfo> byCanonical,
                               Map<String, PickupTypeInfo> byClassAlias,
                               Map<String, List<String>> semanticGroups,
                               Map<String, ExtendedGroup> extendedGroups,
                               Map<String, AmmoClassEntry> ammoPerClass) {
        this.byCanonical = Map.copyOf(byCanonical);
        this.byClassAlias = Map.copyOf(byClassAlias);
        this.semanticGroups = Map.copyOf(semanticGroups);
        this.extendedGroups = Map.copyOf(extendedGroups);
        this.ammoPerClass = Collections.unmodifiableMap(new LinkedHashMap<>(ammoPerClass));
    }

    /** Semantic-slot groepering uit pickup-types.json:semantic_groups. Insertion-order
     *  preserved (LinkedHashMap input) zodat feature-volgorde voorspelbaar is. */
    public Map<String, List<String>> semanticGroups() {
        return semanticGroups;
    }

    /** Pad-A extended_groups (heal/weapon) — dynamic top-K slots. */
    public Map<String, ExtendedGroup> extendedGroups() {
        return extendedGroups;
    }

    /** Pad-A ammo_per_class.classes (insertion-order preserved → vaste slot-volgorde). */
    public Map<String, AmmoClassEntry> ammoPerClass() {
        return ammoPerClass;
    }

    /** Lookup op canonical Botpack class-naam (PascalCase, exacte match).
     *  Onbekende canonical = hard error. */
    public PickupTypeInfo requireByCanonical(String canonical) {
        PickupTypeInfo info = byCanonical.get(canonical);
        if (info == null) {
            throw new IllegalArgumentException(
                "pickup-types.json: no entry for canonical='" + canonical
                    + "' — add it to resources/config/pickup-types.json registry[]");
        }
        return info;
    }

    /** Lookup op T3D class-string (case-insensitive). Onbekende class = empty. */
    public Optional<PickupTypeInfo> lookupByClassAlias(String t3dClassName) {
        if (t3dClassName == null) return Optional.empty();
        return Optional.ofNullable(byClassAlias.get(t3dClassName.toLowerCase(Locale.ROOT)));
    }

    public Map<String, PickupTypeInfo> byCanonical() {
        return byCanonical;
    }

    /** Build vanuit een al geparseerde JsonNode (root van pickup-types.json). */
    public static PickupTypeRegistry fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("pickup-types.json: expected top-level JSON object");
        }
        JsonNode registry = root.path("registry");
        if (!registry.isArray()) {
            throw new IllegalStateException("pickup-types.json: 'registry' must be an array");
        }
        Map<String, PickupTypeInfo> byCanonical = new HashMap<>();
        Map<String, PickupTypeInfo> byAlias = new HashMap<>();
        for (JsonNode entry : registry) {
            String canonical = requireText(entry, "canonical");
            String category = requireText(entry, "category");
            double respawnSeconds = requireNumber(entry, "respawn_seconds");
            PickupTypeInfo info = new PickupTypeInfo(canonical, category, respawnSeconds);

            if (byCanonical.putIfAbsent(canonical, info) != null) {
                throw new IllegalStateException(
                    "pickup-types.json: duplicate canonical='" + canonical + "'");
            }

            JsonNode aliasArr = entry.path("class_aliases");
            if (!aliasArr.isArray() || aliasArr.isEmpty()) {
                throw new IllegalStateException(
                    "pickup-types.json: registry entry '" + canonical
                        + "' needs non-empty 'class_aliases' array");
            }
            for (JsonNode aliasNode : aliasArr) {
                String alias = aliasNode.asText().toLowerCase(Locale.ROOT);
                if (alias.isBlank()) {
                    throw new IllegalStateException(
                        "pickup-types.json: blank alias in '" + canonical + "'");
                }
                PickupTypeInfo prev = byAlias.putIfAbsent(alias, info);
                if (prev != null && !prev.canonical().equals(canonical)) {
                    throw new IllegalStateException(
                        "pickup-types.json: alias '" + alias + "' bound to both '"
                            + prev.canonical() + "' and '" + canonical + "'");
                }
            }
        }

        Map<String, List<String>> semanticGroups = new LinkedHashMap<>();
        JsonNode sgNode = root.path("semantic_groups");
        if (sgNode.isObject()) {
            var it = sgNode.fields();
            while (it.hasNext()) {
                var e = it.next();
                String semantic = e.getKey();
                if (semantic.startsWith("_")) continue; // skip _doc et al.
                JsonNode listNode = e.getValue();
                if (!listNode.isArray() || listNode.isEmpty()) {
                    throw new IllegalStateException(
                        "pickup-types.json: semantic_groups." + semantic + " must be a non-empty array");
                }
                List<String> canonicals = new ArrayList<>(listNode.size());
                for (JsonNode item : listNode) {
                    String canonical = item.asText();
                    if (!byCanonical.containsKey(canonical)) {
                        throw new IllegalStateException(
                            "pickup-types.json: semantic_groups." + semantic
                                + " references unknown canonical '" + canonical + "'");
                    }
                    canonicals.add(canonical);
                }
                semanticGroups.put(semantic, List.copyOf(canonicals));
            }
        }

        Map<String, ExtendedGroup> extendedGroups = parseExtendedGroups(root, byCanonical);
        Map<String, AmmoClassEntry> ammoPerClass = parseAmmoPerClass(root, byCanonical);
        return new PickupTypeRegistry(byCanonical, byAlias, semanticGroups, extendedGroups, ammoPerClass);
    }

    private static Map<String, ExtendedGroup> parseExtendedGroups(JsonNode root,
                                                                   Map<String, PickupTypeInfo> byCanonical) {
        Map<String, ExtendedGroup> out = new LinkedHashMap<>();
        JsonNode egNode = root.path("extended_groups");
        if (!egNode.isObject()) return out;
        var it = egNode.fields();
        while (it.hasNext()) {
            var e = it.next();
            String name = e.getKey();
            if (name.startsWith("_")) continue;
            JsonNode body = e.getValue();
            if (!body.isObject()) {
                throw new IllegalStateException(
                    "pickup-types.json: extended_groups." + name + " must be an object");
            }
            JsonNode canonicalsNode = body.path("canonicals");
            if (!canonicalsNode.isArray() || canonicalsNode.isEmpty()) {
                throw new IllegalStateException(
                    "pickup-types.json: extended_groups." + name + ".canonicals must be non-empty array");
            }
            List<String> canonicals = new ArrayList<>(canonicalsNode.size());
            for (JsonNode c : canonicalsNode) {
                String canonical = c.asText();
                if (!byCanonical.containsKey(canonical)) {
                    throw new IllegalStateException(
                        "pickup-types.json: extended_groups." + name + " references unknown canonical '"
                            + canonical + "'");
                }
                canonicals.add(canonical);
            }
            int slotCount = body.path("slot_count").asInt(-1);
            if (slotCount <= 0) {
                throw new IllegalStateException(
                    "pickup-types.json: extended_groups." + name + ".slot_count must be positive int");
            }
            String sortMode = body.path("sort_mode").asText(null);
            if (sortMode == null || sortMode.isBlank()) {
                throw new IllegalStateException(
                    "pickup-types.json: extended_groups." + name + ".sort_mode required");
            }
            Map<String, Double> healAmounts = parseStringDoubleMap(body, "heal_amounts");
            Map<String, Double> healCaps = parseStringDoubleMap(body, "heal_caps");
            if ("effective_heal_priority".equals(sortMode)) {
                if (healAmounts.isEmpty() || healCaps.isEmpty()) {
                    throw new IllegalStateException(
                        "pickup-types.json: extended_groups." + name + " needs heal_amounts + heal_caps for sort_mode=effective_heal_priority");
                }
                for (String canonical : canonicals) {
                    if (!healAmounts.containsKey(canonical) || !healCaps.containsKey(canonical)) {
                        throw new IllegalStateException(
                            "pickup-types.json: extended_groups." + name + " missing heal_amounts/heal_caps for canonical='"
                                + canonical + "'");
                    }
                }
            }
            out.put(name, new ExtendedGroup(name, List.copyOf(canonicals), slotCount, sortMode,
                Map.copyOf(healAmounts), Map.copyOf(healCaps)));
        }
        return out;
    }

    private static Map<String, AmmoClassEntry> parseAmmoPerClass(JsonNode root,
                                                                  Map<String, PickupTypeInfo> byCanonical) {
        Map<String, AmmoClassEntry> out = new LinkedHashMap<>();
        JsonNode apcNode = root.path("ammo_per_class");
        if (!apcNode.isObject()) return out;
        JsonNode classes = apcNode.path("classes");
        if (!classes.isObject()) return out;
        var it = classes.fields();
        while (it.hasNext()) {
            var e = it.next();
            String canonical = e.getKey();
            if (canonical.startsWith("_")) continue;
            if (!byCanonical.containsKey(canonical)) {
                throw new IllegalStateException(
                    "pickup-types.json: ammo_per_class.classes." + canonical + " is unknown canonical");
            }
            PickupTypeInfo info = byCanonical.get(canonical);
            if (!"ammo".equals(info.category())) {
                throw new IllegalStateException(
                    "pickup-types.json: ammo_per_class.classes." + canonical
                        + " — registry category is '" + info.category() + "', expected 'ammo'");
            }
            JsonNode feedsNode = e.getValue().path("feeds_weapons");
            if (!feedsNode.isArray() || feedsNode.isEmpty()) {
                throw new IllegalStateException(
                    "pickup-types.json: ammo_per_class.classes." + canonical
                        + ".feeds_weapons must be non-empty array");
            }
            List<String> feeds = new ArrayList<>(feedsNode.size());
            for (JsonNode f : feedsNode) {
                String w = f.asText();
                if (!byCanonical.containsKey(w)) {
                    throw new IllegalStateException(
                        "pickup-types.json: ammo_per_class.classes." + canonical
                            + ".feeds_weapons references unknown canonical '" + w + "'");
                }
                feeds.add(w);
            }
            out.put(canonical, new AmmoClassEntry(canonical, List.copyOf(feeds)));
        }
        return out;
    }

    private static Map<String, Double> parseStringDoubleMap(JsonNode parent, String field) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonNode node = parent.path(field);
        if (!node.isObject()) return out;
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith("_")) continue;
            if (!e.getValue().isNumber()) {
                throw new IllegalStateException(
                    "pickup-types.json: " + field + "." + e.getKey() + " must be a number");
            }
            out.put(e.getKey(), e.getValue().asDouble());
        }
        return out;
    }

    /** Laad rechtstreeks vanuit een file (gebruikt door ExtractMapBoundsMain — die kent
     *  GlobalConfigRepository niet). */
    public static PickupTypeRegistry loadFromFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("pickup-types.json not found: " + file);
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(file.toFile());
        return fromJson(root);
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode v = parent.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new IllegalStateException(
                "pickup-types.json: missing/blank text field '" + field
                    + "' in registry entry " + parent);
        }
        return v.asText().trim();
    }

    private static double requireNumber(JsonNode parent, String field) {
        JsonNode v = parent.path(field);
        if (!v.isNumber()) {
            throw new IllegalStateException(
                "pickup-types.json: missing/non-numeric field '" + field
                    + "' in registry entry " + parent);
        }
        return v.asDouble();
    }
}
