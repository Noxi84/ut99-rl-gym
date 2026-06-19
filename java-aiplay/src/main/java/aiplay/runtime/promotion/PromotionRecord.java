package aiplay.runtime.promotion;

/**
 * Immutable record of a promotion decision for one model role.
 * Read from {@code active_bindings.json} at startup for observability.
 */
public record PromotionRecord(
    String modelKey,
    String role,
    int step,
    double valLoss,
    CandidateStatus status,
    double promotedAtEpoch,
    PromotionRecord previous  // nullable — the superseded binding for rollback
) {
    public boolean hasRollbackTarget() {
        return previous != null;
    }
}
