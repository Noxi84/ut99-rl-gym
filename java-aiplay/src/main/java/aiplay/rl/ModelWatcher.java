package aiplay.rl;

import aiplay.prediction.GenericPredictor;
import aiplay.prediction.ModelSpec;
import aiplay.scanners.model.ITrainingModel;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background daemon thread that polls an ONNX model file's modification time. When the file changes, triggers GenericPredictor.refreshModel() for hot-swap. If the model was never registered (ONNX didn't exist at startup), registers it first.
 */
public class ModelWatcher implements Runnable {

  private static final Logger LOG = Logger.getLogger(ModelWatcher.class.getName());
  private static final long DEFAULT_POLL_INTERVAL_MS = 5_000;
  /**
   * Wait for file mtime to stabilize before loading — prevents SIGBUS from rsync mid-write.
   */
  private static final long STABILITY_WAIT_MS = 2_000;
  private static final int STABILITY_CHECKS = 3;

  private final String modelKey;
  private final String onnxFilePath;
  private final GenericPredictor predictor;
  private final long pollIntervalMs;
  private final ITrainingModel trainingModel;

  private volatile boolean running = true;
  private long lastModified = 0;

  /**
   * Shared last-refresh timestamp per onnx path — prevents 20 watchers from all refreshing the same file.
   */
  private static final ConcurrentHashMap<String, Long> LAST_REFRESHED = new ConcurrentHashMap<>();

  public ModelWatcher(String modelKey, String onnxFilePath, GenericPredictor predictor, ITrainingModel trainingModel) {
    this(modelKey, onnxFilePath, predictor, trainingModel, DEFAULT_POLL_INTERVAL_MS);
  }

  public ModelWatcher(String modelKey, String onnxFilePath, GenericPredictor predictor, ITrainingModel trainingModel, long pollIntervalMs) {
    this.modelKey = modelKey;
    this.onnxFilePath = onnxFilePath;
    this.predictor = predictor;
    this.trainingModel = trainingModel;
    this.pollIntervalMs = pollIntervalMs;
  }

