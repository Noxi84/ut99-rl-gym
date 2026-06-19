package aiplay.config.global.command;

public record CommandControllerConfig(
    CommandControllerGeneralConfig general,
    YawHeadingConfig yawHeading,
    PitchConfig pitch
) {

}
