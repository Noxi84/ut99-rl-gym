package aiplay.shared.engagement;

/**
 * Why the current engagement posture was chosen.
 * Used for logging and debugging the tactical decision.
 */
public enum EngagementReason {
    /** No enemy present or relevant. */
    NO_ENEMY,
    /** Enemy visible, tracking opportunistically. */
    ENEMY_VISIBLE,
    /** Enemy close and visible, committing to shot. */
    ENEMY_CLOSE_VISIBLE,
    /** Enemy has our flag and is visible. */
    ENEMY_CARRIER_VISIBLE,
    /** Bot carrying flag, avoiding combat. */
    FLAG_CARRIER_EVASION,
    /** Enemy is actively firing (possible threat). */
    ENEMY_FIRING,
    /** Enemy view direction points toward us (they can see us). */
    ENEMY_FACING_US,
    /** Enemy close but not visible — proximity threat. */
    ENEMY_NEARBY,
    /** Dwell protection — previous engagement still held. */
    DWELL_HOLD
}
