package aiplay.config.global;

public record GameplayConfig(
    double nearDistNorm,
    String mapName,
    String weaponProfile,
    int matchTimeMinutes,
    double flagDropAutoReturnSeconds
) {}
