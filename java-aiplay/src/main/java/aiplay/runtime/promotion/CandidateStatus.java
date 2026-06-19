package aiplay.runtime.promotion;

/**
 * Lifecycle states for a model candidate. Mirrors the Python
 * {@code train.common.Promotion.CandidateStatus} enum.
 */
public enum CandidateStatus {
    BUILT,
    EVALUATING,
    REJECTED,
    PROMOTION_READY,
    PROMOTED,
    SUPERSEDED,
    ROLLED_BACK
}
