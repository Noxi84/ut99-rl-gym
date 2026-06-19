package aiplay.logging;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.LoggingConfig;
import aiplay.runtime.config.SessionPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public final class SessionRollingLogger {

    private static final ConcurrentHashMap<String, Logger> LOGGER_CACHE = new ConcurrentHashMap<String, Logger>();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private SessionRollingLogger() {
        throw new AssertionError("Utility class");
    }

    /**
     * Create/get a rolling file logger.
     *
     * @param sessionId session id (folder name)
     * @param relativeLogPath relative path under session logs folder (e.g. "movement/movement.log" or "features/locationX/onRealTimeResolve.log")
     */
    public static Logger get(String sessionId, String relativeLogPath) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(relativeLogPath, "relativeLogPath");

        String cacheKey = sessionId + "::" + relativeLogPath;
        Logger existing = LOGGER_CACHE.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        Logger created = createLogger(sessionId, relativeLogPath);
        Logger raced = LOGGER_CACHE.putIfAbsent(cacheKey, created);
        return (raced != null) ? raced : created;
    }

    private static Logger createLogger(String sessionId, String relativeLogPath) {
        try {
            LoggingConfig logCfg = GlobalConfigRepository.shared().logging();
            boolean enabled = logCfg.enabled();
            String levelStr = logCfg.level();

            Level level = parseLevel(levelStr, Level.INFO);

            Logger logger = Logger.getLogger("ut99." + sessionId + "." + sanitize(relativeLogPath));
            logger.setUseParentHandlers(false);
            logger.setLevel(enabled ? level : Level.OFF);

            if (!enabled) {
                return logger;
            }

            Path sessionDir = Paths.get(SessionPaths.getSessionDir());
            Path logsDir = sessionDir.resolve("logs").resolve(sessionId);
            Path logFile = logsDir.resolve(relativeLogPath);

            // ensure directories exist
            Files.createDirectories(logFile.getParent());

            int maxBytes = logCfg.maxBytes();
            int maxFiles = logCfg.maxFiles();
            boolean append = true;

            // java.util.logging FileHandler rotation: pattern + limit + count
            // It will create files like movement.log.0, movement.log.1, ...
            // Pattern needs %g to enable rotation index.
            String pattern = toJulPattern(logFile);

            FileHandler fh = new FileHandler(pattern, maxBytes, maxFiles, append);
            fh.setLevel(level);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new LineFormatter());

            // avoid duplicate handlers if createLogger called twice (rare, but safe)
            Handler[] handlers = logger.getHandlers();
            for (int i = 0; i < handlers.length; i++) {
                logger.removeHandler(handlers[i]);
            }
            logger.addHandler(fh);

            return logger;

        } catch (IOException e) {
            // last resort: console handler (still no System.out)
            Logger fallback = Logger.getLogger("ut99.fallback." + sessionId + "." + sanitize(relativeLogPath));
            fallback.setUseParentHandlers(false);
            fallback.setLevel(Level.WARNING);
            ConsoleHandler ch = new ConsoleHandler();
            ch.setLevel(Level.WARNING);
            ch.setFormatter(new LineFormatter());
            fallback.addHandler(ch);
            fallback.log(Level.WARNING, "Failed to create rolling logger for {0}: {1}",
                    new Object[]{relativeLogPath, e.getMessage()});
            return fallback;
        }
    }

    private static String toJulPattern(Path logFile) {
        // ensure %g exists for rotation; keep filename stable
        String p = logFile.toAbsolutePath().toString();

        // If user configured plain ".../movement.log", we rotate to ".../movement.log.%g"
        if (!p.contains("%g")) {
            p = p + ".%g";
        }
        return p;
    }

    private static String sanitize(String s) {
        return s.replace('/', '.').replace('\\', '.').replace(':', '_');
    }

    private static Level parseLevel(String s, Level def) {
        if (s == null) return def;
        String t = s.trim().toUpperCase();
        if ("SEVERE".equals(t)) return Level.SEVERE;
        if ("WARNING".equals(t) || "WARN".equals(t)) return Level.WARNING;
        if ("INFO".equals(t)) return Level.INFO;
        if ("FINE".equals(t) || "DEBUG".equals(t)) return Level.FINE;
        if ("FINER".equals(t)) return Level.FINER;
        if ("FINEST".equals(t) || "TRACE".equals(t)) return Level.FINEST;
        return def;
    }

    /**
     * Formatter: timestamp + level + message (single line).
     */
    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String ts = TS.format(Instant.ofEpochMilli(record.getMillis()));
            String lvl = record.getLevel().getName();
            String msg = formatMessage(record);
            // keep it 1-line (replace newlines)
            msg = msg.replace("\r", " ").replace("\n", " ");
            return MessageFormat.format("{0} [{1}] {2}\n", new Object[]{ts, lvl, msg});
        }
    }
}
