package aiplay.scanners.feature.resolver.matchcontext;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Match-context features die de stationaire actor een tijd/score-tag geven
 * zodat finite-horizon CTF (10–20 min, terminerend bij time-limit) leerbaar
 * wordt als infinite-horizon reformulation in Bertsekas-zin (Sec. 1.6.4):
 * de stage-index k wordt impliciet in de state opgenomen en de stationaire
 * mu(x_k, k) kan zich gedragen als de sequentie {mu_0, ..., mu_{N-1}}.
 *
 * <ul>
 *   <li>{@code remaining_time_norm}: remainingTime / timeLimit, geclampt naar [0,1].
 *       1 = match net begonnen, 0 = match einde.</li>
 *   <li>{@code score_diff_norm}: tanh((our_score - their_score) / 3.0), in [-1,1].
 *       Tanh-schaal 3 zorgt dat een 3-flag-voorsprong al ~0.76 oplevert; diff van 6
 *       verzadigt op ~0.96. Robuust tegen onbekende score-limit.</li>
 *   <li>{@code match_phase_early|mid|late}: one-hot bucketing van elapsed_norm
 *       (= 1 - remaining_time_norm). Thresholds 0.5 en 0.85: early [0, 0.5),
 *       mid [0.5, 0.85), late [0.85, 1.0]. Geeft de policy een coarse phase-trigger
 *       naast de continue tijd-feature.</li>
 * </ul>
 *
 * Bron-data komt uit {@code GameStateDto.mapInfo} (timeLimit/remainingTime/redScore/blueScore)
 * en {@code GameStateDto.playerPawn.team}; alle data wordt al per frame gepopuleerd door
 * {@link MatchContextJsonToDtoConverter}.
 */
@TrainingFeatureComponent(priority = 10)
public class MatchContextFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "remaining_time_norm",
        "score_diff_norm",
        "match_phase_early",
        "match_phase_mid",
        "match_phase_late"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
        "match_phase_early",
        "match_phase_mid",
        "match_phase_late"
    );

    private final MatchContextFeatureValueResolver resolver = new MatchContextFeatureValueResolver();
    private final MatchContextJsonToDtoConverter converter = new MatchContextJsonToDtoConverter();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return BOOLEAN_FEATURES;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }

    @Override
    public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() {
        return converter;
    }
}
