package aiplay.runtime.geo;

import aiplay.dto.CoordinatesDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geodesisch afstandsveld over de beloopbare ruimte van één map.
 *
 * <p>Het veld is een directed graph van voxels (zijde {@code voxelSizeUu}) waarvan de
 * edges <i>geobserveerde</i> pawn-transities zijn uit gameplay-data (zie
 * {@code BuildGeodesicFieldMain}). Afstand tussen twee punten = kortste pad door die
 * graaf — d.w.z. afstand <i>langs routes waarvan bewezen is dat ze beloopbaar zijn</i>,
 * inclusief lifts, jumps en val-routes (die laatste alleen in de gevallen richting).
 *
 * <p>Gebruikt door {@code ObjectiveProgressReward} als potential-functie: in gangen en
 * om obstakels heen wijst de geodesische gradient de route uit, waar euclidische
 * (vogelvlucht-)afstand een lokaal optimum tegen de muur creëert. Bewust NIET gebruikt
 * voor observatie-features (navTarget blijft een kale bearing): het model moet zelf
 * leren navigeren — alleen de reward is topologie-bewust.
 *
 * <p>Query-model: {@link #distanceOrNaN} snap't beide punten op nabije voxels en
 * retourneert {@code min over nodes n nabij from: ||from − c(n)|| + distGraph(n → t)}
 * — continu in {@code from}, zodat per-tick delta's de werkelijke verplaatsing volgen
 * (geen voxel-grootte-sprongen). Punten buiten de dekking of zonder route naar het
 * doel geven {@code NaN}; de caller valt dan terug op euclidische afstand.
 *
 * <p>Thread-safe. Per doel-voxel wordt één reverse-Dijkstra gedraaid en gecached
 * (doelen zijn quasi-statisch: flag bases, drop-sites, carrier-posities).
 */
public final class GeodesicField {

    /** Snap-zoekbereik in voxel-indices rond een query-punt (±N per as). */
    private static final int SNAP_RANGE = 2;

    /** Cache-bound voor doel-voxel → afstandsarray. Bij overflow: clear (doelen zijn
     *  quasi-statisch; een volle cache betekent doorgaans een verlaten episode-context). */
    private static final int MAX_CACHED_TARGETS = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final double voxelSizeUu;

    /** Node-centroids, world-space. Index = node-id. */
    private final double[] cx, cy, cz;

    /** Reverse-adjacency in CSR-vorm: voor node n zijn de INKOMENDE forward-edges
     *  (dus uitgaande edges in de reverse-graaf) {@code revTo[revStart[n] .. revStart[n+1])}. */
    private final int[] revStart;
    private final int[] revTo;
    private final double[] revW;

    /** voxel-key (gepackte ix,iy,iz) → node-id. */
    private final Map<Long, Integer> nodeByVoxel;

    /** doel-node-id → afstanden-array (dist[n] = kortste pad n → doel, forward richting). */
    private final ConcurrentHashMap<Integer, double[]> distCache = new ConcurrentHashMap<>();

    private GeodesicField(double voxelSizeUu, double[] cx, double[] cy, double[] cz,
                          int[] revStart, int[] revTo, double[] revW,
                          Map<Long, Integer> nodeByVoxel) {
        this.voxelSizeUu = voxelSizeUu;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.revStart = revStart;
        this.revTo = revTo;
        this.revW = revW;
        this.nodeByVoxel = nodeByVoxel;
    }

    /* ==================== Construction ==================== */

    /**
     * Bouw een veld uit kale arrays. {@code nodes[i] = [ix, iy, iz, cx, cy, cz]} (voxel-index
     * triple + centroid), {@code edges[j] = [fromNodeIdx, toNodeIdx, weightUu]} (directed).
     * Gedeeld door {@link #load} en de builder (sanity-checks op een vers gebouwd veld).
     */
    public static GeodesicField fromArrays(double voxelSizeUu, double[][] nodes, double[][] edges) {
        if (voxelSizeUu <= 0.0) {
            throw new IllegalArgumentException("voxelSizeUu must be > 0, got " + voxelSizeUu);
        }
        int n = nodes.length;
        double[] cx = new double[n], cy = new double[n], cz = new double[n];
        Map<Long, Integer> byVoxel = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            double[] node = nodes[i];
            if (node.length != 6) {
                throw new IllegalArgumentException("node[" + i + "] must have 6 entries, got " + node.length);
            }
            cx[i] = node[3];
            cy[i] = node[4];
            cz[i] = node[5];
            byVoxel.put(packVoxel((int) node[0], (int) node[1], (int) node[2]), i);
        }

        // CSR voor de reverse-graaf: tel inkomende edges per to-node, dan vul.
        int[] inDegree = new int[n];
        for (double[] e : edges) {
            int to = (int) e[1];
            checkNodeIndex(to, n, "edge.to");
            checkNodeIndex((int) e[0], n, "edge.from");
            inDegree[to]++;
        }
        int[] revStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            revStart[i + 1] = revStart[i] + inDegree[i];
        }
        int[] revTo = new int[edges.length];
        double[] revW = new double[edges.length];
        int[] cursor = new int[n];
        for (double[] e : edges) {
            int from = (int) e[0];
            int to = (int) e[1];
            double w = e[2];
            if (!(w > 0.0) || !Double.isFinite(w)) {
                throw new IllegalArgumentException("edge weight must be finite > 0, got " + w);
            }
            int slot = revStart[to] + cursor[to]++;
            revTo[slot] = from;   // reverse: vanaf 'to' kun je terug naar 'from'
            revW[slot] = w;
        }
        return new GeodesicField(voxelSizeUu, cx, cy, cz, revStart, revTo, revW, byVoxel);
    }

    /** Laad een veld-bestand zoals geschreven door {@code BuildGeodesicFieldMain}. */
    public static GeodesicField load(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        JsonNode sizeNode = root.path("voxel_size_uu");
        if (!sizeNode.isNumber()) {
            throw new IOException("Missing numeric voxel_size_uu in " + file);
        }
        JsonNode nodesNode = root.path("nodes");
        JsonNode edgesNode = root.path("edges");
        if (!nodesNode.isArray() || !edgesNode.isArray()) {
            throw new IOException("Missing nodes[]/edges[] arrays in " + file);
        }
        double[][] nodes = new double[nodesNode.size()][];
        for (int i = 0; i < nodesNode.size(); i++) {
            JsonNode row = nodesNode.get(i);
            nodes[i] = new double[] {
                row.get(0).asDouble(), row.get(1).asDouble(), row.get(2).asDouble(),
                row.get(3).asDouble(), row.get(4).asDouble(), row.get(5).asDouble()
            };
        }
        double[][] edges = new double[edgesNode.size()][];
        for (int i = 0; i < edgesNode.size(); i++) {
            JsonNode row = edgesNode.get(i);
            edges[i] = new double[] {
                row.get(0).asDouble(), row.get(1).asDouble(), row.get(2).asDouble()
            };
        }
        return fromArrays(sizeNode.asDouble(), nodes, edges);
    }

    /* ==================== Query ==================== */

    public int nodeCount() {
        return cx.length;
    }

    public int edgeCount() {
        return revTo.length;
    }

    public double voxelSizeUu() {
        return voxelSizeUu;
    }

    /**
     * Geodesische afstand (UU) van {@code from} naar {@code to} langs de bezoekgraaf,
     * of {@code NaN} wanneer een van beide punten buiten de dekking valt of er geen
     * route bestaat. Richting volgt de geobserveerde transities (val-routes tellen
     * alleen omlaag).
     */
    public double distanceOrNaN(CoordinatesDto from, CoordinatesDto to) {
        if (from == null || to == null) {
            return Double.NaN;
        }
        int targetNode = nearestNode(to.x, to.y, to.z);
        if (targetNode < 0) {
            return Double.NaN;
        }
        double[] dist = distCache.get(targetNode);
        if (dist == null) {
            if (distCache.size() >= MAX_CACHED_TARGETS) {
                distCache.clear();
            }
            dist = distCache.computeIfAbsent(targetNode, this::dijkstraToTarget);
        }

        double best = Double.POSITIVE_INFINITY;
        int fx = floorDiv(from.x), fy = floorDiv(from.y), fz = floorDiv(from.z);
        for (int dx = -SNAP_RANGE; dx <= SNAP_RANGE; dx++) {
            for (int dy = -SNAP_RANGE; dy <= SNAP_RANGE; dy++) {
                for (int dz = -SNAP_RANGE; dz <= SNAP_RANGE; dz++) {
                    Integer n = nodeByVoxel.get(packVoxel(fx + dx, fy + dy, fz + dz));
                    if (n == null) continue;
                    double graphDist = dist[n];
                    if (!Double.isFinite(graphDist)) continue;
                    double total = euclid(from.x, from.y, from.z, cx[n], cy[n], cz[n]) + graphDist;
                    if (total < best) best = total;
                }
            }
        }
        if (!Double.isFinite(best)) {
            return Double.NaN;
        }
        // Rest-afstand doel-centroid → werkelijk doelpunt (constant per doel-voxel; houdt
        // de absolute waarde eerlijk, valt weg in per-tick delta's).
        return best + euclid(to.x, to.y, to.z, cx[targetNode], cy[targetNode], cz[targetNode]);
    }

    /**
     * Fractie van nodes met een eindige route naar het punt — coverage-diagnostiek
     * voor de builder (1.0 = elk bezocht voxel kan het doel bereiken).
     */
    public double reachableFraction(CoordinatesDto to) {
        int targetNode = nearestNode(to.x, to.y, to.z);
        if (targetNode < 0) {
            return 0.0;
        }
        double[] dist = distCache.computeIfAbsent(targetNode, this::dijkstraToTarget);
        int reachable = 0;
        for (double d : dist) {
            if (Double.isFinite(d)) reachable++;
        }
        return reachable / (double) cx.length;
    }

    /* ==================== Internals ==================== */

    /** Dichtstbijzijnde node binnen het snap-bereik (BFS over voxel-ringen is overkill;
     *  brute scan van (2R+1)³ buurcellen — alleen hash-lookups op bestaande keys). */
    private int nearestNode(double x, double y, double z) {
        int ix = floorDiv(x), iy = floorDiv(y), iz = floorDiv(z);
        int bestNode = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int dx = -SNAP_RANGE; dx <= SNAP_RANGE; dx++) {
            for (int dy = -SNAP_RANGE; dy <= SNAP_RANGE; dy++) {
                for (int dz = -SNAP_RANGE; dz <= SNAP_RANGE; dz++) {
                    Integer n = nodeByVoxel.get(packVoxel(ix + dx, iy + dy, iz + dz));
                    if (n == null) continue;
                    double d = euclid(x, y, z, cx[n], cy[n], cz[n]);
                    if (d < bestD) {
                        bestD = d;
                        bestNode = n;
                    }
                }
            }
        }
        return bestNode;
    }

    /** Single-source kortste paden NAAR target (Dijkstra over de reverse-graaf). */
    private double[] dijkstraToTarget(int targetNode) {
        int n = cx.length;
        double[] dist = new double[n];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[targetNode] = 0.0;

        // PriorityQueue van (dist, node); stale entries worden geskipt op dist-check.
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        pq.add(new long[] {Double.doubleToRawLongBits(0.0), targetNode});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            double d = Double.longBitsToDouble(top[0]);
            int node = (int) top[1];
            if (d > dist[node]) continue;
            for (int i = revStart[node]; i < revStart[node + 1]; i++) {
                int next = revTo[i];
                double nd = d + revW[i];
                if (nd < dist[next]) {
                    dist[next] = nd;
                    pq.add(new long[] {Double.doubleToRawLongBits(nd), next});
                }
            }
        }
        return dist;
    }

    private int floorDiv(double v) {
        return (int) Math.floor(v / voxelSizeUu);
    }

    private static double euclid(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Pack 3 voxel-indices (elk ±2^20 bereik) in één long. Publiek: de builder
     *  ({@code BuildGeodesicFieldMain}) voxeliseert met exact dezelfde packing. */
    public static long packVoxel(int ix, int iy, int iz) {
        long bias = 1L << 20;
        return ((ix + bias) << 42) | ((iy + bias) << 21) | (iz + bias);
    }

    private static void checkNodeIndex(int idx, int n, String what) {
        if (idx < 0 || idx >= n) {
            throw new IllegalArgumentException(what + " index out of range: " + idx + " (nodes=" + n + ")");
        }
    }
}
