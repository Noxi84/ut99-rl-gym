package aiplay.config.global;

public record MissionConfig(
    int missionAnnotatorFps,
    int missionMinDwellMs,
    double antiStuckSpeedNormThreshold,
    double antiStuckForwardCollisionThresholdNorm,
    double antiStuckForwardDiagCollisionThresholdNorm,
    int antiStuckTriggerMs,
    int antiStuckRecoveryMs
) {}
