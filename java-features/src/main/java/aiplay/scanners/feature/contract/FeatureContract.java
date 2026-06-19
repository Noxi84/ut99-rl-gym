package aiplay.scanners.feature.contract;

import aiplay.config.model.FeatureGroup;
import aiplay.config.model.ModelConfig;

import java.util.List;
import java.util.Map;

/**
 * De volledig gevalideerde contractdefinitie van één model.
 *
 * <p>Bevat de inputvolgorde, targetvolgorde, temporale groepen en feature-ownership
 * zoals afgeleid uit de modelconfig en TrainingFeatureService. Dit contract is de
 * gedeelde waarheid voor CSV-build, training en runtime inference.
 *
 * <p>Aangemaakt door {@link FeatureContractRepository} na succesvolle validatie.
 */
public record FeatureContract(
    String modelKey,
    List<String> inputFeatures,
    List<String> targetFeatures,
    List<FeatureGroup> featureGroups,
    int totalWindow,
    Map<String, String> featureOwnership
) {
    static FeatureContract from(String modelKey, ModelConfig cfg, Map<String, String> ownership) {
        var f = cfg.features();
        return new FeatureContract(
            modelKey,
            List.copyOf(f.inputFeatures()),
            List.copyOf(f.targetFeatures()),
            f.hasTemporalGroups() ? List.copyOf(f.featureGroups()) : List.of(),
            f.totalWindow(),
            Map.copyOf(ownership)
        );
    }

    /**
     * Past temporale masking in-place toe op een input-tensor van vorm [1][totalWindow][nFeatures].
     *
     * <p>Voor elke feature-groep worden frames in het gap
     * [{@code firstFrames}, {@code totalWindow - lastFrames}) op 0.0f gezet.
     * Dit is de centrale implementatie die zowel door runtime als door tests wordt gebruikt —
     * zodat runtime en training altijd dezelfde maskinglogica volgen.
     */
    public void applyTemporalMask(float[][][] input) {
        if (featureGroups.isEmpty()) {
            return;
        }
        int featureOffset = 0;
        for (FeatureGroup group : featureGroups) {
            int gapStart = group.firstFrames();
            int gapEnd = totalWindow - group.lastFrames();
            int groupSize = group.features().size();
            if (gapStart < gapEnd) {
                for (int t = gapStart; t < gapEnd; t++) {
                    for (int f = featureOffset; f < featureOffset + groupSize; f++) {
                        input[0][t][f] = 0.0f;
                    }
                }
            }
            featureOffset += groupSize;
        }
    }

    /**
     * Genereert een mensleesbare snapshot van het contract voor inspectie en logging.
     */
    public String toSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FeatureContract: ").append(modelKey).append(" ===\n");
        sb.append("totalWindow: ").append(totalWindow).append('\n');
        sb.append("inputFeatures (").append(inputFeatures.size()).append("):\n");

        int groupIdx = 0;
        int featureIdx = 0;
        for (FeatureGroup g : featureGroups) {
            sb.append("  group[").append(groupIdx).append("] firstFrames=")
              .append(g.firstFrames()).append(" lastFrames=").append(g.lastFrames())
              .append(" (").append(g.features().size()).append(" features):\n");
            for (String fid : g.features()) {
                String owner = featureOwnership.getOrDefault(fid, "?");
                sb.append("    [").append(featureIdx).append("] ").append(fid)
                  .append(" <- ").append(owner).append('\n');
                featureIdx++;
            }
            groupIdx++;
        }

        if (featureGroups.isEmpty()) {
            for (String fid : inputFeatures) {
                String owner = featureOwnership.getOrDefault(fid, "?");
                sb.append("  [").append(featureIdx).append("] ").append(fid)
                  .append(" <- ").append(owner).append('\n');
                featureIdx++;
            }
        }

        sb.append("targetFeatures (").append(targetFeatures.size()).append("):\n");
        for (int i = 0; i < targetFeatures.size(); i++) {
            sb.append("  [").append(i).append("] ").append(targetFeatures.get(i)).append('\n');
        }

        return sb.toString();
    }
}
