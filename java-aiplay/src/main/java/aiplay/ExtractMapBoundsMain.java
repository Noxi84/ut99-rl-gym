package aiplay;

import aiplay.config.global.PickupTypeRegistry;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract map geometry from a UT99 level T3D export and write it to
 * {@code resources/config/maps/<mapName>.json}.
 *
 * <p>Each per-map file holds four fields:
 * <ul>
 *   <li>{@code map_norm.{bounds_min,bounds_max}_{x,y,z}} — signed world-space bounding box
 *       computed over all actor locations + brush vertices (brush vertices translated by the
 *       actor's Location; rotation/scale ignored — OK for axis-aligned builder brushes).
 *   <li>{@code symmetric} — {@code true} iff the red and blue FlagBase actors sit mirror-
 *       symmetric around the map center (rotation-symmetric in X–Y). This is the prerequisite
 *       for CanonicalPerspectiveNormalizer's 180° transform to be valid; it no longer requires
 *       the map to be centered on the origin.
 *   <li>{@code spawn_points[]} — one entry per PlayerStart: location, rotation, team (nearest
 *       FlagBase as heuristic since T3D defaults TeamNumber=0 and omits it).
 *   <li>{@code jump_pads[]} — one entry per Kicker actor: location and KickVelocity. Kickers
 *       are UT99's static jumppads — the velocity is applied to any pawn entering the
 *       collision volume. Missing X/Y/Z components in T3D default to 0.
 *   <li>{@code pickups[]} — one entry per known UT99 {@code Botpack.Pickup}-subclass actor:
 *       {@code type} (canonical class name, e.g. {@code UT_FlakCannon}, normalised across
 *       case-variants like {@code armor2}/{@code Armor2}/{@code ut_shieldbelt}),
 *       {@code category} ∈ {weapon, ammo, health, armor, powerup}, and {@code location}.
 *       Respawn-timings zijn NIET in de per-map JSON gesnapshot — die zijn statisch per
 *       canonical class en worden runtime uit {@code resources/config/pickup-types.json}
 *       gelezen ({@link PickupTypeRegistry}). Onbekende actor-classes worden stil
 *       overgeslagen; {@code pickup-types.json} is de single source of truth voor wat telt.
 * </ul>
 *
 * <p>Updates are conservative: if the file already exists, scalars and arrays that are
 * already populated stay untouched (so tweaks to {@code edge}, {@code k}, hand-curated
 * {@code spawn_points}, etc. survive re-runs). Only fully-missing fields are filled in
 * from the freshly parsed T3D.
 */
public final class ExtractMapBoundsMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY_PRINTER;
    static {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentArraysWith(indenter);
        pp.indentObjectsWith(indenter);
        PRETTY_PRINTER = pp;
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static final Pattern ACTOR_PATTERN = Pattern.compile(
        "^Begin Actor Class=(\\S+) Name=\\S+\\r?\\n(.*?)^End Actor",
        Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
        "Location=\\(([^)]+)\\)");

    private static final Pattern ROTATION_PATTERN = Pattern.compile(
        "Rotation=\\(([^)]+)\\)");

    private static final Pattern KICK_VELOCITY_PATTERN = Pattern.compile(
        "KickVelocity=\\(([^)]+)\\)");

    private static final Pattern TEAM_PATTERN = Pattern.compile(
        "^\\s*Team=([0-9]+)\\s*$", Pattern.MULTILINE);

    /** Vertex pattern used to parse brush polygons (for BSP-based bounds). Brush vertices
     *  are in actor-local coordinates — caller must add the actor's Location. */
    private static final Pattern VERTEX_PATTERN = Pattern.compile(
        "Vertex\\s+([+-]?[0-9.]+)\\s*,\\s*([+-]?[0-9.]+)\\s*,\\s*([+-]?[0-9.]+)");

    /** Mover keyframe pose. KeyPos(N)/KeyRot(N) where N ∈ 0..7. Both arrays have sparse
     *  T3D representation — only non-zero components ({@code X=…}, {@code Z=…}) are written. */
    private static final Pattern KEY_POS_PATTERN = Pattern.compile(
        "^\\s*KeyPos\\(([0-7])\\)=\\(([^)]+)\\)", Pattern.MULTILINE);
    private static final Pattern KEY_ROT_PATTERN = Pattern.compile(
        "^\\s*KeyRot\\(([0-7])\\)=\\(([^)]+)\\)", Pattern.MULTILINE);

    /** Mover subclasses we recognize. Engine/Mover is the base; the rest live in UnrealI /
     *  UnrealShare. Unknown subclasses are skipped (logged to stdout). Anything outside this
     *  whitelist won't be exported — add a class here when a map needs custom support. */
    private static final Set<String> MOVER_CLASSES = Set.of(
        "Mover", "ElevatorMover", "GradualMover", "LoopMover", "MixMover",
        "AttachMover", "AssertMover", "RotatingMover");

    /** Enum mappings — short forms used in the per-map JSON. */
    private static final Map<String, String> ENCROACH_TYPE_MAP = Map.of(
        "ME_StopWhenEncroach",   "Stop",
        "ME_ReturnWhenEncroach", "Return",
        "ME_CrushWhenEncroach",  "Crush",
        "ME_IgnoreWhenEncroach", "Ignore");
    private static final Map<String, String> BUMP_TYPE_MAP = Map.of(
        "BT_PlayerBump", "PlayerBump",
        "BT_PawnBump",   "PawnBump",
        "BT_AnyBump",    "AnyBump");
    private static final Map<String, String> TRIGGER_TYPE_MAP = Map.of(
        "TT_PlayerProximity", "PlayerProximity",
        "TT_PawnProximity",   "PawnProximity",
        "TT_ClassProximity",  "ClassProximity",
        "TT_AnyProximity",    "AnyProximity",
        "TT_Shoot",           "Shoot");
    private static final Map<String, String> GLIDE_TYPE_MAP = Map.of(
        "MV_MoveByTime",  "Linear",
        "MV_GlideByTime", "Glide");

    // Mover defaults (Engine/Mover.uc defaultproperties block):
    private static final String   DEFAULT_INITIAL_STATE = "BumpOpenTimed";
    private static final String   DEFAULT_ENCROACH      = "Return";
    private static final String   DEFAULT_BUMP          = "PlayerBump";
    private static final String   DEFAULT_GLIDE         = "Glide";
    private static final double   DEFAULT_MOVE_TIME     = 1.0;
    private static final double   DEFAULT_STAY_OPEN     = 4.0;
    private static final double   DEFAULT_DELAY_TIME    = 0.0;
    private static final int      DEFAULT_NUM_KEYS      = 2;

    // ElevatorTrigger defaults (UnrealI/ElevatorTrigger.uc + Engine/Triggers.uc):
    private static final String   DEFAULT_ELEV_TRIGGER_TYPE = "PlayerProximity";
    private static final double   DEFAULT_ELEV_RADIUS       = 220.0;
    private static final double   DEFAULT_ELEV_HEIGHT       = 40.0;

    /** FlagBase reflection distance allowed for symmetry: 10% of the larger X/Y half-width. */
    private static final double FLAG_SYMMETRY_TOLERANCE_FRAC = 0.10;

    /** Default values written into a fresh map_norm block when no prior file exists. */
    private static final double DEFAULT_EDGE = 0.98;
    private static final double DEFAULT_K = 3.0;

    private ExtractMapBoundsMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: ExtractMapBoundsMain <MyLevel.t3d> <mapName> <maps_dir>");
            System.exit(2);
        }

        Path t3dFile = Path.of(args[0]);
        String mapName = args[1];
        Path mapsDir = Path.of(args[2]);

        // pickup-types.json zit op zustermap-niveau van maps/  (resources/config/pickup-types.json).
        Path pickupTypesFile = mapsDir.toAbsolutePath().getParent().resolve("pickup-types.json");
        PickupTypeRegistry pickupRegistry = PickupTypeRegistry.loadFromFile(pickupTypesFile);
        System.out.println("Loaded pickup registry from " + pickupTypesFile
            + " (" + pickupRegistry.byCanonical().size() + " types)");

        String t3d = Files.readString(t3dFile);
        Bounds b = parseBounds(t3d);
        if (b.actorCount == 0) {
            throw new IllegalStateException("No actors found in T3D: " + t3dFile);
        }

        double minX = round3(b.minX), maxX = round3(b.maxX);
        double minY = round3(b.minY), maxY = round3(b.maxY);
        double minZ = round3(b.minZ), maxZ = round3(b.maxZ);

        List<FlagBase> flags = parseFlagBases(t3d);
        List<SpawnPoint> spawns = parsePlayerStarts(t3d, flags);
        List<JumpPad> jumpPads = parseKickers(t3d);
        List<Pickup> pickups = parsePickups(t3d, pickupRegistry);
        List<Mover> movers = parseMovers(t3d);
        List<ElevatorTrigger> elevatorTriggers = parseElevatorTriggers(t3d);
        Map<String, Integer> unknownMoverSubs = countUnknownMoverSubclasses(t3d);
        boolean symmetric = isSymmetricByFlagBases(flags, minX, maxX, minY, maxY);

        System.out.printf(
            "Parsed %d actors. Bounds X=[%.1f,%.1f] Y=[%.1f,%.1f] Z=[%.1f,%.1f]%n",
            b.actorCount, minX, maxX, minY, maxY, minZ, maxZ);
        System.out.printf("Flag-base symmetric around center: %s (%d flag bases)%n", symmetric, flags.size());
        System.out.printf("Spawn points: %d%n", spawns.size());
        System.out.printf("Jump pads: %d%n", jumpPads.size());
        System.out.printf("Pickups: %d%n", pickups.size());
        summarisePickups(pickups);
        System.out.printf("Movers: %d%n", movers.size());
        summariseMovers(movers);
        System.out.printf("Elevator triggers: %d%n", elevatorTriggers.size());
        if (!unknownMoverSubs.isEmpty()) {
            System.out.println("WARNING: unknown mover-like subclasses (have KeyPos/BasePos but not in whitelist):");
            for (Map.Entry<String, Integer> e : unknownMoverSubs.entrySet()) {
                System.out.printf("  %-25s : %d%n", e.getKey(), e.getValue());
            }
        }

        Files.createDirectories(mapsDir);
        Path mapFile = mapsDir.resolve(mapName + ".json");

        ObjectNode root = readOrEmpty(mapFile);
        boolean changed = false;

        changed |= updateMapNorm(root, minX, maxX, minY, maxY, minZ, maxZ);
        changed |= updateScalarIfMissing(root, "symmetric", symmetric);
        changed |= updateSpawnPointsIfMissing(root, spawns);
        changed |= updateJumpPadsIfMissing(root, jumpPads);
        changed |= updatePickups(root, pickups);
        changed |= updateMovers(root, movers);
        changed |= updateElevatorTriggers(root, elevatorTriggers);

        if (!changed) {
            System.out.println("No change — values already up-to-date for map " + mapName);
            return;
        }

        Files.writeString(mapFile, MAPPER.writer(PRETTY_PRINTER).writeValueAsString(root) + "\n");
        System.out.println("Updated " + mapFile);
    }

    // ─── Per-map JSON file mutation ────────────────────────────────────────────

    private static ObjectNode readOrEmpty(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return MAPPER.createObjectNode();
        }
        JsonNode parsed = MAPPER.readTree(file.toFile());
        if (!parsed.isObject()) {
            throw new IllegalStateException("Expected top-level JSON object in " + file);
        }
        return (ObjectNode) parsed;
    }

    /** Rebuild map_norm only if the existing block is missing or has no bounds_* fields.
     *  Edge/k are preserved when present (default 0.98 / 3.0 otherwise). */
    private static boolean updateMapNorm(ObjectNode root,
                                         double minX, double maxX,
                                         double minY, double maxY,
                                         double minZ, double maxZ) {
        ObjectNode existing = root.has("map_norm") && root.get("map_norm").isObject()
            ? (ObjectNode) root.get("map_norm")
            : null;

        if (existing != null && hasAnyBoundsField(existing)) {
            System.out.println("  map_norm bounds already present — leaving as-is (tweakable by user)");
            return false;
        }

        double edge = (existing != null && existing.path("edge").isNumber())
            ? existing.get("edge").asDouble() : DEFAULT_EDGE;
        double k = (existing != null && existing.path("k").isNumber())
            ? existing.get("k").asDouble() : DEFAULT_K;

        ObjectNode rebuilt = MAPPER.createObjectNode();
        rebuilt.put("bounds_min_x", minX);
        rebuilt.put("bounds_max_x", maxX);
        rebuilt.put("bounds_min_y", minY);
        rebuilt.put("bounds_max_y", maxY);
        rebuilt.put("bounds_min_z", minZ);
        rebuilt.put("bounds_max_z", maxZ);
        rebuilt.put("edge", edge);
        rebuilt.put("k", k);

        root.set("map_norm", rebuilt);
        return true;
    }

    private static boolean hasAnyBoundsField(ObjectNode mapNorm) {
        return mapNorm.has("bounds_min_x")
            || mapNorm.has("bounds_max_x")
            || mapNorm.has("bounds_min_y")
            || mapNorm.has("bounds_max_y")
            || mapNorm.has("bounds_min_z")
            || mapNorm.has("bounds_max_z");
    }

    private static boolean updateScalarIfMissing(ObjectNode root, String field, boolean value) {
        if (root.has(field)) {
            System.out.println("  " + field + " already present — leaving as-is (tweakable by user)");
            return false;
        }
        root.put(field, value);
        return true;
    }

    private static boolean updateSpawnPointsIfMissing(ObjectNode root, List<SpawnPoint> spawns) {
        if (root.has("spawn_points")) {
            System.out.println("  spawn_points already present — leaving as-is (tweakable by user)");
            return false;
        }
        ArrayNode arr = root.putArray("spawn_points");
        for (SpawnPoint s : spawns) {
            ObjectNode entry = arr.addObject();
            ArrayNode loc = entry.putArray("location");
            loc.add(s.x); loc.add(s.y); loc.add(s.z);
            entry.put("team", s.team);
        }
        return true;
    }

    private static boolean updateJumpPadsIfMissing(ObjectNode root, List<JumpPad> pads) {
        if (root.has("jump_pads")) {
            System.out.println("  jump_pads already present — leaving as-is (tweakable by user)");
            return false;
        }
        ArrayNode arr = root.putArray("jump_pads");
        for (JumpPad p : pads) {
            ObjectNode entry = arr.addObject();
            ArrayNode loc = entry.putArray("location");
            loc.add(p.x); loc.add(p.y); loc.add(p.z);
            ArrayNode vel = entry.putArray("velocity");
            vel.add(p.vx); vel.add(p.vy); vel.add(p.vz);
        }
        return true;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ─── Symmetry detection (flag-base based) ──────────────────────────────────

    private static boolean isSymmetricByFlagBases(List<FlagBase> flags,
                                                  double minX, double maxX,
                                                  double minY, double maxY) {
        FlagBase team0 = null, team1 = null;
        for (FlagBase f : flags) {
            if (f.team == 0 && team0 == null) team0 = f;
            else if (f.team == 1 && team1 == null) team1 = f;
        }
        if (team0 == null || team1 == null) return false;

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double halfX = (maxX - minX) / 2.0;
        double halfY = (maxY - minY) / 2.0;

        // Reflect team0 around the map center in X/Y (Z ignored — bases can be on different heights).
        double reflectedX = 2 * centerX - team0.x;
        double reflectedY = 2 * centerY - team0.y;
        double dx = reflectedX - team1.x;
        double dy = reflectedY - team1.y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        double tolerance = FLAG_SYMMETRY_TOLERANCE_FRAC * Math.max(halfX, halfY);
        return distance <= tolerance;
    }

    // ─── T3D parsing ───────────────────────────────────────────────────────────

    static Bounds parseBounds(String text) {
        Bounds b = new Bounds();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            String body = am.group(2);
            double[] loc = parseLocation(body);

            Matcher vm = VERTEX_PATTERN.matcher(body);
            boolean hasVertex = false;
            while (vm.find()) {
                hasVertex = true;
                double wx = loc[0] + Double.parseDouble(vm.group(1));
                double wy = loc[1] + Double.parseDouble(vm.group(2));
                double wz = loc[2] + Double.parseDouble(vm.group(3));
                b.extend(wx, wy, wz);
            }
            if (!hasVertex) {
                b.extend(loc[0], loc[1], loc[2]);
            }
            b.actorCount++;
        }
        return b;
    }

    static List<FlagBase> parseFlagBases(String text) {
        List<FlagBase> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            if (!"FlagBase".equals(am.group(1))) continue;
            String body = am.group(2);
            double[] loc = parseLocation(body);
            int team = parseTeam(body);
            result.add(new FlagBase(team, loc[0], loc[1], loc[2]));
        }
        return result;
    }

    static List<SpawnPoint> parsePlayerStarts(String text, List<FlagBase> flags) {
        List<SpawnPoint> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            if (!"PlayerStart".equals(am.group(1))) continue;
            String body = am.group(2);
            double[] loc = parseLocation(body);
            int[] rot = parseRotation(body);
            int team = resolveSpawnTeam(body, loc, flags);
            result.add(new SpawnPoint(loc[0], loc[1], loc[2], rot[0], rot[1], rot[2], team));
        }
        return result;
    }

    static List<JumpPad> parseKickers(String text) {
        List<JumpPad> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            if (!"Kicker".equals(am.group(1))) continue;
            String body = am.group(2);
            double[] loc = parseLocation(body);
            double[] vel = parseKickVelocity(body);
            result.add(new JumpPad(loc[0], loc[1], loc[2], vel[0], vel[1], vel[2]));
        }
        return result;
    }

    private static int resolveSpawnTeam(String body, double[] loc, List<FlagBase> flags) {
        Matcher tm = Pattern.compile("^\\s*TeamNumber=([0-9]+)\\s*$", Pattern.MULTILINE).matcher(body);
        if (tm.find()) return Integer.parseInt(tm.group(1));
        if (flags.isEmpty()) return 0;
        FlagBase nearest = flags.get(0);
        double bestD = distSq(loc, nearest);
        for (int i = 1; i < flags.size(); i++) {
            double d = distSq(loc, flags.get(i));
            if (d < bestD) {
                bestD = d;
                nearest = flags.get(i);
            }
        }
        return nearest.team;
    }

    private static double distSq(double[] loc, FlagBase f) {
        double dx = loc[0] - f.x;
        double dy = loc[1] - f.y;
        double dz = loc[2] - f.z;
        return dx*dx + dy*dy + dz*dz;
    }

    private static double[] parseLocation(String body) {
        return parseVectorField(body, LOCATION_PATTERN);
    }

    private static double[] parseKickVelocity(String body) {
        return parseVectorField(body, KICK_VELOCITY_PATTERN);
    }

    /** Parse a {@code Field=(X=…,Y=…,Z=…)} triple. Components missing from T3D default to 0,
     *  matching UFP behavior (e.g. {@code KickVelocity=(Z=550)} → (0, 0, 550)). */
    private static double[] parseVectorField(String body, Pattern pattern) {
        Matcher m = pattern.matcher(body);
        double x = 0.0, y = 0.0, z = 0.0;
        if (m.find()) {
            for (String kv : m.group(1).split(",")) {
                String[] parts = kv.split("=", 2);
                if (parts.length != 2) continue;
                double v = Double.parseDouble(parts[1].trim());
                switch (parts[0].trim()) {
                    case "X" -> x = v;
                    case "Y" -> y = v;
                    case "Z" -> z = v;
                }
            }
        }
        return new double[] {x, y, z};
    }

    private static int[] parseRotation(String body) {
        Matcher rm = ROTATION_PATTERN.matcher(body);
        int pitch = 0, yaw = 0, roll = 0;
        if (rm.find()) {
            for (String kv : rm.group(1).split(",")) {
                String[] parts = kv.split("=", 2);
                if (parts.length != 2) continue;
                int v = Integer.parseInt(parts[1].trim());
                switch (parts[0].trim()) {
                    case "Pitch" -> pitch = v;
                    case "Yaw"   -> yaw = v;
                    case "Roll"  -> roll = v;
                }
            }
        }
        return new int[] {pitch, yaw, roll};
    }

    private static int parseTeam(String body) {
        Matcher tm = TEAM_PATTERN.matcher(body);
        return tm.find() ? Integer.parseInt(tm.group(1)) : 0;
    }

    // ─── Data classes ──────────────────────────────────────────────────────────

    static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        int actorCount;

        void extend(double x, double y, double z) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
    }

    record FlagBase(int team, double x, double y, double z) {}

    record SpawnPoint(double x, double y, double z, int pitch, int yaw, int roll, int team) {}

    record JumpPad(double x, double y, double z, double vx, double vy, double vz) {}

    record Pickup(String type, String category, double x, double y, double z) {}

    record Mover(
        String name,
        String subclass,
        String initialState,
        double[] baseLocation,       // 3
        int[] baseRotation,          // 3 (pitch, yaw, roll)
        List<double[]> keyPositions, // each entry: 3-element absolute world coords (base + key offset)
        List<int[]> keyRotations,    // each entry: 3-element absolute (base + key offset); empty list when all-zero
        double[] platformMinLocal,   // 3-element brush AABB min (actor-local, pre-BasePos translation)
        double[] platformMaxLocal,   // 3-element brush AABB max
        double moveTime,
        double stayOpenTime,
        double delayTime,
        int numKeys,
        String glideType,
        String encroachType,
        String bumpType,
        String tag,
        String event
    ) {}

    record ElevatorTrigger(
        String name,
        double[] location,            // 3
        int gotoKeyframe,
        double moveTime,
        String triggerType,
        String event,
        double radius,
        double height,
        boolean triggerOnceOnly,
        String classProximityType    // null when not set
    ) {}

    // ─── Pickup parsing ────────────────────────────────────────────────────────

    /** Parse statische pickup-actors uit T3D. Lookup is case-insensitive — T3D namen zoals
     *  {@code armor2}, {@code ripper}, {@code ut_shieldbelt} mappen op canonical Botpack-namen
     *  {@code Armor2}/{@code Ripper}/{@code UT_ShieldBelt}. Onbekende actor-classes worden
     *  stil overgeslagen — {@code pickup-types.json} is de single source of truth. */
    static List<Pickup> parsePickups(String text, PickupTypeRegistry registry) {
        List<Pickup> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            String cls = am.group(1);
            Optional<PickupTypeRegistry.PickupTypeInfo> info = registry.lookupByClassAlias(cls);
            if (info.isEmpty()) continue;
            double[] loc = parseLocation(am.group(2));
            result.add(new Pickup(info.get().canonical(), info.get().category(),
                                  loc[0], loc[1], loc[2]));
        }
        return result;
    }

    /** Print per-category + per-type counts. Helps spotten of een nieuwe map een pickup-class
     *  bevat die NIET in de registry zit (die zal hier nul keren tellen ondanks dat we
     *  ’m in de T3D zien). */
    private static void summarisePickups(List<Pickup> pickups) {
        Map<String, Integer> perCategory = new HashMap<>();
        Map<String, Integer> perType = new HashMap<>();
        for (Pickup p : pickups) {
            perCategory.merge(p.category(), 1, Integer::sum);
            perType.merge(p.type(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : perCategory.entrySet()) {
            System.out.printf("  %-7s : %d%n", e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : perType.entrySet()) {
            System.out.printf("    %-18s %d%n", e.getKey(), e.getValue());
        }
    }

    /** Overschrijft {@code pickups[]} in de map-JSON met de zojuist uit T3D geparseerde
     *  lijst. Een hergeneratie van pickups is altijd gewenst — de T3D is de waarheid en
     *  handmatige edits op pickup-entries zijn niet zinnig (positie + canonical type komen
     *  beide uit de level-data). {@code respawn_seconds} wordt niet meer gesnapshot in de
     *  per-map JSON — die staat alleen nog in {@code pickup-types.json}. */
    private static boolean updatePickups(ObjectNode root, List<Pickup> pickups) {
        ArrayNode prev = (root.has("pickups") && root.get("pickups").isArray())
            ? (ArrayNode) root.get("pickups") : null;
        ArrayNode arr = MAPPER.createArrayNode();
        for (Pickup p : pickups) {
            ObjectNode entry = arr.addObject();
            entry.put("type", p.type());
            entry.put("category", p.category());
            ArrayNode loc = entry.putArray("location");
            loc.add(round3(p.x()));
            loc.add(round3(p.y()));
            loc.add(round3(p.z()));
        }
        if (prev != null && prev.equals(arr)) {
            return false;
        }
        root.set("pickups", arr);
        return true;
    }

    // ─── Mover / ElevatorTrigger parsing ───────────────────────────────────────

    /** Parse Mover-subclass actors uit T3D. Voor elke mover:
     *  <ul>
     *    <li>{@code BasePos} is de wereld-anker van het brush (UT99 zet Location = BasePos
     *        bij PostBeginPlay). KeyPos(N) zijn relatief tot BasePos. We exporteren beide:
     *        {@code base_location} (= BasePos) en {@code key_positions[]} als <i>absolute</i>
     *        wereld-coordinaten — handig voor consumenten die geen offset hoeven op te tellen.</li>
     *    <li>NumKeys = 1 + hoogste keyframe-index met een non-default KeyPos/KeyRot, met een
     *        minimum van {@code DEFAULT_NUM_KEYS} (2) — Mover.uc default. KeyPos(0) is impliciet
     *        (0,0,0) als ie niet in T3D staat (en gelijk aan BasePos in wereld-coords).</li>
     *    <li>{@code key_rotations} wordt alleen gevuld wanneer minstens één keyframe een non-zero
     *        rotatie heeft (anders ruis voor pure liften). Idem voor {@code base_rotation}.</li>
     *    <li>{@code platform_bounds_local} is de AABB van de brush-vertices in actor-lokale
     *        coördinaten. Bij world-coords optellen krijgt consument de actuele platform-AABB
     *        op een gegeven keyframe via {@code base + key_offset + local_bounds}.</li>
     *  </ul>
     *  Onbekende mover-subclasses worden <i>geteld</i> en gerapporteerd, niet stil weggegooid. */
    static List<Mover> parseMovers(String text) {
        List<Mover> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            String cls = am.group(1);
            if (!MOVER_CLASSES.contains(cls)) continue;
            String body = am.group(2);

            double[] location = parseLocation(body);
            int[] baseRotation = parseRotation(body);
            // BasePos uit T3D is meestal aanwezig en gelijk aan Location, maar in zeldzame gevallen
            // is alleen Location aanwezig. Beide checken — Location wint als BasePos ontbreekt.
            double[] basePos = parseVectorField(body, Pattern.compile("BasePos=\\(([^)]+)\\)"));
            // Als BasePos volledig (0,0,0) is en Location niet, neem Location als anker.
            if (basePos[0] == 0.0 && basePos[1] == 0.0 && basePos[2] == 0.0
                    && (location[0] != 0.0 || location[1] != 0.0 || location[2] != 0.0)) {
                basePos = location;
            }

            TreeMap<Integer, double[]> keyPosMap = new TreeMap<>();
            Matcher km = KEY_POS_PATTERN.matcher(body);
            while (km.find()) {
                int idx = Integer.parseInt(km.group(1));
                keyPosMap.put(idx, parseVectorTriple(km.group(2)));
            }
            TreeMap<Integer, int[]> keyRotMap = new TreeMap<>();
            Matcher kr = KEY_ROT_PATTERN.matcher(body);
            while (kr.find()) {
                int idx = Integer.parseInt(kr.group(1));
                keyRotMap.put(idx, parseRotatorTriple(kr.group(2)));
            }

            int numKeys = computeNumKeys(keyPosMap, keyRotMap);
            List<double[]> keyPositionsAbs = new ArrayList<>(numKeys);
            for (int i = 0; i < numKeys; i++) {
                double[] offset = keyPosMap.getOrDefault(i, new double[] {0.0, 0.0, 0.0});
                keyPositionsAbs.add(new double[] {
                    basePos[0] + offset[0],
                    basePos[1] + offset[1],
                    basePos[2] + offset[2]
                });
            }

            boolean anyKeyRot = keyRotMap.values().stream().anyMatch(ExtractMapBoundsMain::nonZeroRotator);
            List<int[]> keyRotationsAbs;
            if (anyKeyRot) {
                keyRotationsAbs = new ArrayList<>(numKeys);
                for (int i = 0; i < numKeys; i++) {
                    int[] off = keyRotMap.getOrDefault(i, new int[] {0, 0, 0});
                    keyRotationsAbs.add(new int[] {
                        baseRotation[0] + off[0],
                        baseRotation[1] + off[1],
                        baseRotation[2] + off[2]
                    });
                }
            } else {
                keyRotationsAbs = List.of();
            }

            double[] platformMin = new double[] {0.0, 0.0, 0.0};
            double[] platformMax = new double[] {0.0, 0.0, 0.0};
            computeLocalAabb(body, platformMin, platformMax);

            String name              = findActorName(body, am.group(0));
            String initialState      = findQuotedString(body, "InitialState").orElse(DEFAULT_INITIAL_STATE);
            double moveTime          = findFloat(body, "MoveTime").orElse(DEFAULT_MOVE_TIME);
            double stayOpenTime      = findFloat(body, "StayOpenTime").orElse(DEFAULT_STAY_OPEN);
            double delayTime         = findFloat(body, "DelayTime").orElse(DEFAULT_DELAY_TIME);
            String glideRaw          = findBareIdent(body, "MoverGlideType").orElse(null);
            String glide             = (glideRaw != null) ? GLIDE_TYPE_MAP.getOrDefault(glideRaw, glideRaw) : DEFAULT_GLIDE;
            String encroachRaw       = findBareIdent(body, "MoverEncroachType").orElse(null);
            String encroach          = (encroachRaw != null) ? ENCROACH_TYPE_MAP.getOrDefault(encroachRaw, encroachRaw) : DEFAULT_ENCROACH;
            String bumpRaw           = findBareIdent(body, "BumpType").orElse(null);
            String bump              = (bumpRaw != null) ? BUMP_TYPE_MAP.getOrDefault(bumpRaw, bumpRaw) : DEFAULT_BUMP;
            String tag               = findQuotedString(body, "Tag").orElse(null);
            String event             = findQuotedString(body, "Event").orElse(null);

            int[] baseRotationOut = nonZeroRotator(baseRotation) ? baseRotation : null;

            result.add(new Mover(
                name, cls, initialState,
                roundVec3(basePos),
                baseRotationOut,
                roundVec3List(keyPositionsAbs),
                keyRotationsAbs,
                roundVec3(platformMin),
                roundVec3(platformMax),
                round3(moveTime), round3(stayOpenTime), round3(delayTime),
                numKeys, glide, encroach, bump, tag, event
            ));
        }
        return result;
    }

    /** Parse ElevatorTrigger actors. Geen subclass-whitelist hier — class moet exact matchen. */
    static List<ElevatorTrigger> parseElevatorTriggers(String text) {
        List<ElevatorTrigger> result = new ArrayList<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            if (!"ElevatorTrigger".equals(am.group(1))) continue;
            String body = am.group(2);
            double[] loc = parseLocation(body);
            String name = findActorName(body, am.group(0));
            int gotoKeyframe = findInt(body, "GotoKeyframe").orElse(0);
            double moveTime  = findFloat(body, "MoveTime").orElse(0.0);
            String trigRaw   = findBareIdent(body, "TriggerType").orElse(null);
            String trigger   = (trigRaw != null) ? TRIGGER_TYPE_MAP.getOrDefault(trigRaw, trigRaw) : DEFAULT_ELEV_TRIGGER_TYPE;
            String event     = findQuotedString(body, "Event").orElse(null);
            double radius    = findFloat(body, "CollisionRadius").orElse(DEFAULT_ELEV_RADIUS);
            double height    = findFloat(body, "CollisionHeight").orElse(DEFAULT_ELEV_HEIGHT);
            boolean once     = findBool(body, "bTriggerOnceOnly").orElse(false);
            String classProx = findBareIdent(body, "ClassProximityType").orElse(null);
            if ("None".equalsIgnoreCase(classProx)) classProx = null;
            result.add(new ElevatorTrigger(
                name, roundVec3(loc), gotoKeyframe, round3(moveTime),
                trigger, event, round3(radius), round3(height), once, classProx
            ));
        }
        return result;
    }

    private static int computeNumKeys(TreeMap<Integer, double[]> keyPosMap, TreeMap<Integer, int[]> keyRotMap) {
        int highest = -1;
        if (!keyPosMap.isEmpty()) highest = Math.max(highest, keyPosMap.lastKey());
        if (!keyRotMap.isEmpty()) highest = Math.max(highest, keyRotMap.lastKey());
        return Math.max(DEFAULT_NUM_KEYS, highest + 1);
    }

    private static void computeLocalAabb(String body, double[] outMin, double[] outMax) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        Matcher vm = VERTEX_PATTERN.matcher(body);
        boolean any = false;
        while (vm.find()) {
            any = true;
            double x = Double.parseDouble(vm.group(1));
            double y = Double.parseDouble(vm.group(2));
            double z = Double.parseDouble(vm.group(3));
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        if (!any) { minX = minY = minZ = maxX = maxY = maxZ = 0.0; }
        outMin[0] = minX; outMin[1] = minY; outMin[2] = minZ;
        outMax[0] = maxX; outMax[1] = maxY; outMax[2] = maxZ;
    }

    private static double[] parseVectorTriple(String body) {
        double x = 0.0, y = 0.0, z = 0.0;
        for (String kv : body.split(",")) {
            String[] parts = kv.split("=", 2);
            if (parts.length != 2) continue;
            double v = Double.parseDouble(parts[1].trim());
            switch (parts[0].trim()) {
                case "X" -> x = v;
                case "Y" -> y = v;
                case "Z" -> z = v;
            }
        }
        return new double[] {x, y, z};
    }

    private static int[] parseRotatorTriple(String body) {
        int pitch = 0, yaw = 0, roll = 0;
        for (String kv : body.split(",")) {
            String[] parts = kv.split("=", 2);
            if (parts.length != 2) continue;
            int v = (int) Math.round(Double.parseDouble(parts[1].trim()));
            switch (parts[0].trim()) {
                case "Pitch" -> pitch = v;
                case "Yaw"   -> yaw = v;
                case "Roll"  -> roll = v;
            }
        }
        return new int[] {pitch, yaw, roll};
    }

    private static boolean nonZeroRotator(int[] r) {
        return r[0] != 0 || r[1] != 0 || r[2] != 0;
    }

    private static double[] roundVec3(double[] v) {
        return new double[] {round3(v[0]), round3(v[1]), round3(v[2])};
    }

    private static List<double[]> roundVec3List(List<double[]> vs) {
        List<double[]> out = new ArrayList<>(vs.size());
        for (double[] v : vs) out.add(roundVec3(v));
        return out;
    }

    /** Extract the in-body {@code Name="…"} (more reliable than the actor-header name, which
     *  some custom map exporters strip). Falls back to the header (group 0) {@code Name=…}
     *  token if no quoted Name is present. */
    private static String findActorName(String body, String header) {
        Optional<String> q = findQuotedString(body, "Name");
        if (q.isPresent()) return q.get();
        Matcher m = Pattern.compile("Name=(\\S+)").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static Optional<Double> findFloat(String body, String fieldName) {
        Matcher m = Pattern.compile(
            "^\\s*" + Pattern.quote(fieldName) + "=([+-]?[0-9]+\\.?[0-9]*)\\s*$",
            Pattern.MULTILINE).matcher(body);
        return m.find() ? Optional.of(Double.parseDouble(m.group(1))) : Optional.empty();
    }

    private static Optional<Integer> findInt(String body, String fieldName) {
        Matcher m = Pattern.compile(
            "^\\s*" + Pattern.quote(fieldName) + "=([+-]?[0-9]+)\\s*$",
            Pattern.MULTILINE).matcher(body);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static Optional<String> findQuotedString(String body, String fieldName) {
        Matcher m = Pattern.compile(
            "^\\s*" + Pattern.quote(fieldName) + "=\"([^\"]*)\"\\s*$",
            Pattern.MULTILINE).matcher(body);
        if (!m.find()) return Optional.empty();
        String v = m.group(1);
        if (v.isEmpty() || "None".equalsIgnoreCase(v)) return Optional.empty();
        return Optional.of(v);
    }

    private static Optional<String> findBareIdent(String body, String fieldName) {
        Matcher m = Pattern.compile(
            "^\\s*" + Pattern.quote(fieldName) + "=([A-Za-z_][A-Za-z0-9_]*)\\s*$",
            Pattern.MULTILINE).matcher(body);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static Optional<Boolean> findBool(String body, String fieldName) {
        Optional<String> raw = findBareIdent(body, fieldName);
        if (raw.isEmpty()) return Optional.empty();
        return Optional.of("True".equalsIgnoreCase(raw.get()));
    }

    /** Telt onbekende mover-subclass-namen — voor stdout-summary tijdens parse. */
    static Map<String, Integer> countUnknownMoverSubclasses(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher am = ACTOR_PATTERN.matcher(text);
        while (am.find()) {
            String cls = am.group(1);
            if (MOVER_CLASSES.contains(cls)) continue;
            String body = am.group(2);
            // Heuristic: if it has KeyPos or BasePos, it's almost certainly a Mover subclass.
            if (body.contains("KeyPos(") || body.contains("BasePos=(")) {
                counts.merge(cls, 1, Integer::sum);
            }
        }
        return counts;
    }

    // ─── Mover / ElevatorTrigger update ────────────────────────────────────────

    /** Overschrijft {@code movers[]} in de map-JSON met de zojuist uit T3D geparseerde lijst.
     *  Net als pickups: T3D is de single source of truth, geen handmatige edits zinnig. */
    private static boolean updateMovers(ObjectNode root, List<Mover> movers) {
        ArrayNode prev = (root.has("movers") && root.get("movers").isArray())
            ? (ArrayNode) root.get("movers") : null;
        ArrayNode arr = MAPPER.createArrayNode();
        for (Mover m : movers) {
            ObjectNode entry = arr.addObject();
            entry.put("name", m.name());
            entry.put("subclass", m.subclass());
            entry.put("initial_state", m.initialState());

            ArrayNode base = entry.putArray("base_location");
            for (double v : m.baseLocation()) base.add(v);

            if (m.baseRotation() != null) {
                ArrayNode br = entry.putArray("base_rotation");
                for (int v : m.baseRotation()) br.add(v);
            }

            ArrayNode keys = entry.putArray("key_positions");
            for (double[] kp : m.keyPositions()) {
                ArrayNode k = keys.addArray();
                for (double v : kp) k.add(v);
            }

            if (!m.keyRotations().isEmpty()) {
                ArrayNode krots = entry.putArray("key_rotations");
                for (int[] kr : m.keyRotations()) {
                    ArrayNode k = krots.addArray();
                    for (int v : kr) k.add(v);
                }
            }

            ObjectNode aabb = entry.putObject("platform_bounds_local");
            ArrayNode bmin = aabb.putArray("min");
            for (double v : m.platformMinLocal()) bmin.add(v);
            ArrayNode bmax = aabb.putArray("max");
            for (double v : m.platformMaxLocal()) bmax.add(v);

            entry.put("move_time", m.moveTime());
            entry.put("stay_open_time", m.stayOpenTime());
            entry.put("delay_time", m.delayTime());
            entry.put("num_keys", m.numKeys());
            entry.put("glide_type", m.glideType());
            entry.put("encroach_type", m.encroachType());
            entry.put("bump_type", m.bumpType());
            if (m.tag() != null)   entry.put("tag", m.tag());
            if (m.event() != null) entry.put("event", m.event());
        }
        if (prev != null && prev.equals(arr)) {
            return false;
        }
        root.set("movers", arr);
        return true;
    }

    private static boolean updateElevatorTriggers(ObjectNode root, List<ElevatorTrigger> triggers) {
        ArrayNode prev = (root.has("elevator_triggers") && root.get("elevator_triggers").isArray())
            ? (ArrayNode) root.get("elevator_triggers") : null;
        ArrayNode arr = MAPPER.createArrayNode();
        for (ElevatorTrigger t : triggers) {
            ObjectNode entry = arr.addObject();
            entry.put("name", t.name());
            ArrayNode loc = entry.putArray("location");
            for (double v : t.location()) loc.add(v);
            entry.put("goto_keyframe", t.gotoKeyframe());
            entry.put("move_time", t.moveTime());
            entry.put("trigger_type", t.triggerType());
            if (t.event() != null) entry.put("event", t.event());
            entry.put("radius", t.radius());
            entry.put("height", t.height());
            entry.put("trigger_once_only", t.triggerOnceOnly());
            if (t.classProximityType() != null) entry.put("class_proximity_type", t.classProximityType());
        }
        if (prev != null && prev.equals(arr)) {
            return false;
        }
        root.set("elevator_triggers", arr);
        return true;
    }

    private static void summariseMovers(List<Mover> movers) {
        Map<String, Integer> perSubclass = new HashMap<>();
        Map<String, Integer> perInitState = new HashMap<>();
        for (Mover m : movers) {
            perSubclass.merge(m.subclass(), 1, Integer::sum);
            perInitState.merge(m.initialState(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : perSubclass.entrySet()) {
            System.out.printf("  %-15s : %d%n", e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : perInitState.entrySet()) {
            System.out.printf("    %-18s %d%n", e.getKey(), e.getValue());
        }
    }
}
