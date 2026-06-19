package aiplay.shared.mission;

public class MissionIntent {
    public final MissionType missionType;
    public final MissionReason reason;
    public final long timestampMs;

    public MissionIntent(MissionType missionType, MissionReason reason, long frameTimestampMs) {
        this.missionType = missionType;
        this.reason = reason;
        this.timestampMs = frameTimestampMs;
    }
}
