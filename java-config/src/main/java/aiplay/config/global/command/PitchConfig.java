package aiplay.config.global.command;

public record PitchConfig(
    int continuousMaxStep,
    int minSigned,
    int maxSigned,
    double centerDecayRate,
    double deadZoneRad
) {

}
