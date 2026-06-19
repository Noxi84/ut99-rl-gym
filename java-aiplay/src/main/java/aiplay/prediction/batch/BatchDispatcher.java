package aiplay.prediction.batch;

import aiplay.prediction.GenericPredictor;
import aiplay.prediction.GenericPredictor.FlatResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalesces concurrent single-bot inference requests into batched
 * {@link GenericPredictor#predictBatchFull} calls.
 *
 * <p>One dispatcher per (modelKey, device). The queue pattern is
 * {@code take() + drainTo(maxBatchSize - 1)} with <b>no artificial sleep</b>:
 * while the dispatcher runs batch K, requests for batch K+1 pile up naturally
 * in the queue, so the next batch is as large as it can be without adding
 * latency. Same pattern as Triton dynamic batching with
 * max_queue_delay_microseconds=0.</p>
 *
 * <p>The worker runs on a daemon platform thread — deliberate, mirroring
 * {@code GenericPredictor.GPU_NATIVE_EXECUTOR}. The native ORT GPU call the
 * dispatcher triggers is still routed through that same executor internally.</p>
 */
public final class BatchDispatcher {

    private static final Logger LOG = Logger.getLogger(BatchDispatcher.class.getName());

    private final String modelKey;
    private final int maxBatchSize;
    private final GenericPredictor predictor;

    private final LinkedBlockingQueue<BatchRequest> queue;
    private final Thread worker;
    private volatile boolean running = true;

    BatchDispatcher(String modelKey, int maxBatchSize, GenericPredictor predictor) {
        this.modelKey = modelKey;
        this.maxBatchSize = maxBatchSize;
        this.predictor = predictor;
        int capacity = Math.max(maxBatchSize * 4, 32);
        this.queue = new LinkedBlockingQueue<>(capacity);
        String threadName = "ut99-batch-" + modelKey + "-" + (predictor.isUsingGpu() ? "gpu" : "cpu");
        this.worker = Thread.ofPlatform().daemon(true).name(threadName).unstarted(this::runLoop);
        this.worker.setUncaughtExceptionHandler((t, e) ->
            LOG.log(Level.SEVERE, "BatchDispatcher worker died: " + t.getName(), e));
    }

    void start() {
        worker.start();
    }

    /**
     * Submit a single bot's sequence window for inference. Returns a future that
     * completes with the post-truncation output row(s).
     *
     * <p>Returns an already-failed future when the queue is full — caller's
     * submit_timeout_ms catch path handles this like any other backpressure.</p>
     */
    CompletableFuture<FlatResult> submit(float[][] window) {
        CompletableFuture<FlatResult> future = new CompletableFuture<>();
        BatchRequest req = new BatchRequest(window, future);
        if (!queue.offer(req)) {
            future.completeExceptionally(new IllegalStateException(
                "BatchDispatcher queue full for " + modelKey + " (size=" + queue.size() + ")"));
        }
        return future;
    }

    void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void runLoop() {
        while (running) {
            try {
                BatchRequest first = queue.take();
                List<BatchRequest> batch = new ArrayList<>(maxBatchSize);
                batch.add(first);
                queue.drainTo(batch, maxBatchSize - 1);
                dispatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void dispatch(List<BatchRequest> batch) {
        int n = batch.size();
        float[][][] inputs = new float[n][][];
        for (int i = 0; i < n; i++) {
            inputs[i] = batch.get(i).window;
        }
        try {
            FlatResult[] outputs = predictor.predictBatchFull(modelKey, inputs);
            if (outputs.length != n) {
                IllegalStateException err = new IllegalStateException(
                    "predictBatchFull returned " + outputs.length + " rows for batch of " + n);
                for (BatchRequest r : batch) r.future.completeExceptionally(err);
                return;
            }
            for (int i = 0; i < n; i++) {
                batch.get(i).future.complete(outputs[i]);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Batch dispatch failed for " + modelKey + " (batch=" + n + ")", t);
            for (BatchRequest r : batch) r.future.completeExceptionally(t);
        }
    }
}
