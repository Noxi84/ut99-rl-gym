package aiplay.logging;

/**
 * Central place for relative log file paths under: sessions/<sessionId>/logs/
 */
public final class SessionLogPaths {

    private SessionLogPaths() {
        throw new AssertionError("Utility class");
    }

    /**
     * One rolling log per feature.
     * Example file: logs/features/bWasForward/bWasForward.log.0
     */
    public static String featureLog(String featureId) {
        String f = sanitize(featureId);
        return "features/" + f + "/" + f + ".log";
    }

    /**
     * Shared parser/frames pipeline log: ParserLauncher + FramesFacade + WriterFacade, etc.
     */
    public static String framesLog() {
        return "frames/frames.log";
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('/', '_').replace('\\', '_').replace(':', '_');
    }
}
