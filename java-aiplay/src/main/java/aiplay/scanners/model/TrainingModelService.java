package aiplay.scanners.model;

import ai.onnxruntime.OrtException;
import aiplay.prediction.ModelSpec;
import aiplay.runtime.port.InferencePort;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public class TrainingModelService {

  private static final Logger LOG = Logger.getLogger(TrainingModelService.class.getName());

  private static final String BASE_PACKAGE = "aiplay.scanners.model.resolver";

  private final List<ITrainingModel> resolvers;

  public TrainingModelService() {
    this.resolvers = List.copyOf(scanResolvers());
  }

  private static List<ITrainingModel> scanResolvers() {
    List<ITrainingModel> list = new ArrayList<>();

    try (ScanResult scan = new ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages(BASE_PACKAGE)
        .scan()) {

      for (ClassInfo ci : scan.getClassesWithAnnotation(TrainingModelComponent.class.getName())) {
        Class<?> raw = ci.loadClass();

        if (!ITrainingModel.class.isAssignableFrom(raw)) {
          continue;
        }

        @SuppressWarnings("unchecked")
        Class<? extends ITrainingModel> clazz = (Class<? extends ITrainingModel>) raw;

        Constructor<? extends ITrainingModel> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        ITrainingModel instance = ctor.newInstance();
        list.add(instance);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to scan feature resolvers in package: " + BASE_PACKAGE, e);
    }

    list.sort(Comparator.comparingInt(TrainingModelService::priorityOf));
    return list;
  }

  private static int priorityOf(ITrainingModel r) {
    TrainingModelComponent ann = r.getClass().getAnnotation(TrainingModelComponent.class);
    return ann != null ? ann.priority() : 100;
  }

  public void createTrainingCsvFiles(String sessionId, String modelKey) {
    for (ITrainingModel r : resolvers) {
      if (r.getModelKey().equalsIgnoreCase(modelKey) && r.getTrainingModelCsvWriter() != null && r.getTrainingModelCsvWriter().isEnabled()) {
        r.getTrainingModelCsvWriter().createTrainingCsvFileStreaming(sessionId);
      }
    }
  }

  public void createTrainingCsvFilesWithOutputDir(String sessionId, String modelKey, String outputDir) {
    for (ITrainingModel r : resolvers) {
      if (r.getModelKey().equalsIgnoreCase(modelKey) && r.getTrainingModelCsvWriter() != null && r.getTrainingModelCsvWriter().isEnabled()) {
        r.getTrainingModelCsvWriter().createTrainingCsvFileStreamingWithOutputDir(sessionId, outputDir);
      }
    }
  }

  public void createTrainingCsvFilesDistributed(String sessionId, String modelKey,
                                                 String sourceDir, java.util.List<String> zipNames,
                                                 String outputDir) {
    createTrainingCsvFilesDistributed(sessionId, modelKey, sourceDir, zipNames, outputDir, null, null);
  }

  public void createTrainingCsvFilesDistributed(String sessionId, String modelKey,
                                                 String sourceDir, java.util.List<String> zipNames,
                                                 String outputDir, String runId, String shardId) {
    for (ITrainingModel r : resolvers) {
      if (r.getModelKey().equalsIgnoreCase(modelKey) && r.getTrainingModelCsvWriter() != null && r.getTrainingModelCsvWriter().isEnabled()) {
        r.getTrainingModelCsvWriter().createTrainingCsvFileStreamingDistributed(
            sessionId, sourceDir, zipNames, outputDir, runId, shardId);
      }
    }
  }

  public void registerModels(InferencePort predictor) throws OrtException {
    for (ITrainingModel r : resolvers) {
      try {
        predictor.register(new ModelSpec(r));
      } catch (IllegalStateException e) {
        LOG.warning("MODEL_REGISTER_SKIP model=" + r.getModelKey() + " reason=" + e.getMessage());
      } catch (Exception e) {
        LOG.warning("MODEL_REGISTER_FAIL model=" + r.getModelKey() + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
  }

  public List<String> getModelKeys() {
    return resolvers.stream().map(ITrainingModel::getModelKey).toList();
  }

  public int getMaxCsvFps() {
    int max = 0;
    for (ITrainingModel r : resolvers) {
      if (r.getCsvFPS() > max) {
        max = r.getCsvFPS();
      }
    }
    return max;
  }

  public int getMaxCsvNumberOfColumns() {
    int max = 0;
    for (ITrainingModel r : resolvers) {
      if (r.getCsvNumberOfColumns() > max) {
        max = r.getCsvNumberOfColumns();
      }
    }
    return max;
  }
}
