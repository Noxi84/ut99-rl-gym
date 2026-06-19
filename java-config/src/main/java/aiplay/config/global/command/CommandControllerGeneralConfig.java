package aiplay.config.global.command;

public record CommandControllerGeneralConfig(
    int controllerFps,
    int minMovementDwellMs,
    int dedupeThresholdUt,
    int viewTurnIntentMaxAgeMs,
    double collisionWallThresholdNorm,
    int jumpCooldownMs,
    int duckCooldownMs
) {

}
