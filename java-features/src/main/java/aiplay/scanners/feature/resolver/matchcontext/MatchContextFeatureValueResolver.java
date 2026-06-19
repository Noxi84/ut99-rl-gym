package aiplay.scanners.feature.resolver.matchcontext;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.shared.matchcontext.MatchTimingUtils;

/**
 * Resolves match-context features (remaining-time, score-diff, match-phase one-hot).
 *
 * <p>Numeric definitions live in {@link MatchTimingUtils} so reward shaping
 * ({@code aiplay.rl.rewards.team.endgame.EndgameUrgency}) and this feature resolver consume the same
 * source of truth — preventing the dual-source drift documented in
 * {@code memory feedback_objective_dual_source}.
 *
 * <p>Team-conventie: team == 0 → red, team == 1 → blue. Score-diff is perspective-invariant via
 * pawn.team, so no extra augmentation needed beyond existing red/blue normalization in
 * RealtimeSequenceInputBuilder.
 */
public class MatchContextFeatureValueResolver implements TrainingFeatureValueResolver {

    static final int PHASE_EARLY = 0;
    static final int PHASE_MID = 1;
    static final int PHASE_LATE = 2;

    static final float EARLY_MID_ELAPSED_THRESHOLD = 0.5f;
    static final float MID_LATE_ELAPSED_THRESHOLD = 0.85f;

    static final double SCORE_DIFF_TANH_SCALE = 3.0;

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto frame) {
        if (frame == null) return 0.0f;

        return switch (featureId) {
            case "remaining_time_norm" -> MatchTimingUtils.remainingTimeNorm(frame.mapInfo);
            case "score_diff_norm" -> resolveScoreDiffNorm(frame);
            case "match_phase_early" -> resolvePhaseFlag(frame, PHASE_EARLY);
            case "match_phase_mid" -> resolvePhaseFlag(frame, PHASE_MID);
            case "match_phase_late" -> resolvePhaseFlag(frame, PHASE_LATE);
            default -> null;
        };
    }

    private float resolveScoreDiffNorm(GameStateDto frame) {
        int diff = MatchTimingUtils.signedScoreDiff(frame);
        return (float) Math.tanh(diff / SCORE_DIFF_TANH_SCALE);
    }

    private float resolvePhaseFlag(GameStateDto frame, int targetPhase) {
        float elapsed = 1.0f - MatchTimingUtils.remainingTimeNorm(frame.mapInfo);
        int activePhase;
        if (elapsed < EARLY_MID_ELAPSED_THRESHOLD) {
            activePhase = PHASE_EARLY;
        } else if (elapsed < MID_LATE_ELAPSED_THRESHOLD) {
            activePhase = PHASE_MID;
        } else {
            activePhase = PHASE_LATE;
        }
        return activePhase == targetPhase ? 1.0f : 0.0f;
    }
}
