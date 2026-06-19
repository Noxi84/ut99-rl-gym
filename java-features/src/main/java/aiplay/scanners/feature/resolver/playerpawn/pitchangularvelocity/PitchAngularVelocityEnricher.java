package aiplay.scanners.feature.resolver.playerpawn.pitchangularvelocity;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes pitch angular velocity between consecutive frames.
 * Normalized to rotations-per-second: 1.0 = one full 360deg rotation/sec.
 * Positive = pitching up (increasing pitch), negative = pitching down.
 *
 * Uses the same signed-pitch conversion as the rest of the pipeline:
 * 0..18000 = up (positive), 49152..65535 = down (negative).
 *
 * Incremental mode keeps per-session state — the enricher is a singleton
 * inside TrainingFeatureService and is shared across every bot in the JVM,
 * so plain instance fields would leak pitch between bots.
 */
public class PitchAngularVelocityEnricher implements TrainingFeatureEnricher {

    /** 65536 UT units = 360deg = 1 full rotation. Normalizing by this gives rotations/sec. */
    private static final double NORM_DIVISOR = 65536.0;

    private static final class SessionState {
        int lastPitch = Integer.MIN_VALUE;
        long lastTimestampMs = -1;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;

        int prevPitch = Integer.MIN_VALUE;
        long prevTs = -1;

        for (GameStateDto frame : frames) {
            computeAndStore(frame, prevPitch, prevTs);
            if (frame != null && frame.playerPawn != null && frame.playerPawn.viewRotation != null) {
                prevPitch = toSignedPitch(frame.playerPawn.viewRotation.y);
                prevTs = frame.timestampMillis;
            }
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null) return;
        SessionState st = sessions.computeIfAbsent(sessionId, k -> new SessionState());

        for (GameStateDto frame : frames) {
            computeAndStore(frame, st.lastPitch, st.lastTimestampMs);
            if (frame != null && frame.playerPawn != null && frame.playerPawn.viewRotation != null) {
                st.lastPitch = toSignedPitch(frame.playerPawn.viewRotation.y);
                st.lastTimestampMs = frame.timestampMillis;
            }
        }
    }

    private static void computeAndStore(GameStateDto frame, int prevPitch, long prevTs) {
        if (frame == null || frame.playerPawn == null) return;

        if (prevPitch == Integer.MIN_VALUE || frame.playerPawn.viewRotation == null) {
            frame.playerPawn.pitchAngularVelocity_norm = 0f;
            return;
        }

        int curPitch = toSignedPitch(frame.playerPawn.viewRotation.y);
        int delta = curPitch - prevPitch;

        long deltaMs = frame.timestampMillis - prevTs;
        if (deltaMs <= 0) {
            frame.playerPawn.pitchAngularVelocity_norm = 0f;
            return;
        }

        // Angular velocity in rotations per second, clamped to [-1, 1]
        double angVelRotPerSec = (double) delta / deltaMs * 1000.0 / NORM_DIVISOR;
        frame.playerPawn.pitchAngularVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, angVelRotPerSec));
    }

    /** Convert unsigned UT99 pitch to signed: 0..18000 = up (positive), 49152..65535 = down (negative). */
    private static int toSignedPitch(int unsignedPitch) {
        if (unsignedPitch <= 18000) return unsignedPitch;
        if (unsignedPitch >= 49152) return unsignedPitch - 65536;
        return 0;
    }
}
