package aiplay.config.global;

/**
 * Configuration for the engagement/attention tactical layer.
 * Controls dwell, hysteresis, and distance thresholds.
 */
public record EngagementConfig(
    int engagementMinDwellMs,
    int attentionTargetMinDwellMs,
    int commitShotHoldMs,
    int visibleGraceMs,
    double commitShotDistanceThreshold
) {}
