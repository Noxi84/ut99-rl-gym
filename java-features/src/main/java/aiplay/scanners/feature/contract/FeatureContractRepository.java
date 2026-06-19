package aiplay.scanners.feature.contract;

import aiplay.config.ModelConfigRepository;
import aiplay.config.PropertyReaderUtils;
import aiplay.config.model.ModelConfig;
import aiplay.runtime.config.MapIdentityResolver;
import aiplay.scanners.feature.TrainingFeatureService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Singleton die voor elk model uit index.json een gevalideerd {@link FeatureContract} bijhoudt.
 *
 * <p>Roep {@link #validateAll()} aan bij startup vóór het starten van bot-threads.
 * Als een of meer contracten ongeldig zijn, gooit deze methode een
 * {@link IllegalStateException} met alle gevonden fouten.
 *
 * <p>Bij validatie wordt voor elke inputfeature het owning component bepaald en
 * worden dubbele eigenaars gedetecteerd. Na succesvolle validatie logt elke
 * contract een mensleesbare snapshot.
 */
public final class FeatureContractRepository {

    private static final Logger LOG = Logger.getLogger(FeatureContractRepository.class.getName());

    private static final FeatureContractRepository SHARED = new FeatureContractRepository();

    public static FeatureContractRepository shared() {
        return SHARED;
    }

    private final ConcurrentHashMap<String, FeatureContract> cache = new ConcurrentHashMap<>();

    private FeatureContractRepository() {}

    /**
     * Valideert alle modellen uit index.json en slaat geldige contracten op.
     * Gooit {@link IllegalStateException} als een of meer contracten ongeldig zijn.
     */
    public void validateAll() {
        TrainingFeatureService featureService = TrainingFeatureService.shared();
        Set<String> registeredIds = featureService.getRegisteredFeatureIds();
        Map<String, String> globalOwnership = featureService.getFeatureOwnership();
        Map<String, List<String>> duplicates = featureService.getDuplicateFeatures();

        if (!duplicates.isEmpty()) {
            LOG.warning("FEATURE_OWNERSHIP_DUPLICATES " + duplicates);
        }

        List<String> modelKeys = readModelKeysFromIndex();
        List<String> allErrors = new ArrayList<>();

        for (String modelKey : modelKeys) {
            ModelConfig cfg = ModelConfigRepository.shared().get(modelKey);
            List<String> errors = FeatureContractValidator.validate(
                modelKey, cfg.features(), registeredIds, duplicates);
            if (cfg.features().inputFeatures().contains("map_id")) {
                for (String err : MapIdentityResolver.validateAllConfiguredMapIds()) {
                    errors.add(modelKey + ": " + err);
                }
            }

            if (!errors.isEmpty()) {
                allErrors.addAll(errors);
            } else {
                // Bouw per-model ownership: alleen de inputfeatures van dit model
                Map<String, String> modelOwnership = new LinkedHashMap<>();
                for (String fid : cfg.features().inputFeatures()) {
                    String owner = globalOwnership.get(fid);
                    if (owner != null) {
                        modelOwnership.put(fid, owner);
                    }
                }

                FeatureContract contract = FeatureContract.from(modelKey, cfg, modelOwnership);
                cache.put(modelKey, contract);
                LOG.info("FEATURE_CONTRACT_OK model=" + modelKey
                    + " inputs=" + contract.inputFeatures().size()
                    + " targets=" + contract.targetFeatures().size()
                    + " window=" + contract.totalWindow()
                    + " owners=" + contract.featureOwnership().size());
                LOG.fine(() -> contract.toSnapshot());
            }
        }

        if (!allErrors.isEmpty()) {
            throw new IllegalStateException(
                "Feature contract validatie mislukt (" + allErrors.size() + " fout(en)):\n"
                + String.join("\n", allErrors));
        }
    }

    /**
     * Geeft het gevalideerde contract voor het opgegeven model.
     * Gooit {@link IllegalStateException} als {@link #validateAll()} nog niet is aangeroepen.
     */
    public FeatureContract get(String modelKey) {
        FeatureContract c = cache.get(modelKey);
        if (c == null) {
            throw new IllegalStateException(
                "FeatureContract voor model '" + modelKey + "' niet beschikbaar. "
                + "Roep FeatureContractRepository.shared().validateAll() aan bij startup.");
        }
        return c;
    }

    private static List<String> readModelKeysFromIndex() {
        JsonNode modelsNode = PropertyReaderUtils.getSubtree("/models");
        if (modelsNode == null || !modelsNode.isObject()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        modelsNode.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
