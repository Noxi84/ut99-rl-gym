package aiplay.scanners.feature.contract;

import aiplay.config.model.FeatureGroup;
import aiplay.config.model.ModelFeaturesConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valideert een {@link ModelFeaturesConfig} tegen de geregistreerde feature-IDs
 * en feature-ownership.
 *
 * <p>Alle validatiefouten worden verzameld en teruggegeven als lijst, zodat
 * de aanroeper alle problemen tegelijk kan rapporteren.
 */
public final class FeatureContractValidator {

    private FeatureContractValidator() {}

    /**
     * Valideert de features-config van één model.
     *
     * @param modelKey           het model-ID (alleen voor foutberichten)
     * @param features           de te valideren config
     * @param registeredFeatureIds alle feature-IDs die geregistreerd zijn in TrainingFeatureService
     * @param duplicateFeatures  features die door meerdere componenten worden geclaimd
     * @return lijst met foutberichten; leeg bij geldig contract
     */
    public static List<String> validate(
        String modelKey,
        ModelFeaturesConfig features,
        Set<String> registeredFeatureIds,
        Map<String, List<String>> duplicateFeatures
    ) {
        List<String> errors = new ArrayList<>();
        List<String> input = features.inputFeatures();

        if (input.isEmpty()) {
            errors.add(modelKey + ": inputFeatures is leeg");
            return errors;
        }
        if (features.targetFeatures().isEmpty()) {
            errors.add(modelKey + ": targetFeatures is leeg");
        }

        // Alleen inputfeatures worden gevalideerd tegen de registry — targets worden opgelost
        // door TrainingTargetProjector, niet door TrainingFeatureService.
        for (String fid : input) {
            if (!registeredFeatureIds.contains(fid)) {
                errors.add(modelKey + ": inputfeature '" + fid + "' niet geregistreerd in TrainingFeatureService");
            }
        }

        // Dubbele feature-eigenaars: als een inputfeature door meerdere componenten wordt
        // geclaimd, is het onduidelijk welke resolver de waarde levert.
        for (String fid : input) {
            List<String> owners = duplicateFeatures.get(fid);
            if (owners != null) {
                errors.add(modelKey + ": inputfeature '" + fid + "' wordt geclaimd door meerdere componenten: " + owners);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String fid : input) {
            if (!seen.add(fid)) {
                errors.add(modelKey + ": inputfeature '" + fid + "' is dubbel gedefinieerd");
            }
        }

        if (features.hasTemporalGroups()) {
            List<FeatureGroup> groups = features.featureGroups();
            for (int i = 0; i < groups.size(); i++) {
                FeatureGroup g = groups.get(i);
                if (g.features().isEmpty()) {
                    errors.add(modelKey + ": featureGroup[" + i + "] heeft geen features");
                }
                if (g.firstFrames() < 0) {
                    errors.add(modelKey + ": featureGroup[" + i + "] firstFrames < 0");
                }
                if (g.lastFrames() < 0) {
                    errors.add(modelKey + ": featureGroup[" + i + "] lastFrames < 0");
                }
            }
        }

        return errors;
    }
}
