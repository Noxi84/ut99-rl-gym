package aiplay.runtime;

import aiplay.dto.GameStateDto;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import java.util.logging.Logger;

/**
 * Per-bot detector for UT99 match-end transitions ({@code bGameEnded false→true}),
 * emitting a {@code MATCH_ENDED} log-tag for the trainer-side DualKPIDeltaGate.
 *
 * <p>The gate uses match-aligned eval: it counts MATCH_ENDED events since the
 * previous fire and triggers when the count reaches
 * {@code matches_per_eval_cycle}. This decouples gate cadence from wall-clock
 * and from ServerTravel/MinPlayers overhead between matches (~90s observed).
 *
 * <p>Format (same PlayerPawn.log file as {@link PlayerScoresLogger}):
 * <pre>
 * MATCH_ENDED t=&lt;unix_ms&gt; session=&lt;sessionId&gt;
 * </pre>
 *
 * <p>Multiple RL bots in the same UT99 instance each instantiate one logger;
 * the Python parser dedupes by (instance, 5s timestamp bucket) so the count
 * reflects unique match-end events, not per-bot copies.
 *
 * <p>Initialization: {@code prevEnded} starts as {@code null} so the first
 * tick establishes the baseline without emitting — avoids a spurious emit
 * if the bot happens to start during an end-screen.
 */
final class MatchEndLogger {

    private final String sessionId;
    private final Logger logger;
    private Boolean prevEnded;

    MatchEndLogger(String sessionId) {
        this.sessionId = sessionId;
        this.logger = SessionRollingLogger.get(
            sessionId, SessionLogPaths.featureLog("PlayerPawn"));
        this.prevEnded = null;
    }

    void maybeEmit(GameStateDto state) {
        if (state == null || state.mapInfo == null) return;
        boolean ended = state.mapInfo.bGameEnded;
        if (prevEnded == null) {
            prevEnded = ended;
            return;
        }
        if (!prevEnded && ended) {
            long nowMs = System.currentTimeMillis();
            logger.info("MATCH_ENDED t=" + nowMs + " session=" + sessionId);
        }
        prevEnded = ended;
    }
}
