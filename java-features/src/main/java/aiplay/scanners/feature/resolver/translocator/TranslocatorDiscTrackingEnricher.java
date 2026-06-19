package aiplay.scanners.feature.resolver.translocator;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vult {@code playerPawn.discTimeSinceThrow_norm} via rising-edge detectie
 * van {@code discPresent}.
 *
 * <p>Werking:
 * <ul>
 *   <li>discPresent was false, nu true → record throw-tijd, norm = 0.0</li>
 *   <li>discPresent blijft true → norm = min(1.0, elapsedMs / WINDOW_MS)</li>
 *   <li>discPresent valt naar false (teleport / out-of-bounds) → norm = 1.0
 *       (geen actieve disc; modelling-wise = "max tijd geleden"). Tracking
 *       state wordt gereset zodat de volgende throw weer norm = 0.0 begint.</li>
 * </ul>
 *
 * <p>Batch (CSV-training): gebruikt frame-index × frame-duration uit CSV fps;
 * incremental (live play): {@code System.currentTimeMillis()}.
 *
 * <p>Window: 1000 ms — translocator throw → teleport-besluit is meestal
 * &lt; 1s, dus 1s normalization geeft een fijne resolutie op de hot zone.
 */
public class TranslocatorDiscTrackingEnricher implements TrainingFeatureEnricher {

    private static final double THROW_WINDOW_MS = 1000.0;
    /** Default CSV-fps voor batch (50 Hz movement-CSV); override niet nodig — disc
     *  is statisch genoeg dat throw-tijd-resolutie OK is op deze fps. */
    private static final double DEFAULT_CSV_FPS = 50.0;

    private static final class SessionState {
        long throwAtMs = -1L;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        double frameMs = 1000.0 / DEFAULT_CSV_FPS;
        int throwIdx = -1;
        boolean prevPresent = false;

        for (int i = 0; i < frames.size(); i++) {
            GameStateDto f = frames.get(i);
            if (f == null || f.playerPawn == null) continue;
            PlayerDto pawn = f.playerPawn;

            if (pawn.discPresent) {
                if (!prevPresent || throwIdx < 0) {
                    throwIdx = i;
                }
                double elapsedMs = (i - throwIdx) * frameMs;
                pawn.discTimeSinceThrow_norm =
                    (float) Math.min(elapsedMs / THROW_WINDOW_MS, 1.0);
            } else {
                throwIdx = -1;
                pawn.discTimeSinceThrow_norm = 1.0f;
            }
            prevPresent = pawn.discPresent;
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        SessionState st = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        long now = System.currentTimeMillis();

        for (GameStateDto f : frames) {
            if (f == null || f.playerPawn == null) continue;
            PlayerDto pawn = f.playerPawn;

            if (pawn.discPresent) {
                if (st.throwAtMs < 0) {
                    st.throwAtMs = now;
                }
                long elapsedMs = now - st.throwAtMs;
                pawn.discTimeSinceThrow_norm =
                    (float) Math.min((double) elapsedMs / THROW_WINDOW_MS, 1.0);
            } else {
                st.throwAtMs = -1L;
                pawn.discTimeSinceThrow_norm = 1.0f;
            }
        }
    }
}
