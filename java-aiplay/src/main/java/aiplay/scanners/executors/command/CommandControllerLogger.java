package aiplay.scanners.executors.command;

import aiplay.scanners.executors.PlayExecutorLogger;

import java.util.Set;

public class CommandControllerLogger implements PlayExecutorLogger {

    public static final String LOG_COMMAND = "CommandController";

    @Override
    public Set<String> getLogFiles() {
        return Set.of(LOG_COMMAND);
    }

    @Override
    public String resolveRelativeLogPath(String logKey) {
        if (logKey == null || !LOG_COMMAND.equals(logKey)) {
            return featureLogPath(logKey);
        }
        return featureLogPath(LOG_COMMAND);
    }
}
