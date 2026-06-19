package aiplay.shared.tactical;

/**
 * Which territorial boundary the active tactical constraint enforces.
 * Used by the movement constraint applier to know where to clamp.
 */
public enum TacticalTerritoryBoundary {
    /** No boundary — unconstrained. */
    NONE,
  /**
   * The midfield line between home and enemy halves (static fallback).
   */
  MIDFIELD,
  /**
   * Dynamic boundary at the enemy flag carrier's longitudinal position.
   */
  CARRIER_LINE
}
