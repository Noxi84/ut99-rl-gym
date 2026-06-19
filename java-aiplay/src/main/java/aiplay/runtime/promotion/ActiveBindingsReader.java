package aiplay.runtime.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads {@code active_bindings.json} from the sessions directory.
 * Provides startup observability: which model was promoted for each role,
 * when, and whether rollback is available.
 */
public final class ActiveBindingsReader {

    private static final Logger LOG = Logger.getLogger(ActiveBindingsReader.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActiveBindingsReader() {}

    /**
     * Reads active bindings from the sessions dir. Returns empty map if
     * the file doesn't exist (first run, or promotion not yet enabled).
     */
    public static Map<String, PromotionRecord> read() {
        try {
            String sessionsDir = aiplay.runtime.config.SessionPaths.getSessionDir();
            File f = new File(sessionsDir, "active_bindings.json");
            if (!f.exists()) {
                return Collections.emptyMap();
            }
            JsonNode root = MAPPER.readTree(f);
            Map<String, PromotionRecord> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.put(entry.getKey(), parseRecord(entry.getKey(), entry.getValue()));
            }
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to read active_bindings.json: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Logs the active bindings at startup.
     */
    public static void logSnapshot() {
        Map<String, PromotionRecord> bindings = read();
        if (bindings.isEmpty()) {
            LOG.info("Active bindings: none (promotion not yet used or first run)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ Active Model Bindings ───────────────────┐\n");
        for (var entry : bindings.entrySet()) {
            PromotionRecord r = entry.getValue();
            sb.append("│  ").append(String.format("%-20s", entry.getKey()))
              .append(" -> ").append(r.modelKey())
              .append(" step=").append(r.step())
              .append(" loss=").append(String.format("%.4f", r.valLoss()))
              .append(r.hasRollbackTarget() ? " [rollback available]" : "")
              .append("\n");
        }
        sb.append("└───────────────────────────────────────────┘");
        LOG.info(sb.toString());
    }

    private static PromotionRecord parseRecord(String role, JsonNode node) {
        PromotionRecord previous = null;
        if (node.has("previous") && !node.get("previous").isNull()) {
            previous = parseRecord(role, node.get("previous"));
        }
        return new PromotionRecord(
            node.path("model_key").asText(""),
            role,
            node.path("step").asInt(0),
            node.path("val_loss").asDouble(0.0),
            parseStatus(node.path("status").asText("unknown")),
            node.path("promoted_at").asDouble(0.0),
            previous
        );
    }

    private static CandidateStatus parseStatus(String s) {
        try {
            return CandidateStatus.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return CandidateStatus.BUILT;
        }
    }
}
