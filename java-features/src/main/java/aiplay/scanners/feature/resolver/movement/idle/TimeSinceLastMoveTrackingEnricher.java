package aiplay.scanners.feature.resolver.movement.idle;

import aiplay.config.model.ModelConfig;
import aiplay.dto.GameStateDto;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks how long the player has been continuously idle (no horizontal velocity)
 * and writes a normalized 0..1 signal to {@code playerPawn.timeSinceLastMoveNorm}:
 *
 * <p>0.0 = currently moving or just stopped, 1.0 = idle for ≥ window.</p>
 *
 * <p>Mirrors {@link aiplay.scanners.feature.resolver.movement.dodge.DodgeDirTrackingEnricher}'s
 * batch / incremental split: batch mode uses frame counting at the configured CSV FPS;
 * incremental mode uses {@link System#currentTimeMillis()}.</p>
 */
public class TimeSinceLastMoveTrackingEnricher implements TrainingFeatureEnricher {

    private static final float MIN_SPEED_NORM = 0.01f;

    private static final int IDLE_WINDOW_MS = loadIdleWindowMs();
    private static final int CSV_FPS = loadCsvFps();

    private static int loadIdleWindowMs() {
        ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
        return cfg.runtime().idleDurationWindowMs();
    }

    private static int loadCsvFps() {
        ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
        return cfg.trainingCsv().csvFps();
    }

    /** Per-session state for incremental (runtime) enrichment. */
    private static final class SessionState {
        long idleSinceMs = -1L;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        int idleStartIdx = -1;
        double frameDurationMs = 1000.0 / CSV_FPS;

        for (int i = 0; i < frames.size(); i++) {
            GameStateDto f = frames.get(i);
            if (f == null || f.playerPawn == null) continue;

            if (isIdleNow(f)) {
                if (idleStartIdx < 0) {
                    idleStartIdx = i;
                }
                double elapsedMs = (i - idleStartIdx) * frameDurationMs;
                f.playerPawn.timeSinceLastMoveNorm =
                        (float) Math.min(elapsedMs / IDLE_WINDOW_MS, 1.0);
            } else {
                idleStartIdx = -1;
                f.playerPawn.timeSinceLastMoveNorm = 0f;
            }
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        SessionState st = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        long now = System.currentTimeMillis();

        for (GameStateDto f : frames) {
            if (f == null || f.playerPawn == null) continue;

            if (isIdleNow(f)) {
                if (st.idleSinceMs < 0) {
                    st.idleSinceMs = now;
                }
                long elapsedMs = now - st.idleSinceMs;
                f.playerPawn.timeSinceLastMoveNorm =
                        (float) Math.min((double) elapsedMs / IDLE_WINDOW_MS, 1.0);
            } else {
                st.idleSinceMs = -1L;
                f.playerPawn.timeSinceLastMoveNorm = 0f;
            }
        }
    }

    private static boolean isIdleNow(GameStateDto f) {
        float vx = f.playerPawn.velocityX_norm;
        float vy = f.playerPawn.velocityY_norm;
        float speedSq = vx * vx + vy * vy;
        return speedSq < (MIN_SPEED_NORM * MIN_SPEED_NORM);
    }
}
