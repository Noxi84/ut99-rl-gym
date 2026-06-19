package aiplay.prediction.batch;

import aiplay.prediction.GenericPredictor;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * JVM-wide map of {@link BatchDispatcher}s keyed by (modelKey, ONNX path, device).
 *
 * <p>Dispatchers are created lazily on first access via
 * {@link #getOrCreate}. Bots may bind the same model key to different snapshots
 * (current vs champion), so the ONNX path is part of the key. Requests are only
 * batched together when they resolve to the same model file and device.</p>
 */
public final class BatchDispatcherRegistry {

    private static final Logger LOG = Logger.getLogger(BatchDispatcherRegistry.class.getName());
    private static final ConcurrentHashMap<String, BatchDispatcher> DISPATCHERS = new ConcurrentHashMap<>();

    private BatchDispatcherRegistry() {}

    public static BatchDispatcher getOrCreate(String modelKey, String onnxPath,
                                              int maxBatchSize, GenericPredictor predictor) {
        String key = keyFor(modelKey, onnxPath, predictor);
        return DISPATCHERS.computeIfAbsent(key, k -> {
            LOG.info("BATCH_DISPATCHER_NEW key=" + k + " onnxPath=" + onnxPath
                + " dispatchers_total=" + (DISPATCHERS.size() + 1));
            BatchDispatcher d = new BatchDispatcher(modelKey, maxBatchSize, predictor);
            d.start();
            return d;
        });
    }

    static String keyFor(String modelKey, String onnxPath, GenericPredictor predictor) {
        String normalizedPath = Path.of(onnxPath).toAbsolutePath().normalize().toString();
        return modelKey + ":" + normalizedPath + ":" + (predictor.isUsingGpu() ? "gpu" : "cpu");
    }

    /**
     * Stops all dispatcher threads. Meant for JVM shutdown only — daemon threads
     * will die automatically but explicit shutdown gives a clean interrupt.
     */
    public static void shutdownAll() {
        for (BatchDispatcher d : DISPATCHERS.values()) {
            d.shutdown();
        }
        DISPATCHERS.clear();
    }
}
