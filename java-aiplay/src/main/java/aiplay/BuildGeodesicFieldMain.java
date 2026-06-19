package aiplay;

import aiplay.config.PropertyReaderUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.rl.recording.RawGameplayReader;
import aiplay.runtime.config.SessionPaths;
import aiplay.runtime.geo.GeodesicField;
import aiplay.scanners.model.writer.trainingcsvwriter.reader.ReaderService;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.Player;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Bouwt het geodesische afstandsveld voor één map uit gameplay-data en schrijft het naar
 * {@code resources/config/geodesic/<mapKey>.geodesic.json} (gelezen door
 * {@link aiplay.runtime.geo.GeodesicFieldRepository}). NIET in {@code maps/} — alles daar
 * wordt als per-map config gevalideerd (verplichte {@code map_id}) en zou de JVM-start breken.
 *
 * <p><b>Principe:</b> elke pawn-positie in opgenomen gameplay is bewijs van beloopbaarheid;
 * elke overgang tussen twee opeenvolgende frames van dezelfde pawn is bewijs dat je van A
 * naar B kunt (inclusief lifts, jumps en val-routes — die laatste alleen in de gevallen
 * richting: de graaf is directed). De wereld wordt gevoxeliseerd ({@code --voxel-uu}, default
 * 96) en de geobserveerde transities vormen de edges. Geen UT99-pathfinding, geen
 * CSG-analyse: puur observatie. Het veld voedt uitsluitend de reward-Φ
 * ({@code ObjectiveProgressReward}); observatie-features blijven onaangetast.
 *
 * <p><b>Bronnen</b> (alle optioneel, default aan wanneer de directories bestaan):
 * <ul>
 *   <li>{@code json-recording-sessions}-zips (RecordLauncher / demo-opnames): de rauwe
 *       UC-{@code GameState.Players[]} bevat ALLE niet-spectator pawns per frame.
 *   <li>{@code .rec.gz} raw-gameplay-recordings (live bots, capture-mode): per tick een
 *       {@code GameStateDto} → playerPawn + enemies[] + teammates[].
 *   <li>{@code position-traces} CSV's ({@code PositionTraceLogger}, normale play): de
 *       self-bootstrapping bron — elke gespeelde minuut levert dekking, geen opname nodig.
 *       Formaat: {@code # map=<key>}-sectieheaders + {@code tMillis,sessionId,x,y,z}-regels.
 * </ul>
 *
 * <p>Transitie-acceptatie: zelfde pawn-naam, beide frames levend, {@code 0 < dt ≤ 600 ms},
 * verplaatsing ≤ 250 UU (filtert respawns/teleports — teleporter-edges zijn een bewuste
 * v1-beperking, zie docs/rewards/geodesic-distance-field.md). Sessies/files met een andere
 * map worden geskipt op het eerste frame.
 *
 * <p>Re-runs zijn idempotent over dezelfde data en additief over nieuwe data: het veld wordt
 * volledig herbouwd uit wat er aan recordings ligt — periodiek herbouwen naarmate er meer
 * op de map gespeeld is, verfijnt de dekking (handmatig; geen auto-orchestratie).
 *
 * <p>Usage:
 * <pre>
 *   BuildGeodesicFieldMain &lt;mapName&gt; [--voxel-uu 96] [--min-transitions 1]
 *       [--json-dir &lt;dir&gt;]... [--raw-dir &lt;dir&gt;]... [--positions-dir &lt;dir&gt;]...
 *       [--maps-dir &lt;dir&gt;] [--dry-run]
 * </pre>
 */
public final class BuildGeodesicFieldMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max tijdsgat tussen twee frames van dezelfde pawn om nog een transitie te tellen. */
    private static final long MAX_FRAME_GAP_MS = 600L;

    /** Max verplaatsing per geaccepteerde transitie. Loop ~30 UU/frame, dodge ~50, val ~75;
     *  respawn/teleport-sprongen liggen ver daarboven. */
    private static final double MAX_STEP_UU = 250.0;

    private BuildGeodesicFieldMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].startsWith("--")) {
            System.err.println("Usage: BuildGeodesicFieldMain <mapName> [--voxel-uu 96] "
                + "[--min-transitions 1] [--json-dir <dir>]... [--raw-dir <dir>]... "
                + "[--maps-dir <dir>] [--dry-run]");
            System.exit(2);
        }
        String mapNameArg = args[0];
        double voxelUu = 96.0;
        int minTransitions = 1;
        List<Path> jsonDirs = new ArrayList<>();
        List<Path> rawDirs = new ArrayList<>();
        List<Path> positionsDirs = new ArrayList<>();
        Path mapsDir = null;
        boolean dryRun = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--voxel-uu" -> voxelUu = Double.parseDouble(args[++i]);
                case "--min-transitions" -> minTransitions = Integer.parseInt(args[++i]);
                case "--json-dir" -> jsonDirs.add(Path.of(args[++i]));
                case "--raw-dir" -> rawDirs.add(Path.of(args[++i]));
                case "--positions-dir" -> positionsDirs.add(Path.of(args[++i]));
                case "--maps-dir" -> mapsDir = Path.of(args[++i]);
                case "--dry-run" -> dryRun = true;
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }
        if (mapsDir == null) {
            mapsDir = PropertyReaderUtils.projectRoot().toPath().resolve("resources/config/maps");
        }

        // Bestandsnaam-casing volgt de bestaande per-map JSON (zelfde resolutie als runtime).
        String mapKey = resolveMapKeyCasing(mapsDir, mapNameArg);
        System.out.println("[geodesic] map=" + mapKey + " voxel=" + voxelUu
            + "uu minTransitions=" + minTransitions);

        if (jsonDirs.isEmpty()) {
            Path def = Path.of(SessionPaths.getSessionsBaseDir(), "json-recording-sessions");
            if (Files.isDirectory(def)) jsonDirs.add(def);
        }
        if (rawDirs.isEmpty()) {
            try {
                Path def = Path.of(SessionPaths.getRecordingsDir());
                if (Files.isDirectory(def)) rawDirs.add(def);
            } catch (RuntimeException e) {
                System.out.println("[geodesic] recordings dir niet geconfigureerd — raw-bron overgeslagen");
            }
        }
        if (positionsDirs.isEmpty()) {
            Path def = Path.of(SessionPaths.getSessionsBaseDir(), "position-traces");
            if (Files.isDirectory(def)) positionsDirs.add(def);
        }

        Accumulator acc = new Accumulator(voxelUu);
        int jsonSessions = scanJsonSessions(jsonDirs, mapKey, acc);
        int rawFiles = scanRawRecordings(rawDirs, mapKey, acc);
        int positionFiles = scanPositionTraces(positionsDirs, mapKey, acc);

        System.out.printf("[geodesic] bronnen: %d json-sessies, %d raw-files, %d position-traces → frames=%d punten=%d transities=%d%n",
            jsonSessions, rawFiles, positionFiles, acc.framesSeen, acc.pointsSeen, acc.transitionsSeen);
        if (acc.transitionsSeen == 0) {
            System.err.println("[geodesic] GEEN transities gevonden voor map " + mapKey
                + " — is er gameplay-data (json-recording-sessions of .rec.gz) van deze map?");
            System.exit(1);
        }

        FieldData field = acc.build(minTransitions);
        System.out.printf("[geodesic] veld: %d nodes, %d edges (na min-transitions filter)%n",
            field.nodes.size(), field.edges.size());

        runSanityChecks(mapsDir, mapKey, voxelUu, field);

        if (dryRun) {
            System.out.println("[geodesic] --dry-run: niets geschreven");
            return;
        }
        Path outDir = mapsDir.toAbsolutePath().getParent().resolve("geodesic");
        Files.createDirectories(outDir);
        Path out = outDir.resolve(mapKey + ".geodesic.json");
        writeField(out, mapKey, voxelUu, jsonSessions, rawFiles, positionFiles, acc, field);
        System.out.println("[geodesic] geschreven: " + out);
        System.out.println("[geodesic] activeren: zet \"geodesic_field\" : true in resources/config/maps/"
            + mapKey + ".json en sync resources/config/ naar de play-machines");
    }

    /* ==================== Bronnen ==================== */

    private static int scanJsonSessions(List<Path> roots, String mapKey, Accumulator acc) throws Exception {
        ReaderService reader = new ReaderService();
        int used = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> zips;
            try (Stream<Path> walk = Files.walk(root)) {
                // Alleen .zip aanleveren: ReaderService zou een directory met losse JSON
                // zippen en de bron VERWIJDEREN — dat mag een leestool nooit triggeren.
                zips = walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted().toList();
            }
            for (Path zip : zips) {
                SessionScan scan = new SessionScan(mapKey, acc, "json:" + zip);
                try {
                    reader.forEachGameState(zip.toString(), scan::acceptJson);
                } catch (Exception e) {
                    System.err.println("[geodesic] skip " + zip + ": " + e.getMessage());
                    continue;
                }
                if (scan.usedFrames > 0) {
                    used++;
                    System.out.printf("[geodesic]   + %s (%d frames)%n", zip.getFileName(), scan.usedFrames);
                }
            }
        }
        return used;
    }

    private static int scanRawRecordings(List<Path> roots, String mapKey, Accumulator acc) throws IOException {
        int used = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> recs;
            try (Stream<Path> walk = Files.walk(root)) {
                recs = walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".rec.gz"))
                    .sorted().toList();
            }
            for (Path rec : recs) {
                SessionScan scan = new SessionScan(mapKey, acc, "raw:" + rec);
                try (RawGameplayReader r = RawGameplayReader.open(rec)) {
                    RawGameplayReader.Tick tick;
                    while ((tick = r.nextTick()) != null) {
                        if (!scan.acceptDto(tick.gameState())) {
                            break; // andere map — hele file skippen
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[geodesic] skip " + rec + ": " + e.getMessage());
                    continue;
                }
                if (scan.usedFrames > 0) {
                    used++;
                    System.out.printf("[geodesic]   + %s (%d frames)%n", rec.getFileName(), scan.usedFrames);
                }
            }
        }
        return used;
    }

    /**
     * Position-trace CSV's van {@code PositionTraceLogger}: {@code # map=<key>}-regels openen
     * een sectie (tracks resetten op de grens), data-regels zijn {@code tMillis,sessionId,x,y,z}.
     * Een actief beschreven of half-gerssync'te file kan een afgekapte laatste regel hebben —
     * onparseerbare regels worden geskipt.
     */
    private static int scanPositionTraces(List<Path> roots, String mapKey, Accumulator acc) throws IOException {
        int used = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.startsWith("pos_") && name.endsWith(".csv");
                }).sorted().toList();
            }
            for (Path file : files) {
                long usedLines = 0;
                int section = 0;
                boolean sectionMatches = false;
                try (var reader = Files.newBufferedReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("# map=")) {
                            section++;
                            String sectionMap = aiplay.runtime.context.ActiveMapContext
                                .normalize(line.substring("# map=".length()));
                            sectionMatches = mapKey.equalsIgnoreCase(sectionMap);
                            continue;
                        }
                        if (!sectionMatches || line.isBlank()) continue;
                        int c1 = line.indexOf(',');
                        int c2 = line.indexOf(',', c1 + 1);
                        int c3 = line.indexOf(',', c2 + 1);
                        int c4 = line.indexOf(',', c3 + 1);
                        if (c1 < 0 || c2 < 0 || c3 < 0 || c4 < 0) continue;
                        try {
                            long t = Long.parseLong(line.substring(0, c1));
                            String sid = line.substring(c1 + 1, c2);
                            double x = Double.parseDouble(line.substring(c2 + 1, c3));
                            double y = Double.parseDouble(line.substring(c3 + 1, c4));
                            double z = Double.parseDouble(line.substring(c4 + 1));
                            acc.framesSeen++;
                            acc.track("pos:" + file + "#" + section + "|" + sid, t, x, y, z, true);
                            usedLines++;
                        } catch (NumberFormatException e) {
                            // afgekapte of corrupte regel — skip
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[geodesic] skip " + file + ": " + e.getMessage());
                    continue;
                }
                if (usedLines > 0) {
                    used++;
                    System.out.printf("[geodesic]   + %s (%d samples)%n", file.getFileName(), usedLines);
                }
            }
        }
        return used;
    }

    /** Per-sessie scanner: map-filter op het eerste frame + pawn-tracks op naam. */
    private static final class SessionScan {
        private final String mapKey;
        private final Accumulator acc;
        private final String sessionTag;
        private Boolean mapMatches;
        int usedFrames;

        SessionScan(String mapKey, Accumulator acc, String sessionTag) {
            this.mapKey = mapKey;
            this.acc = acc;
            this.sessionTag = sessionTag;
        }

        void acceptJson(GameState gs) {
            if (Boolean.FALSE.equals(mapMatches)) return;
            if (mapMatches == null) {
                String name = (gs.MapInfo != null) ? gs.MapInfo.MapName : null;
                // Zelfde normalisatie als runtime (strip ?query en .LevelInfo0/.unr suffix).
                mapMatches = mapKey.equalsIgnoreCase(
                    aiplay.runtime.context.ActiveMapContext.normalize(name));
                if (!mapMatches) return;
            }
            usedFrames++;
            acc.framesSeen++;
            if (gs.Players == null) return;
            for (Player p : gs.Players) {
                if (p == null || p.Name == null || p.Location == null) continue;
                if (parseBool(p.bIsSpectator)) continue;
                double[] loc = parseTriple(p.Location);
                if (loc == null) continue;
                boolean alive = parseIntSafe(p.Health) > 0;
                acc.track(sessionTag + "|" + p.Name, gs.timestampMillis, loc[0], loc[1], loc[2], alive);
            }
        }

        /** @return false zodra blijkt dat deze file een andere map bevat (caller stopt). */
        boolean acceptDto(GameStateDto dto) {
            if (Boolean.FALSE.equals(mapMatches)) return false;
            if (mapMatches == null) {
                String name = (dto != null && dto.mapInfo != null) ? dto.mapInfo.mapName : null;
                mapMatches = mapKey.equalsIgnoreCase(
                    aiplay.runtime.context.ActiveMapContext.normalize(name));
                if (!mapMatches) return false;
            }
            if (dto == null) return true;
            usedFrames++;
            acc.framesSeen++;
            trackDto(dto.playerPawn, dto.timestampMillis);
            if (dto.enemies != null) {
                for (PlayerDto e : dto.enemies) trackDto(e, dto.timestampMillis);
            }
            if (dto.teammates != null) {
                for (PlayerDto t : dto.teammates) trackDto(t, dto.timestampMillis);
            }
            return true;
        }

        private void trackDto(PlayerDto p, long ts) {
            if (p == null || p.name == null || p.location == null) return;
            acc.track(sessionTag + "|" + p.name, ts, p.location.x, p.location.y, p.location.z,
                p.health > 0);
        }
    }

    /* ==================== Accumulator ==================== */

    private static final class Accumulator {
        private final double voxelUu;
        /** voxel-key → [sumX, sumY, sumZ, count] */
        private final Map<Long, double[]> voxels = new HashMap<>(1 << 16);
        /** from-voxel → (to-voxel → count) */
        private final Map<Long, Map<Long, Integer>> edges = new HashMap<>(1 << 16);
        /** pawn-track-key → laatst geziene levende positie. */
        private final Map<String, TrackState> tracks = new HashMap<>();

        long framesSeen, pointsSeen, transitionsSeen;

        Accumulator(double voxelUu) {
            this.voxelUu = voxelUu;
        }

        void track(String trackKey, long tMillis, double x, double y, double z, boolean alive) {
            if (!alive) {
                tracks.remove(trackKey); // dood → track breken; respawn start een nieuwe
                return;
            }
            pointsSeen++;
            long vox = voxelKey(x, y, z);
            double[] v = voxels.computeIfAbsent(vox, k -> new double[4]);
            v[0] += x; v[1] += y; v[2] += z; v[3] += 1.0;

            TrackState prev = tracks.get(trackKey);
            if (prev != null) {
                long dt = tMillis - prev.tMillis;
                double dx = x - prev.x, dy = y - prev.y, dz = z - prev.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dt > 0 && dt <= MAX_FRAME_GAP_MS && dist <= MAX_STEP_UU && prev.voxelKey != vox) {
                    edges.computeIfAbsent(prev.voxelKey, k -> new HashMap<>())
                        .merge(vox, 1, Integer::sum);
                    transitionsSeen++;
                }
                prev.update(x, y, z, tMillis, vox);
            } else {
                tracks.put(trackKey, new TrackState(x, y, z, tMillis, vox));
            }
        }

        private long voxelKey(double x, double y, double z) {
            return GeodesicField.packVoxel(
                (int) Math.floor(x / voxelUu),
                (int) Math.floor(y / voxelUu),
                (int) Math.floor(z / voxelUu));
        }

        FieldData build(int minTransitions) {
            // Nodes = voxels die deelnemen aan ≥1 overgebleven edge.
            Map<Long, Integer> nodeIndex = new HashMap<>();
            List<long[]> keptEdges = new ArrayList<>();
            for (Map.Entry<Long, Map<Long, Integer>> from : edges.entrySet()) {
                for (Map.Entry<Long, Integer> to : from.getValue().entrySet()) {
                    if (to.getValue() < minTransitions) continue;
                    keptEdges.add(new long[] {from.getKey(), to.getKey()});
                    nodeIndex.putIfAbsent(from.getKey(), nodeIndex.size());
                    nodeIndex.putIfAbsent(to.getKey(), nodeIndex.size());
                }
            }
            double[][] nodes = new double[nodeIndex.size()][];
            long bias = 1L << 20;
            for (Map.Entry<Long, Integer> e : nodeIndex.entrySet()) {
                long key = e.getKey();
                int ix = (int) ((key >>> 42) - bias);
                int iy = (int) (((key >>> 21) & ((1L << 21) - 1)) - bias);
                int iz = (int) ((key & ((1L << 21) - 1)) - bias);
                double[] v = voxels.get(key);
                nodes[e.getValue()] = new double[] {
                    ix, iy, iz, v[0] / v[3], v[1] / v[3], v[2] / v[3]
                };
            }
            List<double[]> edgeRows = new ArrayList<>(keptEdges.size());
            for (long[] e : keptEdges) {
                int from = nodeIndex.get(e[0]);
                int to = nodeIndex.get(e[1]);
                double dx = nodes[from][3] - nodes[to][3];
                double dy = nodes[from][4] - nodes[to][4];
                double dz = nodes[from][5] - nodes[to][5];
                double w = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
                edgeRows.add(new double[] {from, to, Math.round(w * 10.0) / 10.0});
            }
            return new FieldData(List.of(nodes), edgeRows);
        }
    }

    private record FieldData(List<double[]> nodes, List<double[]> edges) {}

    /** Mutable laatst-geziene staat van één pawn-track (vermijdt long-in-double packing). */
    private static final class TrackState {
        double x, y, z;
        long tMillis;
        long voxelKey;

        TrackState(double x, double y, double z, long tMillis, long voxelKey) {
            update(x, y, z, tMillis, voxelKey);
        }

        void update(double x, double y, double z, long tMillis, long voxelKey) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tMillis = tMillis;
            this.voxelKey = voxelKey;
        }
    }

    /* ==================== Sanity & output ==================== */

    /** Spawn-route sanity: per INDIVIDUEEL spawn-punt (geen centroid — dat kan in een muur of
     *  niet-belopen ruimte vallen) de route naar de spawns van het andere team, beide richtingen.
     *  Rapporteert hoeveel spawn-paren verbonden zijn + de mediane geo-afstand vs euclidisch.
     *  Gebruikt exact dezelfde query-code als runtime ({@link GeodesicField}). NB: een fors deel
     *  van de nodes is normaal "val-debris" (one-way kolommen de void in) — onbereikbaarheid
     *  van die nodes is correct gedrag, geen dekkingsprobleem. */
    private static void runSanityChecks(Path mapsDir, String mapKey, double voxelUu, FieldData data)
            throws IOException {
        GeodesicField field = GeodesicField.fromArrays(voxelUu,
            data.nodes.toArray(double[][]::new), data.edges.toArray(double[][]::new));

        Path mapJson = mapsDir.resolve(mapKey + ".json");
        JsonNode root = MAPPER.readTree(mapJson.toFile());
        JsonNode spawns = root.path("spawn_points");
        if (!spawns.isArray() || spawns.isEmpty()) {
            System.out.println("[geodesic] sanity: geen spawn_points in " + mapJson.getFileName()
                + " — spawn-afstandscheck overgeslagen");
            return;
        }
        List<CoordinatesDto> team0 = spawnPoints(spawns, 0);
        List<CoordinatesDto> team1 = spawnPoints(spawns, 1);
        if (team0.isEmpty() || team1.isEmpty()) {
            System.out.println("[geodesic] sanity: spawn_points missen een team — check overgeslagen");
            return;
        }
        int pairs = 0, connected = 0;
        List<Double> ratios = new ArrayList<>();
        for (CoordinatesDto s0 : team0) {
            for (CoordinatesDto s1 : team1) {
                pairs++;
                double geo = field.distanceOrNaN(s0, s1);
                if (!Double.isNaN(geo)) {
                    connected++;
                    double eucl = Math.sqrt(sq(s0.x - s1.x) + sq(s0.y - s1.y) + sq(s0.z - s1.z));
                    if (eucl > 1e-9) ratios.add(geo / eucl);
                }
            }
        }
        double connectedFrac = connected / (double) pairs;
        String ratioStr = "n.v.t.";
        if (!ratios.isEmpty()) {
            ratios.sort(Double::compare);
            ratioStr = String.format(Locale.ROOT, "%.2f", ratios.get(ratios.size() / 2));
        }
        System.out.printf("[geodesic] sanity spawn-routes: %d/%d cross-team spawn-paren verbonden (%.0f%%), "
            + "mediane geo/eucl-ratio %s%n", connected, pairs, connectedFrac * 100.0, ratioStr);
        if (connectedFrac < 0.5) {
            System.out.println("[geodesic] WAARSCHUWING: minder dan de helft van de spawn-routes is verbonden"
                + " — dekking is nog dun (meer gameplay op de map nodig); runtime valt op de gaten terug op euclidisch");
        }
    }

    private static List<CoordinatesDto> spawnPoints(JsonNode spawns, int team) {
        List<CoordinatesDto> out = new ArrayList<>();
        for (JsonNode s : spawns) {
            if (s.path("team").asInt(-1) != team) continue;
            JsonNode loc = s.path("location");
            if (!loc.isArray() || loc.size() < 3) continue;
            CoordinatesDto c = new CoordinatesDto();
            c.x = loc.get(0).asDouble();
            c.y = loc.get(1).asDouble();
            c.z = loc.get(2).asDouble();
            out.add(c);
        }
        return out;
    }

    private static void writeField(Path out, String mapKey, double voxelUu,
                                   int jsonSessions, int rawFiles, int positionFiles,
                                   Accumulator acc, FieldData data) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("map", mapKey);
        root.put("voxel_size_uu", voxelUu);
        ObjectNode builtFrom = root.putObject("built_from");
        builtFrom.put("json_sessions", jsonSessions);
        builtFrom.put("raw_files", rawFiles);
        builtFrom.put("position_trace_files", positionFiles);
        builtFrom.put("frames", acc.framesSeen);
        builtFrom.put("points", acc.pointsSeen);
        builtFrom.put("transitions", acc.transitionsSeen);
        ArrayNode nodes = root.putArray("nodes");
        for (double[] n : data.nodes) {
            ArrayNode row = nodes.addArray();
            row.add((int) n[0]).add((int) n[1]).add((int) n[2]);
            row.add(Math.round(n[3] * 10.0) / 10.0)
               .add(Math.round(n[4] * 10.0) / 10.0)
               .add(Math.round(n[5] * 10.0) / 10.0);
        }
        ArrayNode edges = root.putArray("edges");
        for (double[] e : data.edges) {
            edges.addArray().add((int) e[0]).add((int) e[1]).add(e[2]);
        }
        // Bewust compact (geen pretty print): data-asset van duizenden rijen, geen hand-edit-file.
        Files.writeString(out, MAPPER.writeValueAsString(root));
    }

    /* ==================== Helpers ==================== */

    private static String resolveMapKeyCasing(Path mapsDir, String requested) throws IOException {
        Path exact = mapsDir.resolve(requested + ".json");
        if (Files.isRegularFile(exact)) return requested;
        if (Files.isDirectory(mapsDir)) {
            try (Stream<Path> list = Files.list(mapsDir)) {
                for (Path p : list.toList()) {
                    String name = p.getFileName().toString();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                    String base = name.substring(0, name.length() - 5);
                    if (base.equalsIgnoreCase(requested)) return base;
                }
            }
        }
        throw new IllegalStateException("Geen resources/config/maps/" + requested
            + ".json gevonden (bounds eerst: scripts/deploy/extract-map-bounds.sh " + requested + ")");
    }

    private static double[] parseTriple(String csv) {
        String[] parts = csv.split(",");
        if (parts.length < 3) return null;
        try {
            return new double[] {
                Double.parseDouble(parts[0].trim()),
                Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntSafe(String v) {
        if (v == null) return 0;
        try {
            return (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean parseBool(String v) {
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "ONBEREIKBAAR" : String.format(Locale.ROOT, "%.0fuu", v);
    }
}
