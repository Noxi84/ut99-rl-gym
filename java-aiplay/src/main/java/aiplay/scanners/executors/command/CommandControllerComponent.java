package aiplay.scanners.executors.command;

import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.scanners.executors.PlayExecutorComponent;
import aiplay.scanners.executors.PlayExecutorLogger;
import aiplay.config.global.GlobalConfigRepository;

/**
 * Command controller executor. Runs at controller rate (60Hz by default).
 * Reads movement + VR intents from buses and produces the final HTTP POST command.
 * Priority 3: runs after movement (2) and viewrotation (1) have published their intents.
 */
@PlayExecutorComponent(priority = 3)
public class CommandControllerComponent implements IPlayExecutor {

    private final CommandControllerAiController aiController = new CommandControllerAiController();
    private final CommandControllerLogger logger = new CommandControllerLogger();

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getExecutorKey() {
        return "command-controller";
    }

    @Override
    public int getPredictionFps() {
        int baseFps = GlobalConfigRepository.shared().commandController().general().controllerFps();
        double gameSpeed = aiplay.runtime.config.Ut99InstallResolver.getEffectiveGameSpeed();
        return Math.max(1, (int) Math.round(baseFps * gameSpeed));
    }

    @Override
    public PlayExecutorAiController getPlayExecutorAiController() {
        return aiController;
    }

    @Override
    public PlayExecutorLogger getPlayExecutorLogger() {
        return logger;
    }
}
