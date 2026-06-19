package aiplay.scanners.executors.rlpawn;

import aiplay.scanners.executors.PlayExecutorLogger;

import java.util.Set;

/**
 * Logger voor de joint VR+shooting executor — volgt het patroon van
 * {@link aiplay.scanners.executors.shooting.ShootingExecutorLogger}.
 */
public final class RLPawnExecutorLogger implements PlayExecutorLogger {

    public static final String LOG_VR_SHOOTING = "RLPawn";

    private final Set<String> logFiles = Set.of(LOG_VR_SHOOTING);

    @Override
    public Set<String> getLogFiles() {
        return logFiles;
    }

    @Override
    public String resolveRelativeLogPath(String logKey) {
        return featureLogPath(LOG_VR_SHOOTING);
    }
}
