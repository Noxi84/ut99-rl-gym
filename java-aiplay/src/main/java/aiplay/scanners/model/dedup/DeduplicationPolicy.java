package aiplay.scanners.model.dedup;

import aiplay.scanners.model.sample.TrainingSample;

/**
 * Bucket-based deduplication policy that limits the number of canonical samples
 * per state bucket. Applied before augmentation — if a canonical sample is
 * deduplicated, none of its augmented variants are generated.
 *
 * Stateful: tracks bucket counts per session. Create a new instance per session.
 */
public interface DeduplicationPolicy {

    /**
     * Returns true if this canonical sample should be accepted for training.
     * Returns false if the sample's state bucket has reached its limit.
     * Internally tracks and increments bucket counts.
     */
    boolean shouldAccept(TrainingSample sample);
}
