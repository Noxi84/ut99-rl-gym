package aiplay.runtime.role;

/**
 * Architectural roles that models can fulfill at runtime.
 * A role is a function, not an identity — it describes what a model
 * does in the system, not which specific model file it is.
 */
public enum ModelRole {

    /**
     * Joint movement + view-rotation + shooting policy (rl_pawn).
     * Single LSTM that emits movement intents (sin/cos/dodge/jump/duck/idle),
     * yaw/pitch deltas, bFire/bAltFire, with target_index as auxiliary head.
     */
    PAWN_POLICY(true);

    private final boolean required;

    ModelRole(boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    public String configKey() {
        return name().toLowerCase();
    }
}
