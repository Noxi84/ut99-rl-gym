package aiplay.runtime.role;

import aiplay.config.ModelConfigRepository;
import aiplay.config.PropertyReaderUtils;
import aiplay.config.global.BotConfig;
import aiplay.config.model.ModelConfig;
import aiplay.runtime.identity.IdentityLookups;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Central registry that binds architectural {@link ModelRole}s to concrete
 * model keys. All runtime consumers resolve models through this registry
 * instead of hardcoding model key strings.
 *
 * <p>Bindings are loaded from {@code /config/roles.json} with the structure:
 * <pre>
 * { "bindings": { "pawn_policy": "rl_pawn" } }
 * </pre>
 *
 * <p>Thread-safe: the registry is immutable after construction.</p>
 */
public final class ModelRoleRegistry {

    private static final Logger LOG = Logger.getLogger(ModelRoleRegistry.class.getName());
    private static final AtomicReference<ModelRoleRegistry> SHARED = new AtomicReference<>();

    private final Map<ModelRole, String> bindings; // role -> model key

    private ModelRoleRegistry() {
        this.bindings = loadBindings();
    }

    public static ModelRoleRegistry shared() {
        ModelRoleRegistry current = SHARED.get();
        if (current != null) {
            return current;
        }
        ModelRoleRegistry fresh = new ModelRoleRegistry();
        SHARED.compareAndSet(null, fresh);
        return SHARED.get();
    }

    /**
     * Resolves a required role to its ModelConfig. Throws if the role
     * is not bound or the model does not exist.
     */
    public ModelConfig resolve(ModelRole role) {
        String modelKey = bindings.get(role);
        if (modelKey == null) {
            throw new IllegalStateException(
                "Model role " + role + " is not bound. Check config/roles.json");
        }
        return ModelConfigRepository.shared().get(modelKey);
    }

    /**
     * Resolves an optional role. Returns empty if not bound.
     */
    public Optional<ModelConfig> resolveOptional(ModelRole role) {
        String modelKey = bindings.get(role);
        if (modelKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ModelConfigRepository.shared().get(modelKey));
        } catch (Exception e) {
            LOG.warning("Optional role " + role + " bound to '" + modelKey
                + "' but model config failed to load: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the model key for a role, or null if not bound.
     */
    public String getModelKey(ModelRole role) {
        return bindings.get(role);
    }

    public boolean isRoleBound(ModelRole role) {
        return bindings.containsKey(role);
    }

    /**
     * Returns true when a role is bound and the model is enabled for the current bot.
     *
     * <p>Uses {@link IdentityLookups#currentBotConfig()} to determine the current bot.
     * When no bot context is set (e.g., startup max-FPS calculation), returns true
     * if the role is bound (conservative: assumes active for tick timing).
     */
    public boolean isRoleActive(ModelRole role) {
        String modelKey = bindings.get(role);
        if (modelKey == null) return false;
        // Verify model config exists
        if (resolveOptional(role).isEmpty()) return false;
        BotConfig bot = IdentityLookups.currentBotConfig();
        if (bot == null) {
            // No bot context (startup): role is bound → assume active
            return true;
        }
        return bot.isModelEnabled(modelKey);
    }

    /**
     * Returns true when a role is bound and the model is enabled for the given bot.
     */
    public boolean isRoleActiveForBot(ModelRole role, BotConfig bot) {
        String modelKey = bindings.get(role);
        if (modelKey == null) return false;
        if (resolveOptional(role).isEmpty()) return false;
        return bot.isModelEnabled(modelKey);
    }

    /**
     * Validates that bound roles resolve to existing model configs.
     */
    public void validate() {
        ModelConfigRepository repo = ModelConfigRepository.shared();
        List<String> errors = new ArrayList<>();

        for (ModelRole role : ModelRole.values()) {
            String modelKey = bindings.get(role);
            if (modelKey == null) {
                continue;
            }
            try {
                repo.get(modelKey);
            } catch (Exception e) {
                errors.add("Role " + role + " -> '" + modelKey + "' model config not found: " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                "Model role validation failed:\n  " + String.join("\n  ", errors));
        }
    }

    /**
     * Logs the current role bindings at startup.
     */
    public void logSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ Model Role Registry ─────────────────────┐\n");

        for (ModelRole role : ModelRole.values()) {
            String modelKey = bindings.get(role);
            String status = modelKey == null ? "not bound" : modelKey + " (bound, per-bot enabled)";
            sb.append("│  ").append(String.format("%-20s", role.configKey()))
              .append(" -> ").append(status).append("\n");
        }

        sb.append("└───────────────────────────────────────────┘");
        LOG.info(sb.toString());
    }

    // ─────────────────────── loading ───────────────────────

    private static Map<ModelRole, String> loadBindings() {
        Map<ModelRole, String> map = new EnumMap<>(ModelRole.class);

        JsonNode rolesNode = PropertyReaderUtils.getSubtree("/roles");
        if (rolesNode != null) {
            JsonNode bindingsNode = rolesNode.get("bindings");
            if (bindingsNode != null && bindingsNode.isObject()) {
                for (ModelRole role : ModelRole.values()) {
                    JsonNode val = bindingsNode.get(role.configKey());
                    if (val != null && val.isTextual() && !val.asText().isBlank()) {
                        map.put(role, val.asText());
                    }
                }
            }
        }

        return Collections.unmodifiableMap(map);
    }
}
