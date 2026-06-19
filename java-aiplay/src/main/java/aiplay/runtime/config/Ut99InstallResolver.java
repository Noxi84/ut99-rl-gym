package aiplay.runtime.config;

import aiplay.config.global.GlobalConfigRepository;

/**
 * Resolves the UT99 install root directory with env/system/config overrides.
 *
 * Priority:
 * 1) -DUT99_INSTALL_ROOT
 * 2) UT99_INSTALL_ROOT env var
 * 3) Ut99ServerConfig.installRoot()
 */
public final class Ut99InstallResolver {

    private Ut99InstallResolver() {}

    public static String resolve() {
        String sys = System.getProperty("UT99_INSTALL_ROOT");
        if (sys != null && !sys.isBlank()) return normalizeDir(sys.trim());

        String env = System.getenv("UT99_INSTALL_ROOT");
        if (env != null && !env.isBlank()) return normalizeDir(env.trim());

        return normalizeDir(GlobalConfigRepository.shared().server().installRoot());
    }

    public static double getEffectiveGameSpeed() {
        String env = System.getenv("UT99_GAME_SPEED");
        if (env == null || env.isBlank()) {
            throw new IllegalStateException("UT99_GAME_SPEED env var is not set");
        }
        return Double.parseDouble(env.trim());
    }

    private static String normalizeDir(String dir) {
        if (dir == null) return "";
        String d = dir.trim();
        if (d.equals("~")) d = System.getProperty("user.home");
        else if (d.startsWith("~/")) d = System.getProperty("user.home") + d.substring(1);
        while (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        return d;
    }
}
