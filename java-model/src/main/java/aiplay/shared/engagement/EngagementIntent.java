package aiplay.shared.engagement;

/**
 * Tactical intent describing how the bot engages enemies and where attention is focused.
 * Produced by {@code MissionAnnotator} and written to {@code GameStateDto.annotatedEngagement}
 * / {@code annotatedAttentionTarget} for downstream feature resolvers and reward utilities.
 */
public class EngagementIntent {
    public final EngagementType engagementType;
    public final AttentionTargetType attentionTarget;
    public final EngagementReason reason;
    public final long timestampMs;

    public EngagementIntent(EngagementType engagementType,
                            AttentionTargetType attentionTarget,
                            EngagementReason reason,
                            long frameTimestampMs) {
        this.engagementType = engagementType;
        this.attentionTarget = attentionTarget;
        this.reason = reason;
        this.timestampMs = frameTimestampMs;
    }
}
