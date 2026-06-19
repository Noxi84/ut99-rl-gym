package aiplay.prediction;

import ai.onnxruntime.*;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.runtime.port.InferencePort;
import aiplay.scanners.model.ITrainingModel;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GenericPredictor implements AutoCloseable, aiplay.runtime.port.InferencePort {

    /* ── Globale ONNX omgeving + sessiecache per modelPath+device ── */
    private static final OrtEnvironment ENV = OrtEnvironment.getEnvironment();
    private static final ConcurrentHashMap<String, OrtSession> SESSION_CACHE = new ConcurrentHashMap<String, OrtSession>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> SESSION_LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final ThreadLocal<FloatBuffer> INPUT_BUFFER =
            ThreadLocal.withInitial(() -> FloatBuffer.allocate(0));
    private static final ExecutorService GPU_NATIVE_EXECUTOR = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("ut99-ort-gpu-", 0).factory()
    );
    private static final boolean CUDA_AVAILABLE = detectCuda();

    private static ReentrantReadWriteLock lockFor(String cacheKey) {
        return SESSION_LOCKS.computeIfAbsent(cacheKey, k -> new ReentrantReadWriteLock());
    }

    @FunctionalInterface
    private interface OrtWork<T> {
        T run() throws OrtException;
    }

    private static final AtomicInteger GPU_QUEUE_DEPTH = new AtomicInteger();
    private static final long GPU_STALL_THRESHOLD_NS = 200_000_000L; // 200ms

    private static <T> T runOrtWork(boolean forcePlatformThread, OrtWork<T> work) throws OrtException {
        if (!forcePlatformThread) {
            return work.run();
        }

        int queued = GPU_QUEUE_DEPTH.incrementAndGet();
        long submitNs = System.nanoTime();
        try {
            return GPU_NATIVE_EXECUTOR.submit(() -> {
                long waitNs = System.nanoTime() - submitNs;
                if (waitNs > GPU_STALL_THRESHOLD_NS) {
                    Logger.getLogger(GenericPredictor.class.getName())
                        .warning("GPU_EXECUTOR_STALL queue_wait_ms=" + (waitNs / 1_000_000)
                            + " depth=" + queued);
                }
                return work.run();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ONNX GPU work", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OrtException) {
                throw (OrtException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Unexpected failure in ONNX GPU work", cause);
        } finally {
            GPU_QUEUE_DEPTH.decrementAndGet();
        }
    }

    /** Per-instance GPU flag. Default true (use GPU if available). */
    private boolean useGpu = true;

    public void setUseGpu(boolean useGpu) {
        this.useGpu = useGpu;
    }

    private boolean shouldUseCuda() {
        return CUDA_AVAILABLE && useGpu;
    }

    private String sessionCacheKey(String onnxPath) {
        return onnxPath + (shouldUseCuda() ? ":gpu" : ":cpu");
    }

    private static boolean detectCuda() {
        try {
            OrtSession.SessionOptions testOpts = new OrtSession.SessionOptions();
            testOpts.addCUDA(0);
            testOpts.close();
            System.out.println("ONNX_RUNTIME: CUDA provider available — GPU inference enabled");
            return true;
        } catch (Exception e) {
            System.out.println("ONNX_RUNTIME: CUDA not available (" + e.getMessage() + ") — using CPU");
            return false;
        }
    }

    private OrtSession.SessionOptions createSessionOptions() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        if (shouldUseCuda()) {
            var cudaOpts = new ai.onnxruntime.providers.OrtCUDAProviderOptions(0);
            cudaOpts.add("gpu_mem_limit", String.valueOf(2L * 1024 * 1024 * 1024));
            cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
            opts.addCUDA(cudaOpts);
        }
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        return opts;
    }

    /* ── Registraties per modelKey ── */
    private final ConcurrentHashMap<String, ModelSpec> specsByKey = new ConcurrentHashMap<String, ModelSpec>();
    private final ConcurrentHashMap<String, RuntimeMeta> metaByKey = new ConcurrentHashMap<String, RuntimeMeta>();

    // log once per session+model
    private static final ConcurrentHashMap<String, Boolean> RUNTIME_LOGGED = new ConcurrentHashMap<String, Boolean>();

    /* ───────────────────────── API ───────────────────────── */

    public void register(ModelSpec spec) throws OrtException {
        Objects.requireNonNull(spec, "ModelSpec is null");
        ModelSpec prev = specsByKey.put(spec.modelKey, spec);
        if (prev != null && !prev.onnxModelPath.equals(spec.onnxModelPath)) {
            Logger.getLogger(GenericPredictor.class.getName()).warning(
                "SPEC_OVERWRITE predictorId=" + System.identityHashCode(this)
                + " modelKey=" + spec.modelKey
                + " oldPath=" + prev.onnxModelPath
                + " newPath=" + spec.onnxModelPath
                + " caller=" + Thread.currentThread().getStackTrace()[2]);
        }
        ensureRuntime(null, spec.modelKey, spec.trainingModel);
    }

    public boolean isModelAvailable(String modelKey) {
        return specsByKey.containsKey(modelKey) && metaByKey.containsKey(modelKey);
    }

    public ModelSpec getSpec(String modelKey) {
        return requireSpec(modelKey);
    }

    public boolean isUsingGpu() {
        return shouldUseCuda();
    }

    @Override
    public InferencePort.RawPrediction predictRaw(String sessionId,
                                                  String modelKey,
                                                  float[][][] input) throws OrtException {
        ModelSpec spec = requireSpec(modelKey);
        RuntimeMeta m = ensureRuntime(sessionId, modelKey, spec.trainingModel);
        float[][][] adapted = adaptSeq(input, m.expectedT, m.expectedF);
        FlatResult result = runFlatFull(sessionId, spec, m, adapted);
        return new InferencePort.RawPrediction(result.action, result.targetLogits);
    }

    /**
     * Batched inference variant that preserves all model outputs. Full-joint
     * policy uses output[0]=actions and output[1]=target_logits.
     */
    public FlatResult[] predictBatchFull(String modelKey, float[][][] batchedInputs) throws OrtException {
        if (batchedInputs == null || batchedInputs.length == 0) {
            throw new IllegalArgumentException("Batched input is null or empty");
        }
        ModelSpec spec = requireSpec(modelKey);
        RuntimeMeta m = ensureRuntime(null, modelKey, spec.trainingModel);

        float[][][] adapted = adaptBatch(batchedInputs, m.expectedT, m.expectedF);
        return runFlatBatchFull(spec, m, adapted);
    }

    /**
     * Swap this predictor's {@link ModelSpec} for {@code modelKey} to a new
     * ONNX path and install a fresh {@link OrtSession} for the new path,
     * leaving the old session alone.
     *
     * <p>Used by {@link aiplay.rl.champion.ChampionNewestWatcher} when a
     * PROMOTE on the trainer side advances {@code bundles.json}'s pool[0]
     * counter — the watcher resolves the new snapshot dir via
     * {@link aiplay.rl.champion.SnapshotResolver#resolve(String, String)},
     * which validates fingerprints, then calls this on every registered
     * predictor for the affected model.
     *
     * <p><b>Why we don't close the old session here:</b> {@link #SESSION_CACHE}
     * is JVM-static and shared across every predictor instance for the same
     * onnx path. Multiple bots in this JVM may still hold a spec pointing at
     * the old path until their own swap fires (the watcher walks predictors
     * sequentially). Closing the old session inside this method would race
     * those bots' in-flight predicts. Cleanup is the watcher's responsibility
     * via {@link #closeAbandonedSession(String)} once it has swapped every
     * registered predictor.
     *
     * <p>Install order: cache → meta → spec. Any prediction call that reads
     * the new spec mid-swap must already find a session in the cache for
     * the new path; reading the old spec is safe because the old session
     * has not been touched.
     */
    public void replaceAndRefresh(String modelKey, String newOnnxPath) throws OrtException {
        ModelSpec oldSpec = requireSpec(modelKey);
        if (newOnnxPath.equals(oldSpec.onnxModelPath)) {
            // Idempotent no-op: spec already points at the requested path.
            // Champion snapshots are immutable on disk, so a same-path call
            // means a previous swap (or a retry pass from
            // ChampionNewestWatcher's partial-failure recovery) already
            // installed this session. Re-creating it would just churn the
            // ORT session for no behavior change.
            return;
        }
        String newCacheKey = sessionCacheKey(newOnnxPath);

        runOrtWork(shouldUseCuda(), () -> {
            ReentrantReadWriteLock newLock = lockFor(newCacheKey);
            newLock.writeLock().lock();
            OrtSession freshLocal = null;
            try {
                OrtSession existing = SESSION_CACHE.get(newCacheKey);
                OrtSession session;
                if (existing != null) {
                    session = existing;
                } else {
                    freshLocal = ENV.createSession(newOnnxPath, createSessionOptions());
                    session = freshLocal;
                }

                int[] tf = readInputShape(session);
                int expectedF = oldSpec.featureOrder.size();
                if (tf[1] != expectedF) {
                    throw new OrtException("Featurecount mismatch on champion swap: ONNX F=" + tf[1]
                            + " vs Java expectedF=" + expectedF
                            + " (modelKey=" + modelKey + " newOnnxPath=" + newOnnxPath + ")");
                }

                ModelSpec newSpec = new ModelSpec(oldSpec.trainingModel, newOnnxPath);
                RuntimeMeta newMeta = RuntimeMeta.fromSession(session);

                if (freshLocal != null) {
                    SESSION_CACHE.put(newCacheKey, freshLocal);
                    freshLocal = null;
                }
                metaByKey.put(modelKey, newMeta);
                specsByKey.put(modelKey, newSpec);
            } catch (Exception e) {
                if (freshLocal != null) {
                    try { freshLocal.close(); } catch (Exception ignore) {}
                }
                throw e;
            } finally {
                newLock.writeLock().unlock();
            }
            return null;
        });
    }

    /**
     * Drain in-flight predicts on the cacheKey derived from {@code onnxPath}
     * (both :gpu and :cpu variants) by taking the write-lock, then remove +
     * close the session. Called by {@link aiplay.rl.champion.ChampionNewestWatcher}
     * after every registered predictor has swapped off the old path.
     *
     * <p>Idempotent and silent — missing/already-closed sessions are no-ops.
     */
    public static void closeAbandonedSession(String onnxPath) {
        for (String suffix : new String[]{":gpu", ":cpu"}) {
            String cacheKey = onnxPath + suffix;
            ReentrantReadWriteLock lock = SESSION_LOCKS.get(cacheKey);
            if (lock == null) {
                // No predictor ever opened this path with this device — nothing to do.
                continue;
            }
            // Use platform-thread executor unconditionally; the GPU variant
            // requires it and the CPU variant is cheap enough.
            try {
                runOrtWork(true, () -> {
                    lock.writeLock().lock();
                    try {
                        OrtSession old = SESSION_CACHE.remove(cacheKey);
                        if (old != null) {
                            try {
                                old.close();
                            } catch (Exception ignore) {
                            }
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                    return null;
                });
            } catch (OrtException ignore) {
                // Closing should never fail; if it does there's nothing to recover.
            }
        }
    }

    public void refreshModel(String modelKey) throws OrtException {
        ModelSpec spec = requireSpec(modelKey);
        String cacheKey = sessionCacheKey(spec.onnxModelPath);
        runOrtWork(shouldUseCuda(), () -> {
            ReentrantReadWriteLock lock = lockFor(cacheKey);
            lock.writeLock().lock();
            try {
                OrtSession fresh = ENV.createSession(spec.onnxModelPath, createSessionOptions());
                try {
                    int[] tf = readInputShape(fresh);
                    int expectedF = spec.featureOrder.size();
                    if (tf[1] != expectedF) {
                        throw new IllegalStateException(
                                "Featurecount mismatch: ONNX F=" + tf[1]
                                        + " vs Java expectedF=" + expectedF
                        );
                    }
                    OrtSession old = SESSION_CACHE.put(cacheKey, fresh);
                    if (old != null && old != fresh) {
                        try { old.close(); } catch (Exception ignore) {}
                    }
                    metaByKey.put(spec.modelKey, RuntimeMeta.fromSession(fresh));
                } catch (Exception e) {
                    try { fresh.close(); } catch (Exception ignore) {}
                    throw e;
                }
            } finally {
                lock.writeLock().unlock();
            }
            return null;
        });
    }

    @Override
    public void close() {
        // laat cache staan (kan gedeeld zijn). Desgewenst kun je shutdownAll() aanroepen bij app-exit.
    }

    public static void shutdownAll() {
        try {
            runOrtWork(true, () -> {
                for (OrtSession s : SESSION_CACHE.values()) {
                    try {
                        s.close();
                    } catch (Exception ignore) {
                    }
                }
                SESSION_CACHE.clear();
                SESSION_LOCKS.clear();
                ENV.close();
                return null;
            });
        } catch (Exception ignore) {
        } finally {
            GPU_NATIVE_EXECUTOR.shutdown();
        }
    }

    /* ───────────────────── Internals ───────────────────── */

    private ModelSpec requireSpec(String key) {
        ModelSpec s = specsByKey.get(key);
        if (s == null) throw new IllegalStateException("Onbekend modelKey: " + key);
        return s;
    }

    private RuntimeMeta ensureRuntime(String sessionId, String modelKey, ITrainingModel trainingModel) throws OrtException {
        ModelSpec spec = requireSpec(modelKey);
        String cacheKey = sessionCacheKey(spec.onnxModelPath);

        // Fast path: session and meta already exist
        RuntimeMeta meta = metaByKey.get(modelKey);
        if (meta != null && SESSION_CACHE.containsKey(cacheKey)) {
            logRuntimeOnce(sessionId, modelKey, meta, trainingModel);
            return meta;
        }

        RuntimeMeta m = runOrtWork(shouldUseCuda(), () -> {
            ReentrantReadWriteLock lock = lockFor(cacheKey);
            lock.writeLock().lock();
            try {
                RuntimeMeta existingMeta = metaByKey.get(modelKey);
                OrtSession session = SESSION_CACHE.get(cacheKey);
                if (existingMeta != null && session != null) {
                    return existingMeta;
                }

                if (session == null) {
                    OrtSession fresh = ENV.createSession(spec.onnxModelPath, createSessionOptions());
                    OrtSession existing = SESSION_CACHE.putIfAbsent(cacheKey, fresh);
                    session = (existing != null) ? existing : fresh;
                    if (existing != null) {
                        try {
                            fresh.close();
                        } catch (Exception ignore) {
                        }
                    }
                }

                int[] tf = readInputShape(session);
                int expectedF = spec.featureOrder.size();
                if (tf[1] != expectedF) {
                    throw new IllegalStateException(
                            "Featurecount mismatch: ONNX F=" + tf[1]
                                    + " vs Java expectedF=" + expectedF
                    );
                }

                RuntimeMeta resolved = RuntimeMeta.fromSession(session);
                metaByKey.put(modelKey, resolved);
                return resolved;
            } finally {
                lock.writeLock().unlock();
            }
        });

        logRuntimeOnce(sessionId, modelKey, m, trainingModel);
        return m;
    }

    private void logRuntimeOnce(String sessionId, String modelKey, RuntimeMeta m, ITrainingModel trainingModel) {
        if (sessionId == null || sessionId.isBlank()) return;

        String k = sessionId + "|" + modelKey + "|runtime";
        Boolean prev = RUNTIME_LOGGED.putIfAbsent(k, Boolean.TRUE);
        if (prev != null) return;

        ModelSpec spec = specsByKey.get(modelKey);
        String onnxPath = (spec != null) ? spec.onnxModelPath : "UNKNOWN";
        for (String loggerKey : trainingModel.getTrainingModelLogger().getLogFiles()) {
            Logger log = modelLogger(sessionId, loggerKey);
            if (log == null) return;

            log.info("PRED_RUNTIME modelKey=" + modelKey +
                    " onnxPath=" + onnxPath +
                    " inputName=" + m.inputName +
                    " shape=[1," + m.expectedT + "," + m.expectedF + "]" +
                    " outputs=" + m.outputNames);
        }
    }

    private static int[] readInputShape(OrtSession s) throws OrtException {
        TensorInfo ti = (TensorInfo) s.getInputInfo().values().iterator().next().getInfo();
        long[] shape = ti.getShape();
        int T = (int) shape[shape.length - 2];
        int F = (int) shape[shape.length - 1];
        return new int[]{T, F};
    }

    private float[][][] adaptSeq(float[][][] in, int expectedT, int expectedF) {
        if (in == null || in.length != 1) throw new IllegalArgumentException("Input moet [1][T][F] zijn.");
        int T = in[0].length;
        int F = in[0][0].length;

        if (F != expectedF) {
            throw new IllegalArgumentException("Feature len mismatch: got " + F + " expected " + expectedF);
        }
        if (T == expectedT) {
            return in;
        }

        float[][][] out = new float[1][expectedT][expectedF];
        if (T >= expectedT) {
            System.arraycopy(in[0], T - expectedT, out[0], 0, expectedT);
        } else {
            for (int i = 0; i < expectedT - T; i++) {
                out[0][i] = Arrays.copyOf(in[0][0], expectedF);
            }
            System.arraycopy(in[0], 0, out[0], expectedT - T, T);
        }
        return out;
    }

    private float[][][] adaptBatch(float[][][] in, int expectedT, int expectedF) {
        if (in == null || in.length == 0) {
            throw new IllegalArgumentException("Input moet [N][T][F] zijn.");
        }

        int n = in.length;
        boolean alreadyExact = true;
        for (int b = 0; b < n; b++) {
            if (in[b] == null || in[b].length == 0) {
                throw new IllegalArgumentException("Batch row " + b + " is leeg.");
            }
            int T = in[b].length;
            int F = in[b][0].length;
            if (F != expectedF) {
                throw new IllegalArgumentException("Feature len mismatch: got " + F + " expected " + expectedF);
            }
            if (T != expectedT) {
                alreadyExact = false;
            }
        }
        if (alreadyExact) {
            return in;
        }

        float[][][] out = new float[n][expectedT][expectedF];
        for (int b = 0; b < n; b++) {
            int T = in[b].length;
            if (T >= expectedT) {
                System.arraycopy(in[b], T - expectedT, out[b], 0, expectedT);
            } else {
                for (int i = 0; i < expectedT - T; i++) {
                    out[b][i] = Arrays.copyOf(in[b][0], expectedF);
                }
                System.arraycopy(in[b], 0, out[b], expectedT - T, T);
            }
        }
        return out;
    }

    /** Result wrapper for single-frame inference. Holds primary action vector
     *  plus optional Phase-2 target_logits from the shooting model's target_head
     *  (null when ONNX has only one output). */
    public static final class FlatResult {
        public final float[] action;
        public final float[] targetLogits;
        FlatResult(float[] action, float[] targetLogits) {
            this.action = action;
            this.targetLogits = targetLogits;
        }
    }

    private FlatResult runFlatFull(String sessionId, ModelSpec spec, RuntimeMeta m, float[][][] input) throws OrtException {
        int totalFloats = m.expectedT * m.expectedF;
        FloatBuffer buf = reusableInputBuffer(totalFloats);

        int anomalies = 0;
        for (int t = 0; t < m.expectedT; t++) {
            float[] row = input[0][t];
            buf.put(row, 0, m.expectedF);
            for (int f = 0; f < m.expectedF; f++) {
                if (!Float.isFinite(row[f])) {
                    buf.put(buf.position() - m.expectedF + f, 0f);
                    anomalies++;
                }
            }
        }
        buf.flip();

        if (anomalies > 0) {
            Logger log = modelLogger(sessionId, spec.modelKey);
            if (log != null) {
                log.warning("PRED_ANOMALY modelKey=" + spec.modelKey + " nonFiniteCount=" + anomalies + " ->0");
            }
        }

        String cacheKey = sessionCacheKey(spec.onnxModelPath);
        return runOrtWork(shouldUseCuda(), () -> {
            ReentrantReadWriteLock lock = lockFor(cacheKey);

            // readLock allows concurrent inference; writeLock (in refreshModel) blocks
            // inference only during the brief session swap, not during model loading.
            lock.readLock().lock();
            try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, buf, new long[]{1, m.expectedT, m.expectedF})) {
                OrtSession session = SESSION_CACHE.get(cacheKey);
                if (session == null) {
                    throw new IllegalStateException("Geen sessie in cache voor " + spec.onnxModelPath);
                }

                try (OrtSession.Result out = session.run(Collections.singletonMap(m.inputName, tensor))) {
                    OnnxValue v0 = out.get(0);
                    Object val = v0.getValue();
                    float[] vals;

                    if (val instanceof float[][]) {
                        float[] row = ((float[][]) val)[0];
                        vals = row;
                    } else if (val instanceof float[]) {
                        vals = (float[]) val;
                    } else {
                        throw new IllegalStateException("Onverwacht output type: " + val.getClass());
                    }

                    if (spec.targetOrder != null && !spec.targetOrder.isEmpty()) {
                        if (vals.length < spec.targetOrder.size()) {
                            throw new IllegalStateException("Output too short for " + spec.modelKey +
                                    ": onnxOutLen=" + vals.length + " vs targetOrder=" + spec.targetOrder.size());
                        }
                        if (vals.length > spec.targetOrder.size()) {
                            vals = Arrays.copyOf(vals, spec.targetOrder.size());
                        }
                    }

                    float[] targetLogits = null;
                    if (out.size() > 1) {
                        OnnxValue v1 = out.get(1);
                        Object tval = v1.getValue();
                        if (tval instanceof float[][]) {
                            targetLogits = ((float[][]) tval)[0];
                        } else if (tval instanceof float[]) {
                            targetLogits = (float[]) tval;
                        }
                    }

                    return new FlatResult(vals, targetLogits);
                }
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    private FlatResult[] runFlatBatchFull(ModelSpec spec, RuntimeMeta m, float[][][] inputs) throws OrtException {
        int n = inputs.length;
        int anomalies = 0;
        FloatBuffer buf = reusableInputBuffer(n * m.expectedT * m.expectedF);

        for (int b = 0; b < n; b++) {
            for (int t = 0; t < m.expectedT; t++) {
                float[] row = inputs[b][t];
                buf.put(row, 0, m.expectedF);
                for (int f = 0; f < m.expectedF; f++) {
                    if (!Float.isFinite(row[f])) {
                        buf.put(buf.position() - m.expectedF + f, 0f);
                        anomalies++;
                    }
                }
            }
        }
        buf.flip();

        if (anomalies > 0) {
            Logger log = modelLogger(null, spec.modelKey);
            if (log != null) {
                log.warning("PRED_ANOMALY_BATCH modelKey=" + spec.modelKey
                        + " batchSize=" + n + " nonFiniteCount=" + anomalies + " ->0");
            }
        }

        String cacheKey = sessionCacheKey(spec.onnxModelPath);
        return runOrtWork(shouldUseCuda(), () -> {
            ReentrantReadWriteLock lock = lockFor(cacheKey);
            lock.readLock().lock();
            try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, buf, new long[]{n, m.expectedT, m.expectedF})) {
                OrtSession session = SESSION_CACHE.get(cacheKey);
                if (session == null) {
                    throw new IllegalStateException("Geen sessie in cache voor " + spec.onnxModelPath);
                }

                try (OrtSession.Result out = session.run(Collections.singletonMap(m.inputName, tensor))) {
                    OnnxValue v0 = out.get(0);
                    Object val = v0.getValue();

                    float[][] actionRows = requireBatchRows(val, n, "actions");
                    float[][] targetRows = null;
                    if (out.size() > 1) {
                        OnnxValue v1 = out.get(1);
                        targetRows = requireBatchRows(v1.getValue(), n, "target_logits");
                    }

                    FlatResult[] result = new FlatResult[n];
                    int targetLen = (spec.targetOrder != null) ? spec.targetOrder.size() : 0;
                    for (int i = 0; i < n; i++) {
                        float[] row = actionRows[i];
                        if (targetLen > 0) {
                            if (row.length < targetLen) {
                                throw new IllegalStateException("Output too short for " + spec.modelKey
                                        + ": onnxOutLen=" + row.length + " vs targetOrder=" + targetLen);
                            }
                            if (row.length > targetLen) {
                                row = Arrays.copyOf(row, targetLen);
                            }
                        }
                        float[] targetLogits = targetRows != null ? targetRows[i] : null;
                        result[i] = new FlatResult(row, targetLogits);
                    }
                    return result;
                }
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    private static float[][] requireBatchRows(Object value, int expectedRows, String outputName) {
        if (value instanceof float[][] rows) {
            if (rows.length != expectedRows) {
                throw new IllegalStateException("Batch size mismatch for " + outputName
                        + ": expected " + expectedRows + " rows, got " + rows.length);
            }
            return rows;
        }
        if (value instanceof float[] row && expectedRows == 1) {
            return new float[][]{row};
        }
        throw new IllegalStateException("Onverwacht output type voor batch " + outputName
                + ": " + value.getClass());
    }

    private static FloatBuffer reusableInputBuffer(int floats) {
        FloatBuffer buf = INPUT_BUFFER.get();
        if (buf.capacity() < floats) {
            buf = FloatBuffer.allocate(floats);
            INPUT_BUFFER.set(buf);
        }
        buf.clear();
        buf.limit(floats);
        return buf;
    }

    private Logger modelLogger(String sessionId, String modelKey) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Logger l = SessionRollingLogger.get(sessionId, SessionLogPaths.featureLog(modelKey));
        l.setLevel(Level.INFO);
        return l;
    }

    /* ───────────────────── RuntimeMeta ───────────────────── */

    private static final class RuntimeMeta {
        private final String inputName;
        private final int expectedT;
        private final int expectedF;
        private final List<String> outputNames;

        private RuntimeMeta(String inputName, int expectedT, int expectedF, List<String> outputNames) {
            this.inputName = inputName;
            this.expectedT = expectedT;
            this.expectedF = expectedF;
            this.outputNames = outputNames;
        }

        private static RuntimeMeta fromSession(OrtSession session) throws OrtException {
            String in = session.getInputInfo().keySet().iterator().next();
            TensorInfo ti = (TensorInfo) session.getInputInfo().values().iterator().next().getInfo();
            long[] shape = ti.getShape();
            int T = (int) shape[shape.length - 2];
            int F = (int) shape[shape.length - 1];

            List<String> outs = new ArrayList<String>();
            for (String k : session.getOutputInfo().keySet()) outs.add(k);

            return new RuntimeMeta(in, T, F, Collections.unmodifiableList(outs));
        }
    }
}
