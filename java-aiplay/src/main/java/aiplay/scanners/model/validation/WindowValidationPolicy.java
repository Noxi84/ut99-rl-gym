package aiplay.scanners.model.validation;

import aiplay.scanners.model.sample.TrainingSample;

/**
 * Per-model policy that decides whether a canonical window is valid for training.
 * Separate from deduplication — this checks content validity (e.g. sufficient lookahead),
 * not sample repetition.
 */
public interface WindowValidationPolicy {

    enum Decision {
        /** Window is valid, proceed with augmentation and writing. */
        ACCEPT,
        /** Window is invalid, skip it but continue iterating. */
        SKIP,
        /** Window is invalid and no subsequent windows will be valid either. Stop iteration. */
        STOP
    }

    Decision validate(TrainingSample sample);
}
