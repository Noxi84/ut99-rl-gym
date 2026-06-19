package aiplay.shared.tactical;

/**
 * Immutable tactical spatial constraint intent. Published to {@link TacticalIntentBus},
 * consumed by the movement constraint applier and feature resolvers.
 *
 * Describes the active constraint rule, how movement is restricted,
 * which boundary applies, the carrier line position, and why this state was chosen.
 */
public class TacticalIntent {
    public final TacticalType tacticalType;
    public final TacticalConstraintMode constraintMode;
    public final TacticalTerritoryBoundary territoryBoundary;
    public final TacticalReason reason;
    public final long timestampMs;
  /**
   * Longitudinal position of the active no-pass boundary [0..1], or -1.0 if not applicable.
   */
  public final double carrierLineProgressNorm;

    public TacticalIntent(TacticalType tacticalType,
                          TacticalConstraintMode constraintMode,
                          TacticalTerritoryBoundary territoryBoundary,
                          TacticalReason reason,
        long frameTimestampMs,
        double carrierLineProgressNorm) {
        this.tacticalType = tacticalType;
        this.constraintMode = constraintMode;
        this.territoryBoundary = territoryBoundary;
        this.reason = reason;
        this.timestampMs = frameTimestampMs;
      this.carrierLineProgressNorm = carrierLineProgressNorm;
    }

    /** Convenience factory for the default unconstrained state. */
    public static TacticalIntent unconstrained(long frameTimestampMs) {
        return new TacticalIntent(
                TacticalType.NONE,
                TacticalConstraintMode.UNCONSTRAINED,
                TacticalTerritoryBoundary.NONE,
                TacticalReason.NO_CONSTRAINT,
            frameTimestampMs,
            -1.0);
    }
}
