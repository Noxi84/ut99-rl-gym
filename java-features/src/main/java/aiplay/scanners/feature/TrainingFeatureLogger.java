package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.config.global.GlobalConfigRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public interface TrainingFeatureLogger {

    // Per (sessionId, featureId, method) throttling + lastValue
    ConcurrentHashMap<String, FeatureLogState> STATE = new ConcurrentHashMap<String, FeatureLogState>();

    /**
     * Elke String is een "log file id" => SessionLogPaths.featureLog(id)
     * Alle feature logs worden naar AL deze logfiles geschreven.
     */
    Set<String> getLogFiles();

    default void onRealTimeResolve(String sessionId, String modelKey, String featureId, GameStateDto frame, Float value) {
        logFeature(sessionId, featureId, modelKey + "-onRealTimeResolve", value, frame);
    }

    default void onCsvResolve(String sessionId, String modelKey, String featureId, List<GameStateDto> frames, GameStateDto current, Float value) {
        logFeature(sessionId, featureId, modelKey + "-onCsvResolve", value, current);
    }

    default void onEnrichBatch(String sessionId, String modelKey, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        logEnrich(sessionId, modelKey + "-onEnrichBatch", featureComponent, frames);
        logEnrichFeatureValues(sessionId, modelKey, "onEnrichBatchValue", featureComponent, frames);
    }

    default void onEnrichIncremental(String sessionId, String modelKey, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        logEnrich(sessionId, modelKey + "-onEnrichIncremental", featureComponent, frames);
        logEnrichFeatureValues(sessionId, modelKey, "onEnrichIncrementalValue", featureComponent, frames);
    }

    /**
     * Handige “escape hatch” voor custom debug lijnen in EXACT dezelfde feature log files
     * (PlayerPawn / Movement / ...), zonder logs te versnipperen.
     */
    default void logLine(String sessionId, Level level, String line) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        if (line == null) {
            return;
        }
        Level outLevel = (level == null) ? Level.INFO : level;

        for (Logger logger : getTargetLoggers(sessionId)) {
            logger.log(outLevel, line);
        }
    }

    default void logEnrichFeatureValues(String sessionId, String modelKey, String method, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(featureComponent, "featureComponent");

        if (frames == null || frames.isEmpty()) {
            return;
        }

        DebugConfig cfg = DebugConfig.get();
        if (cfg.debugFeatureSet.isEmpty()) {
            return;
        }

        List<Integer> sampleIdx = buildSampleIndices(frames.size());

        for (String featureId : featureComponent.getFeatureIds()) {
            if (!cfg.debugFeatureSet.contains(featureId)) {
                continue;
            }

            for (Integer idx : sampleIdx) {
                GameStateDto current = frames.get(idx.intValue());
                Float value;
                try {
                    value = featureComponent.resolveCsvWriterFeatureValue(modelKey, sessionId, featureId, frames, current);
                } catch (RuntimeException ex) {
                    String line = method
                            + " feature=" + featureId
                            + " value=<EX>"
                            + " component=" + featureComponent.getClass().getSimpleName()
                            + " ex=" + ex.getClass().getSimpleName()
                            + " msg=" + safeMsg(ex.getMessage())
                            + buildFrameExtra(current);

                    for (Logger logger : getTargetLoggers(sessionId)) {
                        logger.log(Level.WARNING, line);
                    }
                    continue;
                }

                logFeature(sessionId, featureId, method, value, current);
            }
        }
    }

    default List<Integer> buildSampleIndices(int size) {
        List<Integer> idx = new ArrayList<Integer>(3);
        if (size <= 0) {
            return idx;
        }
        idx.add(Integer.valueOf(0));
        if (size >= 2) {
            idx.add(Integer.valueOf(size / 2));
        }
        if (size >= 3) {
            idx.add(Integer.valueOf(size - 1));
        }

        List<Integer> dedup = new ArrayList<Integer>(3);
        Set<Integer> seen = new HashSet<Integer>();
        for (Integer i : idx) {
            if (seen.add(i)) {
                dedup.add(i);
            }
        }
        return dedup;
    }

    default void logFeature(String sessionId, String featureId, String method, Float value, GameStateDto frameOrNull) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(featureId, "featureId");
        Objects.requireNonNull(method, "method");

        DebugConfig cfg = DebugConfig.get();
        if (!cfg.debugFeatureSet.contains(featureId)) {
            return;
        }

        String key = sessionId + "::" + featureId + "::" + method;
        FeatureLogState st = STATE.computeIfAbsent(key, k -> new FeatureLogState());

        long seq = st.counter.incrementAndGet();
        if (seq % cfg.everyN != 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = st.lastWriteMs.get();
        if (cfg.minIntervalMs > 0L && (now - last) < cfg.minIntervalMs) {
            return;
        }

        ValueInfo vi = ValueInfo.from(value);

        if (cfg.onlyOnChange) {
            Double prev = st.lastValue;
            if (prev != null) {
                if (isFinite(prev.doubleValue()) && isFinite(vi.numericValue)) {
                    if (Math.abs(prev.doubleValue() - vi.numericValue) < cfg.eps) {
                        return;
                    }
                } else {
                    String prevRendered = st.lastRendered;
                    if (prevRendered != null && prevRendered.equals(vi.renderedValue)) {
                        return;
                    }
                }
            }
        }

        // update state
        st.lastValue = isFinite(vi.numericValue) ? Double.valueOf(vi.numericValue) : null;
        st.lastRendered = vi.renderedValue;
        st.lastWriteMs.set(now);

        long lineNo = st.lines.incrementAndGet();
        if (lineNo > cfg.maxLines) {
            return;
        }

        String extra = buildFrameExtra(frameOrNull);

        String line = method
                + " feature=" + featureId
                + " value=" + vi.renderedValue
                + extra;

        for (Logger logger : getTargetLoggers(sessionId)) {
            logger.info(line);
        }
    }

    /**
     * Bepaalt naar welke loggers we schrijven.
     * -> ENKEL getLogFiles() (geen per-feature fallback)
     */
    default List<Logger> getTargetLoggers(String sessionId) {
        Set<String> ids = getLogFiles();
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> targets = new LinkedHashSet<String>(ids);

        List<Logger> out = new ArrayList<Logger>(targets.size());
        for (String id : targets) {
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            out.add(SessionRollingLogger.get(sessionId, SessionLogPaths.featureLog(id)));
        }
        return out;
    }

    default void logEnrich(String sessionId, String method, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(method, "method");

        int size = (frames == null) ? 0 : frames.size();
        if (size == 0) {
            return;
        }

        DebugConfig cfg = DebugConfig.get();
        // This is extremely chatty at runtime (per component, per tick). Only log when debugging is enabled.
        if (cfg.debugFeatureSet.isEmpty()) {
            return;
        }

        String loggerName = this.getClass().getSimpleName();
        String key = sessionId + "::" + loggerName + "::" + method;

        FeatureLogState st = STATE.computeIfAbsent(key, k -> new FeatureLogState());

        long seq = st.counter.incrementAndGet();
        if (seq % cfg.everyN != 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = st.lastWriteMs.get();
        if (cfg.minIntervalMs > 0L && (now - last) < cfg.minIntervalMs) {
            return;
        }
        st.lastWriteMs.set(now);

        long lineNo = st.lines.incrementAndGet();
        if (lineNo > cfg.maxLines) {
            return;
        }

        GameStateDto first = frames.get(0);
        GameStateDto lastFrame = frames.get(size - 1);

        String extraFirst = formatFrameBasics(first);
        String extraLast = formatFrameBasics(lastFrame);

        String componentName = (featureComponent == null) ? "null" : featureComponent.getClass().getSimpleName();

        String line = method
                + " logger=" + loggerName
                + " component=" + componentName
                + " frames=" + size
                + " first=" + extraFirst
                + " last=" + extraLast;

        for (Logger logger : getTargetLoggers(sessionId)) {
            logger.info(line);
        }
    }

    default String formatFrameBasics(GameStateDto f) {
        if (f == null) {
            return "null";
        }

        String loc = "(n/a)";
        if (f.playerPawn != null && f.playerPawn.location != null) {
            loc = "(" + f.playerPawn.location.x + "," + f.playerPawn.location.y + "," + f.playerPawn.location.z + ")";
        }

        String ts = "";
        try {
            ts = " ts=" + f.timestampMillis;
        } catch (Exception ignored) {
            // no-op
        }

        String id = " id=" + System.identityHashCode(f);

        return "loc=" + loc + ts + id;
    }

    default String buildFrameExtra(GameStateDto f) {
        if (f == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(180);
        sb.append(" | id=").append(System.identityHashCode(f));

        try {
            sb.append(" ts=").append(f.timestampMillis);
        } catch (Exception ignored) {
            // no-op
        }

        if (f.playerPawn != null && f.playerPawn.location != null) {
            sb.append(" loc=(")
                    .append(f.playerPawn.location.x).append(",")
                    .append(f.playerPawn.location.y).append(",")
                    .append(f.playerPawn.location.z).append(")");
            try {
                sb.append(" locN=(")
                        .append(format6(f.playerPawn.location.x_norm)).append(",")
                        .append(format6(f.playerPawn.location.y_norm)).append(",")
                        .append(format6(f.playerPawn.location.z_norm)).append(")");
            } catch (Exception ignored) {
                // no-op
            }
        }

        if (f.playerPawn != null && f.playerPawn.viewRotation != null) {
            sb.append(" vr=(")
                    .append((int) f.playerPawn.viewRotation.x).append(",")
                    .append((int) f.playerPawn.viewRotation.y).append(")");
            try {
                sb.append(" vrSC=(")
                        .append(format6(f.playerPawn.viewRotation.x_sin)).append(",")
                        .append(format6(f.playerPawn.viewRotation.x_cos)).append(")");
            } catch (Exception ignored) {
                // no-op
            }
            try {
                sb.append(" vrYn=").append(format6(f.playerPawn.viewRotation.y_norm));
            } catch (Exception ignored) {
                // no-op
            }
        }

        if (f.playerPawn != null && f.playerPawn.collisions != null) {
            sb.append(" col=(")
                    .append(format6(f.playerPawn.collisions.fwdCollision_norm)).append(",")
                    .append(format6(f.playerPawn.collisions.backCollision_norm)).append(",")
                    .append(format6(f.playerPawn.collisions.leftCollision_norm)).append(",")
                    .append(format6(f.playerPawn.collisions.rightCollision_norm)).append(")");
        }

        return sb.toString();
    }

    default String format6(double v) {
        if (!isFinite(v)) {
            return "NaN";
        }
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    static String safeMsg(String msg) {
        if (msg == null) {
            return "";
        }
        String trimmed = msg.trim();
        if (trimmed.length() > 240) {
            return trimmed.substring(0, 240) + "...";
        }
        return trimmed;
    }

    final class FeatureLogState {
        private final AtomicLong counter = new AtomicLong(0L);
        private final AtomicLong lastWriteMs = new AtomicLong(0L);
        private final AtomicLong lines = new AtomicLong(0L);
        private volatile Double lastValue = null;
        private volatile String lastRendered = null;
    }

    /**
     * Gecachte snapshot van debug properties, om overhead in realtime te beperken.
     */
    final class DebugConfig {
        private static final AtomicLong LAST_LOAD_MS = new AtomicLong(0L);
        private static volatile DebugConfig CACHED = new DebugConfig(
                new HashSet<String>(),
                1,
                0L,
                false,
                0.0d,
                1
        );

        private static final long TTL_MS = 1500L;

        private final Set<String> debugFeatureSet;
        private final int everyN;
        private final long minIntervalMs;
        private final boolean onlyOnChange;
        private final double eps;
        private final int maxLines;

        private DebugConfig(Set<String> debugFeatureSet,
                            int everyN,
                            long minIntervalMs,
                            boolean onlyOnChange,
                            double eps,
                            int maxLines) {
            this.debugFeatureSet = debugFeatureSet;
            this.everyN = everyN;
            this.minIntervalMs = minIntervalMs;
            this.onlyOnChange = onlyOnChange;
            this.eps = eps;
            this.maxLines = maxLines;
        }

        static DebugConfig get() {
            long now = System.currentTimeMillis();
            long last = LAST_LOAD_MS.get();
            if ((now - last) < TTL_MS) {
                return CACHED;
            }
            if (!LAST_LOAD_MS.compareAndSet(last, now)) {
                return CACHED;
            }

            aiplay.config.global.DebugConfig dbgCfg = GlobalConfigRepository.shared().debug();
            Set<String> debugSet = new HashSet<String>();
            if (dbgCfg.features() != null) {
                for (String s : dbgCfg.features()) {
                    if (s == null) {
                        continue;
                    }
                    String t = s.trim();
                    if (!t.isEmpty()) {
                        debugSet.add(t);
                    }
                }
            }

            int everyN = Math.max(1, dbgCfg.logEveryN());
            long minIntervalMs = Math.max(0L, dbgCfg.logMinIntervalMs());
            boolean onlyOnChange = dbgCfg.logOnlyOnChange();
            double eps = dbgCfg.logChangeEpsilon();
            int maxLines = Math.max(1, dbgCfg.logMaxLinesPerFeature());

            CACHED = new DebugConfig(debugSet, everyN, minIntervalMs, onlyOnChange, eps, maxLines);
            return CACHED;
        }
    }

    final class ValueInfo {
        private final double numericValue;
        private final String renderedValue;

        private ValueInfo(double numericValue, String renderedValue) {
            this.numericValue = numericValue;
            this.renderedValue = renderedValue;
        }

        static ValueInfo from(Float value) {
            if (value == null) {
                return new ValueInfo(Double.NaN, "null");
            }
            float f = value.floatValue();
            if (Float.isNaN(f)) {
                return new ValueInfo(Double.NaN, "NaN");
            }
            if (Float.isInfinite(f)) {
                if (f > 0) {
                    return new ValueInfo(Double.POSITIVE_INFINITY, "Inf");
                }
                return new ValueInfo(Double.NEGATIVE_INFINITY, "-Inf");
            }
            double d = (double) f;
            String s = String.format(java.util.Locale.ROOT, "%.6f", d);
            return new ValueInfo(d, s);
        }
    }
}
