package aiplay.scanners.feature.resolver.jumppad;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.HashSet;
import java.util.Set;

/**
 * Auto-discovered feature component for {@code self_jumpPad{N}_*} features.
 * Pre-registers all suffixes for slots {@code 0..MAX_SLOTS-1}; only suffixes that
 * actually appear in features.json are used at runtime.
 *
 * <p>Naamgeving volgt het {@code <owner>_<feature>} patroon: alle pad-relaties zijn
 * voorzien van een explicit owner-prefix ({@code self_}). Toekomstige uitbreidingen
 * kunnen analoog {@code enemy{N}_jumpPad{M}_*} of {@code teammate{N}_jumpPad{M}_*}
 * bieden via aanvullende components + enrichers (de huidige enricher computeert alleen
 * voor {@code playerPawn}).
 *
 * <p>Priority 11: enricher must run after PlayerPawnBasicFeatureJsonToDtoConverter
 * (priority 10) so {@code playerPawn.location} and {@code playerPawn.viewRotation}
 * are populated before nearest-pad sorting.
 */
@TrainingFeatureComponent(priority = 11)
public class JumpPadFeatureComponent implements ITrainingFeature {

    /** Suffix bestaande uit jumpPad{N}_<feature>. Hergebruikbaar door eventuele
     *  enemy/teammate variants in de toekomst. */
    public static final String[] PAD_SUFFIXES = {
        "present",
        "relSin", "relCos",
        "distance_norm",
        "forwardDist_norm", "rightDist_norm",
        "zOffset_norm",
        "landingForwardDist_norm", "landingRightDist_norm",
        "landingZOffset_norm",
        "landingTowardsGoal_cos",
    };

    public static final String[] PAD_BOOLEAN_SUFFIXES = {
        "present",
    };

    private static final Set<String> CACHED_FEATURE_IDS;
    private static final Set<String> CACHED_BOOLEAN_FEATURES;

    static {
        Set<String> ids = new HashSet<>(JumpPadEnricher.MAX_SLOTS * PAD_SUFFIXES.length);
        Set<String> bools = new HashSet<>(JumpPadEnricher.MAX_SLOTS * PAD_BOOLEAN_SUFFIXES.length);
        for (int slot = 0; slot < JumpPadEnricher.MAX_SLOTS; slot++) {
            String prefix = "self_jumpPad" + slot + "_";
            for (String suffix : PAD_SUFFIXES) {
                ids.add(prefix + suffix);
            }
            for (String suffix : PAD_BOOLEAN_SUFFIXES) {
                bools.add(prefix + suffix);
            }
        }
        CACHED_FEATURE_IDS = Set.copyOf(ids);
        CACHED_BOOLEAN_FEATURES = Set.copyOf(bools);
    }

    private final JumpPadFeatureValueResolver resolver = new JumpPadFeatureValueResolver();
    private final JumpPadEnricher enricher = new JumpPadEnricher();

    @Override
    public Set<String> getFeatureIds() {
        return CACHED_FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return CACHED_BOOLEAN_FEATURES;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
