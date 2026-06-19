package aiplay.config.model;

import java.util.List;

public record ModelTrainingCsvConfig(
    boolean enabled,
    int csvFps,
    int numberOfColumns,
    List<String> stateBucketKey,
    int targetLookaheadFrames,
    int bcYawTargetScale,
    int bcPitchTargetScale
) {}
