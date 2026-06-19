package aiplay.scanners.executors.rlpawn;

import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.scanners.executors.PlayExecutorComponent;
import aiplay.scanners.executors.PlayExecutorLogger;

/**
 * Joint VR+shooting executor component — de enige low-level policy executor
 * in productie. Publiceert movement, view-turn en shooting intents vanuit
 * één ONNX run.
 */
@PlayExecutorComponent(priority = 5)
public final class RLPawnExecutorComponent implements IPlayExecutor {

    public static final String EXECUTOR_KEY = "vr-shoot-executor";

    private final RLPawnExecutorAiController aiController = new RLPawnExecutorAiController();
    private final RLPawnExecutorLogger logger = new RLPawnExecutorLogger();

    @Override
    public boolean isActive() {
        return ModelRoleRegistry.shared().isRoleActive(ModelRole.PAWN_POLICY);
    }

    @Override
    public String getExecutorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public int getPredictionFps() {
        return ModelRoleRegistry.shared().resolveOptional(ModelRole.PAWN_POLICY)
            .map(cfg -> {
                int baseFps = cfg.runtime().predictionFps();
                double gameSpeed = aiplay.runtime.config.Ut99InstallResolver.getEffectiveGameSpeed();
                return Math.max(1, (int) Math.round(baseFps * gameSpeed));
            })
            .orElse(1);
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
