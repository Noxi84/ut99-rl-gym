package aiplay.config.global;

/**
 * Configuration voor sticky aim-target selectie in {@code AimTargetSelector}.
 *
 * <p>Hysterese voorkomt dat aim-target frame-per-frame switcht tussen enemies
 * zodra slot 0 (closest) van identiteit wisselt — de oorzaak van pitch-oscillatie bij 2+ enemies. De selector houdt een currentTargetName per sessie bij (in {@code AimTargetState}) en switcht alleen onder gecontroleerde condities.</p>
 */
public record AimTargetConfig(
    /** Minimum tijd dat huidige target unseen moet zijn voor een closer candidate mag winnen. */
    int unseenBeforeSwitchMs,
    /** Absolute unseen-timeout: huidige target wordt verlaten ongeacht candidate. */
    int unseenForceSwitchMs,
    /** Nieuwe candidate moet ≤ deze ratio × huidige afstand zijn om de switch te rechtvaardigen. */
    double switchDistanceRatio,
    /** Minimum tijd na committen voordat een switch (niet forced) mag plaatsvinden. */
    int minCommitMs
) {

}