  @Override
  public void run() {
    LOG.info("MODEL_WATCHER started for " + modelKey + " -> " + onnxFilePath);

    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        checkAndRefresh();
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        LOG.log(Level.WARNING, "MODEL_WATCHER error for " + modelKey, e);
      }
    }

    LOG.info("MODEL_WATCHER stopped for " + modelKey);
  }

  void checkAndRefresh() {
    File file = new File(onnxFilePath);
    if (!file.exists()) {
      return;
    }

    long currentModified = file.lastModified();
    if (currentModified == lastModified) {
      return;
    }

    // Early-out: another watcher already refreshed this mtime.
    Long alreadyRefreshed = LAST_REFRESHED.get(onnxFilePath);
    if (alreadyRefreshed != null && alreadyRefreshed.longValue() == currentModified) {
      // Only advance lastModified once our own predictor actually holds the
      // spec. If late-registration loses the multi-watcher race here, leave
      // lastModified untouched so the next poll retries — otherwise this mtime
      // is marked done forever and this bot's predictor stays spec-less
      // ("Onbekend modelKey" on every frame).
      if (lateRegisterIfNeeded()) {
        lastModified = currentModified;
      }
      return;
    }

    if (lastModified > 0) {
      LOG.info("MODEL_WATCHER detected change for " + modelKey
          + " -> " + onnxFilePath
          + " (mtime " + lastModified + " -> " + currentModified + ")");
    } else {
      LOG.info("MODEL_WATCHER detected new model file for " + modelKey
          + " -> " + onnxFilePath);
    }

    if (!waitForStableFiles(file)) {
      LOG.info("MODEL_WATCHER file still changing for " + modelKey + ", deferring to next poll");
      return;
    }

    // After stability wait — atomically claim this mtime. ConcurrentHashMap.put()
    // returns the previous value; if another watcher set currentModified during
    // our stability wait, skip the expensive GPU refresh.
    Long previousClaim = LAST_REFRESHED.put(onnxFilePath, currentModified);
    if (previousClaim != null && previousClaim.longValue() == currentModified) {
      // Same guard as the early-out above: don't burn this mtime unless our
      // predictor is actually registered.
      if (lateRegisterIfNeeded()) {
        lastModified = currentModified;
      }
      LOG.info("MODEL_WATCHER skipped refresh for " + modelKey
          + " (another watcher already refreshed this mtime)");
      return;
    }

    try {
      lateRegisterIfNeeded();
      long t0 = System.nanoTime();
      predictor.refreshModel(modelKey);
      long ms = (System.nanoTime() - t0) / 1_000_000;
      LOG.info("MODEL_WATCHER refreshed " + modelKey + " from "
          + onnxFilePath + " successfully (" + ms + " ms)");
      lastModified = currentModified;
    } catch (Exception e) {
      if (previousClaim != null) {
        LAST_REFRESHED.replace(onnxFilePath, currentModified, previousClaim);
      } else {
        LAST_REFRESHED.remove(onnxFilePath, currentModified);
      }
      LOG.log(Level.WARNING, "MODEL_WATCHER refresh failed for " + modelKey
          + " (will retry on next poll)", e);
    }
  }

  /**
   * Ensure this watcher's predictor holds the model spec. Returns whether the
   * model is available afterwards so callers can gate {@code lastModified} on a
   * confirmed registration (see the skip-branches in {@link #checkAndRefresh}).
   * Returning the post-register availability — rather than swallowing the
   * outcome — is what makes a lost registration retryable instead of permanent.
   */
  private boolean lateRegisterIfNeeded() {
    if (predictor.isModelAvailable(modelKey)) {
      return true;
    }
    if (trainingModel == null) {
      return false;
    }
    try {
      predictor.register(new ModelSpec(trainingModel, onnxFilePath));
      LOG.info("MODEL_WATCHER late-registered model " + modelKey
          + " from " + onnxFilePath);
      return predictor.isModelAvailable(modelKey);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "MODEL_WATCHER late-register failed for " + modelKey, e);
      return false;
    }
  }

  /**
   * Wait until both the .onnx file and its companion .onnx.data file have stable mtime + size for STABILITY_CHECKS consecutive checks. This prevents SIGBUS crashes when ONNX Runtime memory-maps a .data file that rsync is still writing.
   */
  private boolean waitForStableFiles(File onnxFile) {
    File dataFile = new File(onnxFile.getPath() + ".data");
    long sleepPerCheck = STABILITY_WAIT_MS / STABILITY_CHECKS;
    int stableCount = 0;
    long prevOnnxMtime = onnxFile.lastModified();
    long prevOnnxSize = onnxFile.length();
    long prevDataMtime = dataFile.exists() ? dataFile.lastModified() : -1;
    long prevDataSize = dataFile.exists() ? dataFile.length() : -1;

    while (stableCount < STABILITY_CHECKS) {
      try {
        Thread.sleep(sleepPerCheck);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      long curOnnxMtime = onnxFile.lastModified();
      long curOnnxSize = onnxFile.length();
      long curDataMtime = dataFile.exists() ? dataFile.lastModified() : -1;
      long curDataSize = dataFile.exists() ? dataFile.length() : -1;

      if (curOnnxMtime == prevOnnxMtime && curOnnxSize == prevOnnxSize
          && curDataMtime == prevDataMtime && curDataSize == prevDataSize) {
        stableCount++;
      } else {
        stableCount = 0;
        prevOnnxMtime = curOnnxMtime;
        prevOnnxSize = curOnnxSize;
        prevDataMtime = curDataMtime;
        prevDataSize = curDataSize;
      }
    }
    return true;
  }

  public void stop() {
    running = false;
  }

  public boolean isRunning() {
    return running;
  }

  /**
   * Start the watcher as a daemon thread.
   */
  public Thread startDaemon() {
    Thread t = Thread.ofVirtual()
        .name("ModelWatcher-" + modelKey)
        .start(this);
    return t;
  }
}
