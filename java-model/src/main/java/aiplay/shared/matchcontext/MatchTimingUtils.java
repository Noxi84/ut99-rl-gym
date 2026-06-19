package aiplay.shared.matchcontext;

import aiplay.dto.GameStateDto;
import aiplay.dto.MapInfoDto;
import aiplay.dto.PlayerDto;

/**
 * Shared static helpers for match-timing and score-state derivations.
 *
 * <p>Single source of truth for {@code remaining_time_norm} (the [0,1]-normalized fraction of the
 * match still to play) and {@code signedScoreDiff} (our_score − their_score from the perspective of
 * {@code state.playerPawn.team}). Both feature resolvers and reward shaping consume these so the
 * two stay in lock-step (no repeat of the {@code feedback_objective_dual_source} anti-pattern where
 * navTarget feature and objective_progress reward duplicated logic).
 *
 * <p>Fallback rules mirror {@link aiplay.scanners.feature.resolver.matchcontext.MatchContextFeatureValueResolver}:
 * when UC sends {@code TimeLimit=0} (unlimited match, current production default) we use
 * {@code elapsedTime / DEFAULT_MAX_ELAPSED_SECONDS} as the anchor — same constant as the resolver
 * so feature and reward produce identical values per tick.
 */
public final class MatchTimingUtils {

    /** 10-min match anchor used as denominator when {@code timeLimit <= 0}. */
    public static final double DEFAULT_MAX_ELAPSED_SECONDS = 600.0;

    private MatchTimingUtils() {
    }

    /**
     * Fraction of match still to play, in {@code [0, 1]}. {@code 1.0} at match start, {@code 0.0}
     * at match end. Falls back to {@code 1 - elapsed/DEFAULT_MAX_ELAPSED_SECONDS} when
     * {@code TimeLimit=0} (UC unlimited-match config).
     */
    public static float remainingTimeNorm(MapInfoDto map) {
        if (map == null) {
            return 0.0f;
        }
        if (map.timeLimit > 0.0) {
            double ratio = map.remainingTime / map.timeLimit;
            if (ratio < 0.0) ratio = 0.0;
            if (ratio > 1.0) ratio = 1.0;
            return (float) ratio;
        }
        double elapsed = map.elapsedTime;
        if (!Double.isFinite(elapsed) || elapsed <= 0.0) {
            return 1.0f;
        }
        double elapsedRatio = elapsed / DEFAULT_MAX_ELAPSED_SECONDS;
        if (elapsedRatio < 0.0) elapsedRatio = 0.0;
        if (elapsedRatio > 1.0) elapsedRatio = 1.0;
        return (float) (1.0 - elapsedRatio);
    }

    /**
     * Score differential from the bot's own team perspective: {@code our_score − their_score}.
     * Positive when our team is leading, negative when behind, zero when tied. Returns {@code 0}
     * when the player pawn or map info is unavailable (treated as neither leading nor behind, so
     * downstream "behind?" checks are false-safe).
     */
    public static int signedScoreDiff(GameStateDto state) {
        if (state == null) {
            return 0;
        }
        PlayerDto pawn = state.playerPawn;
        MapInfoDto map = state.mapInfo;
        if (pawn == null || map == null) {
            return 0;
        }
        boolean isRed = pawn.team == 0;
        int myScore = parseScore(isRed ? map.redScore : map.blueScore);
        int theirScore = parseScore(isRed ? map.blueScore : map.redScore);
        return myScore - theirScore;
    }

    private static int parseScore(String raw) {
        if (raw == null) return 0;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return 0;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }
}
