package aiplay.scanners.feature.resolver.movement.dodge;

import aiplay.config.model.ModelConfig;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks dodge direction through the ACTIVE phase and computes continuous
 * dodge cooldown for the movement model.
 *
 * <p>Sets {@code playerPawn.activeDodgeDir} to the UT99 dodge direction
 * (1=fwd, 2=back, 3=left, 4=right) during both the direction and ACTIVE
 * phases. Cleared on DONE/NONE.</p>
 *
 * <p>Sets {@code playerPawn.dodgeCooldownNorm}: 0.0 = just dodged,
 * 1.0 = fully recovered.</p>
 *
 * <p>Batch mode (CSV): uses frame counting at known FPS (recording ElapsedTime
 * has only integer-second resolution, too coarse for 300ms cooldown).
 * Incremental mode (runtime): uses System.currentTimeMillis().</p>
 */
public class DodgeDirTrackingEnricher implements TrainingFeatureEnricher {

    private static final int DODGE_COOLDOWN_MS = loadDodgeCooldownMs();
    private static final int CSV_FPS = loadCsvFps();

    private static int loadDodgeCooldownMs() {
        try {
            ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
            int v = cfg.runtime().dodgeCooldownMs();
            return v > 0 ? v : 300;
        } catch (Exception e) {
            return 300;
        }
    }

    private static int loadCsvFps() {
        try {
            ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
            return cfg.trainingCsv().csvFps() > 0 ? cfg.trainingCsv().csvFps() : 30;
        } catch (Exception e) {
            return 30;
        }
    }

    /** Per-session state for incremental (runtime) enrichment. */
    private static class SessionState {
        int trackedDir = 0;
        DodgeState prevDodgeState = DodgeState.NONE;
        long lastDodgeInitMs = -1L;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        int trackedDir = 0;
        DodgeState prevState = DodgeState.NONE;
        int dodgeInitFrameIdx = -1;
        double frameDurationMs = 1000.0 / CSV_FPS;

        for (int i = 0; i < frames.size(); i++) {
            GameStateDto f = frames.get(i);
            if (f == null || f.playerPawn == null) continue;
            DodgeState ds = f.playerPawn.dodgeState;
            if (ds == null) ds = DodgeState.NONE;

            trackedDir = trackDir(ds, trackedDir);
            f.playerPawn.activeDodgeDir = trackedDir;

            // Detect dodge initiation: NONE → directional
            if (prevState == DodgeState.NONE && isDirectional(ds)) {
                dodgeInitFrameIdx = i;
            }
            prevState = ds;

            // Compute cooldown from frame counting
            if (dodgeInitFrameIdx < 0) {
                f.playerPawn.dodgeCooldownNorm = 1.0f; // no dodge yet
            } else {
                double elapsedMs = (i - dodgeInitFrameIdx) * frameDurationMs;
                f.playerPawn.dodgeCooldownNorm = (float) Math.min(elapsedMs / DODGE_COOLDOWN_MS, 1.0);
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
            DodgeState ds = f.playerPawn.dodgeState;
            if (ds == null) ds = DodgeState.NONE;

            st.trackedDir = trackDir(ds, st.trackedDir);
            f.playerPawn.activeDodgeDir = st.trackedDir;

            if (st.prevDodgeState == DodgeState.NONE && isDirectional(ds)) {
                st.lastDodgeInitMs = now;
            }
            st.prevDodgeState = ds;

            // Compute cooldown from wall clock
            if (st.lastDodgeInitMs < 0) {
                f.playerPawn.dodgeCooldownNorm = 1.0f;
            } else {
                double elapsedMs = now - st.lastDodgeInitMs;
                f.playerPawn.dodgeCooldownNorm = (float) Math.min(elapsedMs / DODGE_COOLDOWN_MS, 1.0);
            }
        }
    }

    private static int trackDir(DodgeState ds, int trackedDir) {
        return switch (ds) {
            case FORWARD -> 1;
            case BACK    -> 2;
            case LEFT    -> 3;
            case RIGHT   -> 4;
            case ACTIVE  -> trackedDir;
            default      -> 0;
        };
    }

    private static boolean isDirectional(DodgeState ds) {
        return ds == DodgeState.FORWARD || ds == DodgeState.BACK
            || ds == DodgeState.LEFT || ds == DodgeState.RIGHT;
    }
}
