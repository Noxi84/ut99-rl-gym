package aiplay.runtime.config;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.Ut99ServerConfig;
import aiplay.instance.InstanceConfig;

/**
 * Resolves the NeuralNet webservice URL with env/system/instance overrides.
 *
 * Priority:
 * 1) InstanceConfig (per-bot override)
 * 2) -DUT99_NEURALNET_URL
 * 3) UT99_NEURALNET_URL env var
 * 4) Ut99ServerConfig.utNeuralnetServer()
 * 5) Default: http://127.0.0.1:<uweb_port>/utneuralnet/
 */
public final class NeuralNetEndpointResolver {

    private static final String SYS_NEURALNET_URL = "UT99_NEURALNET_URL";
    private static final String SYS_UWEB_LISTEN_PORT = "UT99_UWEB_LISTEN_PORT";

    private NeuralNetEndpointResolver() {}

    public static String resolve(InstanceConfig config) {
        if (config != null) return config.getNeuralNetUrl();
        return resolve();
    }

    public static String resolve() {
        String sys = System.getProperty(SYS_NEURALNET_URL);
        if (sys != null && !sys.isBlank()) return sys.trim();

        String env = System.getenv(SYS_NEURALNET_URL);
        if (env != null && !env.isBlank()) return env.trim();

        Ut99ServerConfig srv = GlobalConfigRepository.shared().server();
        String configured = srv.utNeuralnetServer();
        if (configured != null && !configured.isBlank()) {
            return overrideLocalUrlPort(configured.trim(), resolveUWebListenPort());
        }

        return "http://127.0.0.1:" + resolveUWebListenPort() + "/utneuralnet/";
    }

    public static int resolveUWebListenPort(InstanceConfig config) {
        if (config != null) return config.getUwebListenPort();
        return resolveUWebListenPort();
    }

    public static int resolveUWebListenPort() {
        String sys = System.getProperty(SYS_UWEB_LISTEN_PORT);
        if (sys != null && !sys.isBlank()) {
            try { return Math.max(1, Integer.parseInt(sys.trim())); } catch (Exception ignore) {}
        }
        String env = System.getenv(SYS_UWEB_LISTEN_PORT);
        if (env != null && !env.isBlank()) {
            try { return Math.max(1, Integer.parseInt(env.trim())); } catch (Exception ignore) {}
        }
        return Math.max(1, GlobalConfigRepository.shared().server().uwebListenPort());
    }

    public static int resolveServerPort(InstanceConfig config) {
        if (config != null) return config.getServerPort();
        return resolveServerPort();
    }

    public static int resolveServerPort() {
        String sys = System.getProperty("UT99_SERVER_PORT");
        if (sys != null && !sys.isBlank()) {
            try { return Math.max(1, Integer.parseInt(sys.trim())); } catch (Exception ignore) {}
        }
        String env = System.getenv("UT99_SERVER_PORT");
        if (env != null && !env.isBlank()) {
            try { return Math.max(1, Integer.parseInt(env.trim())); } catch (Exception ignore) {}
        }
        return Math.max(1, GlobalConfigRepository.shared().server().port());
    }

    private static String overrideLocalUrlPort(String url, int port) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isBlank()) return u;
        boolean isLocal = u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:")
            || u.startsWith("https://127.0.0.1:") || u.startsWith("https://localhost:");
        if (!isLocal) return u;
        return u.replaceAll("(?i)^(https?://(?:127\\.0\\.0\\.1|localhost)):(\\d+)(/.*)?$",
            "$1:" + Math.max(1, port) + "$3");
    }
}
