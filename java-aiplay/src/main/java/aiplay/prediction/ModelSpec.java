package aiplay.prediction;

import aiplay.scanners.model.ITrainingModel;
import java.io.File;
import java.util.List;
import java.util.Objects;

public final class ModelSpec {
    public final ITrainingModel trainingModel;
    public final String modelKey;             // unieke naam (bv. "movement", "viewrotation")
    public final String onnxModelPath;        // bestandslocatie
    public final List<String> featureOrder;   // input feature volgorde per frame
    public final List<String> targetOrder;    // ONNX output volgorde
    public final double fps;                  // basis-fps van training CSV

  /**
   * Standard constructor — resolves ONNX path from SessionPaths.
   */
    public ModelSpec(ITrainingModel r) {
      this(r, aiplay.runtime.config.SessionPaths.getModelTrainingDir() + "/" + r.getModelKey() + ".onnx");
    }

  /**
   * Constructor with explicit ONNX path — used for per-instance binding overrides.
   */
  public ModelSpec(ITrainingModel r, String onnxModelPath) {
        this(r, r.getModelKey(), onnxModelPath);
  }

  /**
   * Constructor with explicit predictor key + ONNX path — used when champion
   * and current bots share a GenericPredictor but need separate ONNX sessions
   * (e.g. "rl_pawn" for current, "rl_pawn@0143" for a champion snapshot).
   */
  public ModelSpec(ITrainingModel r, String predictorKey, String onnxModelPath) {
        this.trainingModel = r;
        this.modelKey = Objects.requireNonNull(predictorKey);
    this.onnxModelPath = Objects.requireNonNull(onnxModelPath);
        this.featureOrder = Objects.requireNonNull(r.getInputFeatures());
        this.targetOrder = Objects.requireNonNull(r.getTargetFeatures());
        this.fps = r.getCsvFPS() > 0 ? r.getCsvFPS() : 60.0;

        if (!new File(this.onnxModelPath).exists()) {
            throw new IllegalStateException("Model file '" + onnxModelPath + " bestaat niet.");
        }
    }
}
