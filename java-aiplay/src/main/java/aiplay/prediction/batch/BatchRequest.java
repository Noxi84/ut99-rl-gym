package aiplay.prediction.batch;

import aiplay.prediction.GenericPredictor.FlatResult;

import java.util.concurrent.CompletableFuture;

/**
 * One bot's pending inference request sitting in the dispatcher queue.
 * The dispatcher thread collects up to maxBatchSize requests, runs them as one
 * {@code session.run()} and completes each future with its row outputs.
 */
final class BatchRequest {
    final float[][] window;
    final CompletableFuture<FlatResult> future;

    BatchRequest(float[][] window, CompletableFuture<FlatResult> future) {
        this.window = window;
        this.future = future;
    }
}
