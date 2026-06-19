package aiplay.scanners.feature.resolver.playerpawn.yawangularvelocity;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes yaw angular velocity between consecutive frames.
 * Normalized to rotations-per-second: 1.0 = one full 360deg rotation/sec.
 * Positive = turning right (increasing yaw), negative = turning left.
 *
 * Batch mode uses local state (CSV generation, full session at once).
 * Incremental mode keeps per-session state — the enricher is a singleton
 * inside TrainingFeatureService and is shared across every bot in the JVM,
 * so plain instance fields would leak yaw between bots and poison the
 * viewrotation LSTM input with garbage angular velocities.
 */
public class YawAngularVelocityEnricher implements TrainingFeatureEnricher {

    /** 65536 UT units = 360deg = 1 full rotation. Normalizing by this gives rotations/sec. */
    private static final double NORM_DIVISOR = 65536.0;

    private static final class SessionState {
        int lastYaw = -1;
        long lastTimestampMs = -1;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;

        int prevYaw = -1;
        long prevTs = -1;

        for (GameStateDto frame : frames) {
            computeAndStore(frame, prevYaw, prevTs);
            if (frame != null && frame.playerPawn != null && frame.playerPawn.viewRotation != null) {
                prevYaw = frame.playerPawn.viewRotation.x & 0xFFFF;
                prevTs = frame.timestampMillis;
            }
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null) return;
        SessionState st = sessions.computeIfAbsent(sessionId, k -> new SessionState());

        for (GameStateDto frame : frames) {
            computeAndStore(frame, st.lastYaw, st.lastTimestampMs);
            if (frame != null && frame.playerPawn != null && frame.playerPawn.viewRotation != null) {
                st.lastYaw = frame.playerPawn.viewRotation.x & 0xFFFF;
                st.lastTimestampMs = frame.timestampMillis;
            }
        }
    }

    private static void computeAndStore(GameStateDto frame, int prevYaw, long prevTs) {
        if (frame == null || frame.playerPawn == null) return;

        if (prevYaw < 0 || frame.playerPawn.viewRotation == null) {
            frame.playerPawn.yawAngularVelocity_norm = 0f;
            return;
        }

        int curYaw = frame.playerPawn.viewRotation.x & 0xFFFF;
        int delta = curYaw - prevYaw;
        // Wrap to [-32768, 32767] for shortest-path delta
        if (delta > 32768) delta -= 65536;
        else if (delta < -32768) delta += 65536;

        long deltaMs = frame.timestampMillis - prevTs;
        if (deltaMs <= 0) {
            frame.playerPawn.yawAngularVelocity_norm = 0f;
            return;
        }

        // Angular velocity in rotations per second, clamped to [-1, 1]
        double angVelRotPerSec = (double) delta / deltaMs * 1000.0 / NORM_DIVISOR;
        frame.playerPawn.yawAngularVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, angVelRotPerSec));
    }
}
