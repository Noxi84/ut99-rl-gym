package aiplay.scanners.executors;

import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;

import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central logging router for an executor.
 *
 * Rules:
 * - Controllers/services NEVER build paths directly.
 * - They request a Logger via executorLogger.getLogger(sessionId, logKey).
 * - The implementation maps logKey -> "same file structure as before".
 *
 * Important:
 * - If you pass a featureId as logKey, you can route executor logs into the same feature log file.
 */
public interface PlayExecutorLogger {

    /**
     * Informational: logical keys this executor uses (optional).
     */
    Set<String> getLogFiles();

    /**
     * Resolve a logical logKey to a relative path under sessions/<sid>/logs/.
     * Typically you return SessionLogPaths.featureLog("Movement") etc.
     */
    String resolveRelativeLogPath(String logKey);

    /**
     * Get a rolling JUL logger routed to the proper rolling file.
     */
    default Logger getLogger(String sessionId, String logKey) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(logKey, "logKey");

        String relative = resolveRelativeLogPath(logKey);
        Logger logger = SessionRollingLogger.get(sessionId, relative);

        if (logger.getLevel() == null) {
            logger.setLevel(Level.INFO);
        }

        return logger;
    }

    /**
     * Helper for feature logs folder (same structure as before).
     */
    default String featureLogPath(String featureId) {
        return SessionLogPaths.featureLog(featureId);
    }
}
