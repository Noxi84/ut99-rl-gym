package aiplay.runtime;

/**
 * Explicit lifecycle states for a single bot runtime instance.
 */
public enum RuntimeLifecycle {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
}
