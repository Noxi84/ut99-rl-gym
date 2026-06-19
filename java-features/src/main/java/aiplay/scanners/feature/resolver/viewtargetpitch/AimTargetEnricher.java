package aiplay.scanners.feature.resolver.viewtargetpitch;

import aiplay.config.global.AimTargetConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.shared.engagement.AimTargetSelection;
import aiplay.shared.engagement.AimTargetSelector;
import aiplay.shared.engagement.AimTargetState;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature-pipeline adapter rond {@link AimTargetSelector}: draait de sticky aim-target selectie
 * over een frame-batch en annoteert elk frame met {@code annotatedAimEnemy} +
 * {@code annotatedAimTargetIndex}, zodat alle aim-target lezers (heading-ref, bearing-input,
 * lookahead, reward) dezelfde enemy zien.
 *
 * <p>De adapter bezit alleen de <b>state-levensduur</b>: een verse {@link AimTargetState} per
 * offline batch (deterministisch per shard) versus een per-sessie persistente cursor voor live
 * incremental verwerking. De selectie-policy zelf (welke vijand, hysterese, hard-pin) leeft in
 * {@link AimTargetSelector} (java-model).
 */
public class AimTargetEnricher implements TrainingFeatureEnricher {

    private static final ConcurrentHashMap<String, SessionCursor> runtimeStates = new ConcurrentHashMap<>();

    public static void unregisterSession(String sessionId) {
        runtimeStates.remove(sessionId);
    }

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        AimTargetConfig cfg = GlobalConfigRepository.shared().aimTarget();
        AimTargetState state = new AimTargetState();
        for (GameStateDto frame : frames) {
            if (frame == null) continue;
            annotate(frame, state, cfg);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        AimTargetConfig cfg = GlobalConfigRepository.shared().aimTarget();
        SessionCursor cursor = runtimeStates.computeIfAbsent(sessionId, k -> new SessionCursor());
        int startIndex = findFirstIndexAfterTimestamp(frames, cursor.lastTs);
        if (startIndex < 0) return;
        for (int i = startIndex; i < frames.size(); i++) {
            GameStateDto frame = frames.get(i);
            if (frame == null) continue;
            annotate(frame, cursor.state, cfg);
            cursor.lastTs = frame.timestampMillis;
        }
    }

    private static void annotate(GameStateDto frame, AimTargetState state, AimTargetConfig cfg) {
        AimTargetSelection sel = AimTargetSelector.select(frame, state, cfg);
        frame.annotatedAimEnemy = sel.enemy();
        frame.annotatedAimTargetIndex = sel.slotIndex();
    }

    private static int findFirstIndexAfterTimestamp(List<GameStateDto> frames, long lastTs) {
        if (lastTs == Long.MIN_VALUE) return 0;
        for (int i = 0; i < frames.size(); i++) {
            GameStateDto gs = frames.get(i);
            if (gs != null && gs.timestampMillis > lastTs) return i;
        }
        return -1;
    }

    /** Per-sessie incremental cursor: de policy-state plus de laatst-verwerkte timestamp. */
    private static final class SessionCursor {
        final AimTargetState state = new AimTargetState();
        long lastTs = Long.MIN_VALUE;
    }
}
