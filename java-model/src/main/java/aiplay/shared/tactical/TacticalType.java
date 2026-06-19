package aiplay.shared.tactical;

/**
 * Active tactical spatial constraint rule. Orthogonal to mission/skill/route/engagement:
 * does not change the objective or attention, but restricts allowed movement execution.
 *
 * Each type represents a distinct spatial constraint regime. Only one tactical type
 * is active at a time per bot.
 */
public enum TacticalType {
    /** No spatial constraint active — movement is unconstrained. */
    NONE,
    /**
     * Dynamic carrier-shadow deny: the no-pass boundary tracks the enemy flag
     * carrier's longitudinal position. Active when carrier position is reliable.
     */
    CARRIER_SHADOW_DENY,
  /**
   * Midfield fallback deny: falls back to the static midfield line when carrier position is unknown or unreliable.
   */
  MIDFIELD_FALLBACK_DENY
}
