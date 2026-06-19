package aiplay.config.global;

/**
 * Configuration for the tactical spatial constraint layer.
 * Loaded from runtime.json /runtime/tactical.
 */
public record TacticalConfig(
    int tacticalMinDwellMs,
    double carrierLineMarginNorm,
    int carrierGraceMs
) {}
