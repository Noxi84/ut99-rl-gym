package aiplay.shared.tactical;

/**
 * Why the current tactical constraint was activated or deactivated.
 * Used for logging, debugging, and observability of tactical decisions.
 */
public enum TacticalReason {
    /** No tactical constraint applicable — default state. */
    NO_CONSTRAINT,
  /**
   * Carrier shadow deny active — carrier line reliable, tracking enemy carrier position.
   */
  CARRIER_SHADOW_ACTIVE,
  /**
   * Midfield fallback deny active — carrier position unreliable, using midfield as boundary.
   */
  MIDFIELD_FALLBACK_ACTIVE,
    /** Constraint released because bot picked up the enemy flag. */
    SELF_HAS_ENEMY_FLAG,
    /** Constraint released because enemy no longer has our flag. */
    ENEMY_NO_LONGER_HAS_FLAG,
    /** Dwell protection — previous tactical state still held. */
    DWELL_HOLD
}
