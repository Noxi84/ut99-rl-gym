package aiplay.rl.champion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Metadata loaded from a champion's {@code snapshot.json}, mirroring the
 * Python side {@code train.common.champion_store.SnapshotMeta} dataclass.
 *
 * <p>Only fields needed at Java load-time are exposed. Match history and
 * tags stay Python-tooling concerns, but the promotion decision is needed
 * so {@code <mk>/newest} can ignore bootstrap/manual snapshots.
 */
public record SnapshotMeta(
    int counter,
    String modelKey,
    String featureFingerprint,
    String archFingerprint,
    String rewardsFingerprint,
    String promotionDecision
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SnapshotMeta read(Path snapshotJson) {
        try {
            JsonNode root = MAPPER.readTree(snapshotJson.toFile());
            return new SnapshotMeta(
                root.get("counter").asInt(),
                root.get("model_key").asText(),
                root.get("feature_fingerprint").asText(),
                root.get("arch_fingerprint").asText(),
                root.get("rewards_fingerprint").asText(),
                root.path("kpi_at_snapshot").path("decision").asText("")
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read snapshot metadata: " + snapshotJson, e);
        } catch (NullPointerException e) {
            throw new IllegalStateException(
                "snapshot.json missing required fields: " + snapshotJson, e);
        }
    }

    public boolean isPromoted() {
        return "PROMOTE".equalsIgnoreCase(promotionDecision);
    }
}
