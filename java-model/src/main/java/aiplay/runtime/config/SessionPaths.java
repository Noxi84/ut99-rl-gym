package aiplay.runtime.config;

import aiplay.config.global.GlobalConfigRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves session-related paths with env/system property overrides.
 *
 * Priority for sessions dir:
 * 1) -DUT99_SESSIONS_DIR
 * 2) UT99_SESSIONS_DIR env var
 * 3) GlobalConfigRepository files.sessionsDir
 */
public final class SessionPaths {

    private static final String SYS_SESSIONS_DIR = "UT99_SESSIONS_DIR";

    private SessionPaths() {}

    public static String getSessionsBaseDir() {
        String sys = System.getProperty(SYS_SESSIONS_DIR);
        if (sys != null && !sys.isBlank()) return normalizeDir(sys.trim());

        String env = System.getenv(SYS_SESSIONS_DIR);
        if (env != null && !env.isBlank()) return normalizeDir(env.trim());

        String configured = GlobalConfigRepository.shared().files().sessionsDir();
        if (configured != null && !configured.isBlank()) return normalizeDir(configured.trim());

        throw new IllegalStateException(
            "Sessions dir not configured. Set UT99_SESSIONS_DIR or /files/sessions_dir in config");
    }

    public static String getSessionDir() {
        return getSessionsBaseDir();
    }

    public static String getModelTrainingDir() {
        return normalizeDir(Path.of(getSessionsBaseDir(), "models", "trainingmodel").toString());
    }

    /**
     * Centrale recordings folder (raw .rec.gz files). Geen sessions/<sid>
     * verdeling — alle CAPTURE-mode bots en converted human gameplay schrijven
     * hierin onder een submap-structuur (from-servers/<host>/<model> of
     * from-dev/<model>).
     */
    public static String getRecordingsDir() {
        String configured = GlobalConfigRepository.shared().files().recordingsDir();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                "Recordings dir not configured. Set /files/recordings_dir in config");
        }
        return normalizeDir(configured.trim());
    }

    public static void ensureSessionDirsExist() {
        String base = getSessionsBaseDir();
        try {
            Files.createDirectories(Paths.get(base, "models", "trainingmodel"));
            Files.createDirectories(Paths.get(base, "csv-training-data"));
            Files.createDirectories(Paths.get(base, "rl-replay-buffer"));
            Files.createDirectories(Paths.get(base, "json-recording-sessions"));
            Files.createDirectories(Paths.get(base, "logs"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session directories under: " + base, e);
        }
    }

    private static String normalizeDir(String dir) {
        if (dir == null) return "";
        String d = dir.trim();
        if (d.equals("~")) {
            d = System.getProperty("user.home");
        } else if (d.startsWith("~/")) {
            d = System.getProperty("user.home") + d.substring(1);
        }
        while (d.endsWith("/") && d.length() > 1) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }
}
