package aiplay.rl.trace;

import aiplay.config.PropertyReaderUtils;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.runtime.config.SessionPaths;
import aiplay.runtime.context.MapKey;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lichtgewicht positie-tracer voor de eigen bot-pawns tijdens normale play.
 *
 * <p>Doel: het geodesische afstandsveld self-bootstrapping maken — elke gespeelde minuut
 * levert bezochte posities/transities, zonder demo-opnames of capture-mode. De traces worden
 * door {@code scripts/deploy/build-geodesic-field.sh} (pull-stap) naar dev gehaald en door
 * {@code BuildGeodesicFieldMain --positions-dir} als bron gelezen. Zie
 * docs/rewards/geodesic-distance-field.md.
 *
 * <p>Bestandsformaat ({@code $SESSIONS_DIR/position-traces/pos_<startMillis>_<pid>.csv}):
 * <pre>
 *   # map=CTF-Face
 *   1786353071123,instance-0-[BETA]Falcon,6835.4,-1379.2,-1983.9,0,1
 * </pre>
 * Kolommen: {@code timestampMs,sessionId,x,y,z,hasFlag(0/1),team(0/1)}. hasFlag+team toegevoegd
 * 2026-06-15 voor carry-home-diagnostiek (waar draagt/dropt de carrier de enemy-vlag).
 * Eén file per JVM-run; alle bots van alle instanties in dezelfde file (de sessionId is per
 * bot-instantie uniek, dus tracks van verschillende UT99-servers in één JVM mengen niet).
 * Een {@code # map=}-regel opent elke map-sectie; de builder reset tracks op die grens.
 *
 * <p>Config (VERPLICHT, {@code resources/config/runtime.json → position_trace}): {@code enabled},
 * {@code min_interval_ms} (per-bot sample-interval; moet < 600 ms blijven — de builder's
 * max-frame-gap — anders worden samples niet tot transities verbonden), {@code retention_days}
 * (oude trace-files worden bij start opgeruimd).
 *
 * <p>Fail-safe by design: I/O-fouten loggen één warning en schakelen de tracer uit — de
 * bot-loop mag hier nooit op breken. Alleen levende pawns worden gelogd (dood → gap →
 * de builder breekt de track via zijn gap/step-limieten).
 */
public final class PositionTraceLogger {

    private static final Logger LOG = Logger.getLogger(PositionTraceLogger.class.getName());

    private static final long FLUSH_INTERVAL_MS = 2_000L;

    private static volatile PositionTraceLogger instance;

    private final boolean enabled;
    private final long minIntervalMs;
    private final BufferedWriter writer;
    private final Map<String, Long> lastWriteBySession = new HashMap<>();
    private String currentMapKey;
    private long lastFlushMs;
    private boolean broken;

    public static PositionTraceLogger shared() {
        PositionTraceLogger local = instance;
        if (local == null) {
            synchronized (PositionTraceLogger.class) {
                local = instance;
                if (local == null) {
                    local = create();
                    instance = local;
                }
            }
        }
        return local;
    }

    private PositionTraceLogger(boolean enabled, long minIntervalMs, BufferedWriter writer) {
        this.enabled = enabled;
        this.minIntervalMs = minIntervalMs;
        this.writer = writer;
    }

    private static PositionTraceLogger create() {
        JsonNode cfg = PropertyReaderUtils.getSubtree("/runtime/position_trace");
        if (cfg == null || !cfg.isObject()) {
            throw new IllegalStateException(
                "Missing 'position_trace' block in resources/config/runtime.json"
                + " (vereist: enabled, min_interval_ms, retention_days)");
        }
        boolean enabled = requireBoolean(cfg, "enabled");
        long minIntervalMs = requireLong(cfg, "min_interval_ms");
        int retentionDays = (int) requireLong(cfg, "retention_days");
        if (!enabled) {
            LOG.info("[PositionTrace] disabled (runtime.json position_trace.enabled=false)");
            return new PositionTraceLogger(false, 0L, null);
        }
        try {
            Path dir = Path.of(SessionPaths.getSessionsBaseDir(), "position-traces");
            Files.createDirectories(dir);
            cleanupOldTraces(dir, retentionDays);
            Path file = dir.resolve("pos_" + System.currentTimeMillis() + "_"
                + ProcessHandle.current().pid() + ".csv");
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            PositionTraceLogger logger = new PositionTraceLogger(true, minIntervalMs, writer);
            Runtime.getRuntime().addShutdownHook(new Thread(logger::closeQuietly, "pos-trace-close"));
            LOG.info("[PositionTrace] logging to " + file + " (interval=" + minIntervalMs
                + "ms, retention=" + retentionDays + "d)");
            return logger;
        } catch (IOException e) {
            LOG.warning("[PositionTrace] init failed — tracer disabled: " + e);
            return new PositionTraceLogger(false, 0L, null);
        }
    }

    /** Log de eigen pawn-positie van deze bot voor dit frame (rate-limited per sessionId). */
    public void log(String sessionId, GameStateDto frame) {
        if (!enabled || broken || frame == null || sessionId == null) {
            return;
        }
        PlayerDto pawn = frame.playerPawn;
        if (pawn == null || pawn.location == null || pawn.health <= 0) {
            return;
        }
        long now = frame.timestampMillis > 0 ? frame.timestampMillis : System.currentTimeMillis();
        synchronized (this) {
            Long last = lastWriteBySession.get(sessionId);
            if (last != null && (now - last) < minIntervalMs) {
                return;
            }
            lastWriteBySession.put(sessionId, now);
            try {
                String mapKey = MapKey.fromFrame(frame);
                if (!mapKey.equals(currentMapKey)) {
                    writer.write("# map=" + mapKey);
                    writer.newLine();
                    currentMapKey = mapKey;
                }
                writer.write(now + "," + sessionId + ","
                    + fmt(pawn.location.x) + "," + fmt(pawn.location.y) + "," + fmt(pawn.location.z)
                    + "," + (pawn.hasFlag ? 1 : 0) + "," + pawn.team);
                writer.newLine();
                long wall = System.currentTimeMillis();
                if (wall - lastFlushMs >= FLUSH_INTERVAL_MS) {
                    writer.flush();
                    lastFlushMs = wall;
                }
            } catch (IOException e) {
                broken = true;
                LOG.warning("[PositionTrace] write failed — tracer disabled: " + e);
            }
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private synchronized void closeQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // shutdown best-effort
        }
    }

    private static void cleanupOldTraces(Path dir, int retentionDays) {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "pos_*.csv")) {
            for (Path p : stream) {
                if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            LOG.warning("[PositionTrace] retention cleanup failed: " + e);
        }
    }

    private static boolean requireBoolean(JsonNode cfg, String field) {
        JsonNode n = cfg.path(field);
        if (!n.isBoolean()) {
            throw new IllegalStateException(
                "runtime.json position_trace." + field + " must be a boolean");
        }
        return n.asBoolean();
    }

    private static long requireLong(JsonNode cfg, String field) {
        JsonNode n = cfg.path(field);
        if (!n.isIntegralNumber()) {
            throw new IllegalStateException(
                "runtime.json position_trace." + field + " must be an integer");
        }
        return n.asLong();
    }
}
