package aiplay.prediction.batch;

import ai.onnxruntime.OrtException;
import aiplay.config.global.InferenceBatchingConfig;
import aiplay.prediction.GenericPredictor;
import aiplay.prediction.GenericPredictor.FlatResult;
import aiplay.prediction.ModelSpec;
import aiplay.runtime.port.InferencePort;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator around {@link GenericPredictor} that routes {@code predict()}
 * calls through a JVM-wide {@link BatchDispatcher} when batching is enabled.
 * When disabled (config kill-switch), it's a transparent pass-through.
 *
 * <p>Executors (MovementExecutorAiController, ViewRotationExecutorAiController,
 * ShootingExecutorAiController) need zero changes — this decorator preserves
 * the synchronous {@link InferencePort#predict} signature. Virtual threads on
 * which those executors run park cheaply on {@code future.get(timeout)}.</p>
 */
public final class BatchingInferencePort implements InferencePort {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(BatchingInferencePort.class.getName());
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> DIAG_LOGGED =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final GenericPredictor underlying;
    private final InferenceBatchingConfig cfg;

    public BatchingInferencePort(GenericPredictor underlying, InferenceBatchingConfig cfg) {
        this.underlying = underlying;
        this.cfg = cfg;
    }

    @Override
    public void register(ModelSpec spec) throws OrtException {
        underlying.register(spec);
    }

    @Override
    public boolean isModelAvailable(String modelKey) {
        return underlying.isModelAvailable(modelKey);
    }

    @Override
    public void refreshModel(String modelKey) throws OrtException {
        underlying.refreshModel(modelKey);
    }

    @Override
    public RawPrediction predictRaw(String sessionId,
                                    String modelKey,
                                    float[][][] input) throws OrtException {
        if (!cfg.enabled()) {
            return underlying.predictRaw(sessionId, modelKey, input);
        }
        if (input == null || input.length != 1) {
            throw new IllegalArgumentException("Batched input submit expects [1][T][F], got length " +
                (input == null ? "null" : String.valueOf(input.length)));
        }

        ModelSpec spec = underlying.getSpec(modelKey);
        if (sessionId != null && DIAG_LOGGED.putIfAbsent(sessionId + "|" + modelKey, Boolean.TRUE) == null) {
            LOG.info("BATCH_INFERENCE_PATH sid=" + sessionId + " modelKey=" + modelKey
                + " onnxPath=" + spec.onnxModelPath
                + " predictorId=" + System.identityHashCode(underlying));
        }
        BatchDispatcher dispatcher = BatchDispatcherRegistry.getOrCreate(
            modelKey, spec.onnxModelPath, cfg.maxBatchSize(), underlying);
        CompletableFuture<FlatResult> future = dispatcher.submit(input[0]);

        FlatResult result;
        try {
            result = future.get(cfg.submitTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new OrtException("Inference batch timeout after " + cfg.submitTimeoutMs()
                + " ms for " + modelKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrtException("Interrupted while waiting for batch inference of " + modelKey);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OrtException) throw (OrtException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new OrtException("Batch inference failed for " + modelKey + ": " + cause);
        }

        return new RawPrediction(result.action, result.targetLogits);
    }
}
