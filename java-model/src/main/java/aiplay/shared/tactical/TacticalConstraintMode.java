package aiplay.shared.tactical;

/**
 * How movement is constrained by the active tactical rule.
 * Describes the enforcement behavior applied by the movement constraint applier.
 */
public enum TacticalConstraintMode {
    /** No movement constraint — all directions allowed. */
    UNCONSTRAINED,
    /**
     * Block the longitudinal component of movement that would cross back
     * toward the home half past the midfield line.
     * Used as fallback when carrier position is unreliable.
     */
    BLOCK_REENTRY_TO_HOME_HALF,
  /**
   * Block movement that would bring the bot homeward past the dynamic carrier line. The carrier line position is carried in TacticalIntent.
   */
  BLOCK_REENTRY_PAST_CARRIER_LINE
}
