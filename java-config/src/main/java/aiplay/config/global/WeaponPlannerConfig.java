package aiplay.config.global;

/**
 * Config for the weapon-planner behavior-tree lane (runtime.json {@code /weapon_planner}).
 *
 * @param fps    tick rate of the lane that decides which weapon a bot should hold.
 *               Low by default — the preferred weapon only changes on ammo depletion
 *               or pickup, so a few Hz is plenty.
 * @param dwellMs anti-flapping hysteresis: once the planner commits to a weapon it stays
 *                there for at least this long, unless the committed weapon becomes
 *                unusable (out of ammo / dropped). Prevents rapid back-and-forth when a
 *                weapon's ammo hovers around zero.
 */
public record WeaponPlannerConfig(
    int fps,
    int dwellMs
) {}
