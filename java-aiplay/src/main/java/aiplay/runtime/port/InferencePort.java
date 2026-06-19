package aiplay.runtime.port;

import ai.onnxruntime.OrtException;
import aiplay.prediction.ModelSpec;

/**
 * Port for neural network inference. The runtime kernel requests predictions
 * per model key without knowing the inference engine (ONNX, stub, replay).
 *
 * <p>Live adapter: {@code GenericPredictor} (ONNX Runtime with CUDA/CPU dispatch).
 * Test adapter: returns fixed or recorded predictions.</p>
 */
public interface InferencePort {

    record RawPrediction(float[] action, float[] targetLogits) {}

    void register(ModelSpec spec) throws OrtException;

    boolean isModelAvailable(String modelKey);

    RawPrediction predictRaw(String sessionId,
                             String modelKey,
                             float[][][] input) throws OrtException;

    void refreshModel(String modelKey) throws OrtException;
}
