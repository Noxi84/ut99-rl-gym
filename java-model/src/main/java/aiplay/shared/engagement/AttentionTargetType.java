package aiplay.shared.engagement;

/**
 * What the bot's view and shooting systems should focus on right now.
 * Determines the bearing source for viewrotation and the target for shooting.
 */
public enum AttentionTargetType {
    /** Look toward enemy flag (offensive objective). */
    OBJECTIVE_ENEMY_FLAG,
    /** Look toward home base (defensive objective / flag capture). */
    OBJECTIVE_HOME_BASE,
    /** Look toward own flag (recovery / defensive). */
    OBJECTIVE_HOME_FLAG,
    /** Look toward the closest enemy player. */
    ENEMY_PLAYER,
    /** Look toward the enemy carrying our flag. */
    ENEMY_CARRIER,
    /**
     * Look toward the (living) enemy nearest to our home flag — defender focus when no carrier
     * exists yet, so the bot pre-aims at the most likely incoming attacker.
     */
    ENEMY_NEAREST_TO_HOME_FLAG,
    /**
     * Look toward the (living) enemy nearest to our team's Attack-role teammate — Cover focus,
     * so the bot tracks the threat that the attacker is most likely about to engage.
     */
    ENEMY_NEAREST_TO_ATTACKER,
    /**
     * Look toward the enemy that most directly threatens US — the attacker the bot must watch to
     * stay alive. Used while the bot carries the flag (role-blind): it focuses on whoever is
     * shooting at / facing / closing on the carrier rather than on the enemy flag carrier (EFC) it
     * happens to share an area with. Resolves to null (→ objective fallback) when no enemy poses a
     * real threat; the fleeing EFC only counts when it actively attacks us.
     */
    ENEMY_THREAT_TO_SELF,
    /** No specific target — fallback to objective. */
    NONE
}
