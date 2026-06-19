package aiplay.runtime;

import aiplay.runtime.role.ModelRoleRegistry;

/**
 * JVM-wide shared services. Created once by the launcher, passed to
 * BotRuntimeFactory for each instance.
 *
 * <p>Global singletons (GlobalConfigRepository, ModelConfigRepository,
 * FeatureContractRepository, SessionPaths) remain accessible via their
 * own .shared() methods. This class holds computed values that should
 * not be recomputed per instance.</p>
 */
public final class SharedRuntimeServices {

    private final int maxPredictionFps;
    private final ModelRoleRegistry modelRoleRegistry;

    public SharedRuntimeServices(int maxPredictionFps, ModelRoleRegistry modelRoleRegistry) {
        if (maxPredictionFps <= 0) {
            throw new IllegalArgumentException("maxPredictionFps must be > 0, got " + maxPredictionFps);
        }
        this.maxPredictionFps = maxPredictionFps;
        this.modelRoleRegistry = modelRoleRegistry;
    }

    public int getMaxPredictionFps() {
        return maxPredictionFps;
    }

    public ModelRoleRegistry getModelRoleRegistry() {
        return modelRoleRegistry;
    }
}
