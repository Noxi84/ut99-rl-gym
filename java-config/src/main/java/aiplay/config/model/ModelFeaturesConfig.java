package aiplay.config.model;

import java.util.List;
import java.util.stream.Collectors;

public record ModelFeaturesConfig(
    List<String> legacyFeatures,     // legacy flat list — used when featureGroups is null/empty
    List<FeatureGroup> featureGroups, // temporal groups — null if not configured
    List<String> targetFeatures,
    List<String> auxTargetFeatures   // Phase 2: aux columns in CSV (e.g. target_index, target_index_confidence)
                                     // Not part of model output_size; consumed by aux losses in training.
) {
    public boolean hasTemporalGroups() {
        return featureGroups != null && !featureGroups.isEmpty();
    }

    /** Total sequence window = max(firstFrames + lastFrames) across all groups. */
    public int totalWindow() {
        if (!hasTemporalGroups()) return 0;
        return featureGroups.stream()
            .mapToInt(g -> g.firstFrames() + g.lastFrames())
            .max()
            .orElse(1);
    }

    /** All input features in order (flattened from groups, or the legacy flat list). */
    public List<String> inputFeatures() {
        if (!hasTemporalGroups()) return legacyFeatures;
        return featureGroups.stream()
            .flatMap(g -> g.features().stream())
            .collect(Collectors.toList());
    }
}
