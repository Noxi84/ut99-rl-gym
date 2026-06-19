package aiplay.config.model;

public record ModelRuntimeConfig(
    int predictionFps,
    int dodgeCooldownMs,
    int idleDurationWindowMs,
    double idleEnterThreshold,
    double idleExitThreshold
) {}
