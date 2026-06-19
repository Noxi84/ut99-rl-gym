package aiplay.config.global;

/**
 * AI properties for a UT99 stock bot. Written to User.ini under [Botpack.ChallengeBotInfo]
 * with incrementing indices (BotTeams[0], BotNames[0], ...).
 *
 * @param jumpy           0 or 1 — jump frequency
 * @param favoriteWeapon  class path (e.g. "Botpack.ShockRifle")
 * @param camping         0.0–1.0 — tendency to camp
 * @param strafingAbility 0.0–99.0 — strafing dodge skill
 * @param combatStyle     -1.0–2.0 — aggressiveness (-1=defensive, 2=very aggressive)
 * @param alertness       0.0–99.0 — reaction speed and awareness
 * @param accuracy        0.0–1.0 — aim accuracy
 * @param skill           0.0–1.0 — overall skill level
 */
public record Ut99BotConfig(
    int jumpy,
    String favoriteWeapon,
    double camping,
    double strafingAbility,
    double combatStyle,
    double alertness,
    double accuracy,
    double skill
) {}
