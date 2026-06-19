package aiplay.scanners.feature.resolver.mission;

import aiplay.dto.GameStateDto;
import aiplay.mission.MissionAnnotator;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enriches frames with mission/engagement/tactical/spatial annotation using the shared MissionAnnotator.
 *
 * Two paths:
 * - enrichBatch (CSV): fresh annotator, processes all frames sequentially
 * - enrichIncremental (realtime): persistent annotator per sessionId
 *
 * Both paths set annotatedMission/annotatedEngagement/annotatedAttentionTarget and tactical/spatial
 * facts on each GameStateDto frame. Downstream feature resolvers read these fields — no blackboard leakage.
 *
 * Per-instance isolation relies on unique sessionIds (e.g. "instance-0", "instance-1")
 * set by MultiInstanceLauncher.
 */
public class MissionAnnotationFeatureEnricher implements TrainingFeatureEnricher {

    /** Per-session annotator state. Static because there is one enricher per JVM. */
    private static final ConcurrentHashMap<String, AnnotatorState> runtimeStates =
            new ConcurrentHashMap<>();

    /**
     * Remove a session's cached annotator state. Called by MultiInstanceLauncher
     * on session teardown to avoid unbounded state growth.
     */
    public static void unregisterSession(String sessionId) {
        runtimeStates.remove(sessionId);
    }

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        MissionAnnotator annotator = new MissionAnnotator();
        for (GameStateDto frame : frames) {
            if (frame == null) continue;
            MissionAnnotator.Result result = annotator.annotate(frame);
            if (result != null) {
                frame.annotatedMission = result.mission.missionType;
                frame.annotatedEngagement = result.engagement.engagementType;
                frame.annotatedAttentionTarget = result.engagement.attentionTarget;
            }
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;

        AnnotatorState state = runtimeStates.computeIfAbsent(sessionId, k -> new AnnotatorState());

        // Find first frame after the last processed timestamp to avoid re-processing
        int startIndex = findFirstIndexAfterTimestamp(frames, state.lastTs);
        if (startIndex < 0) return;

        for (int i = startIndex; i < frames.size(); i++) {
            GameStateDto frame = frames.get(i);
            if (frame == null || frame.playerPawn == null) continue;

            MissionAnnotator.Result result = state.annotator.annotate(frame);
            if (result != null) {
                frame.annotatedMission = result.mission.missionType;
                frame.annotatedEngagement = result.engagement.engagementType;
                frame.annotatedAttentionTarget = result.engagement.attentionTarget;
            }
            state.lastTs = frame.timestampMillis;
        }
    }

    private static int findFirstIndexAfterTimestamp(List<GameStateDto> frames, long lastTs) {
        if (lastTs == -1L) return 0;
        for (int i = 0; i < frames.size(); i++) {
            GameStateDto gs = frames.get(i);
            if (gs != null && gs.timestampMillis > lastTs) {
                return i;
            }
        }
        return -1;
    }

    private static final class AnnotatorState {
        final MissionAnnotator annotator = new MissionAnnotator();
        long lastTs = -1L;
    }
}
