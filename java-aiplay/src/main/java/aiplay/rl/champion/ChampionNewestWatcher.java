package aiplay.rl.champion;

import aiplay.prediction.GenericPredictor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hot-swap watcher for bots configured with {@code "<mk>/newest"} snapshot
 * specs. Polls {@code champions/bundles.json} for mtime changes; when the
 * newest promoted counter for a registered model advances, resolves the new
 * snapshot via {@link SnapshotResolver#resolve(String, String)} (which
 * hard-validates fingerprints) and calls
 * {@link GenericPredictor#replaceAndRefresh(String, String)} on every
 * registered predictor for that model. After all swaps complete, the old
 * session is drained and closed via
 * {@link GenericPredictor#closeAbandonedSession(String)}.
 *
 * <p>Pinned-counter specs ({@code "<mk>/0007"}) and live-policy bots
 * ({@code "current"}) deliberately do not register here — they use the
 * existing {@link aiplay.rl.ModelWatcher} (live) or stay frozen until
 * bot restart (pinned).
 *
 * <p>Single JVM-wide daemon thread, lazy-started on first {@link #register}.
 * Multiple bots in the same JVM share this watcher so a single bundles.json
 * change triggers exactly one resolve + sequential predictor swaps.
 */
public final class ChampionNewestWatcher {

    private static final Logger LOG = Logger.getLogger(ChampionNewestWatcher.class.getName());
    private static final long DEFAULT_POLL_INTERVAL_MS = 5_000;
    /**
     * Wait for the bundles.json + snapshot files to stabilize before swapping —
     * the trainer's PROMOTE rsyncs both bundles.json and the new snapshot dir
     * (incl. {@code .onnx} + {@code .onnx.data}) non-atomically. Loading a
     * half-rsynced ONNX would crash with SIGBUS once ORT memory-maps it.
     */
    private static final long STABILITY_WAIT_MS = 2_000;
    private static final int STABILITY_CHECKS = 3;

    private static final ChampionNewestWatcher INSTANCE = new ChampionNewestWatcher();

    private final Map<String, ModelState> modelStates = new HashMap<>();
    private final long pollIntervalMs;
    private final Object stateLock = new Object();

    private volatile Thread daemon = null;
    private long lastBundlesMtime = 0L;

    private ChampionNewestWatcher() {
        this(DEFAULT_POLL_INTERVAL_MS);
    }

    ChampionNewestWatcher(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Register a (modelKey, predictor) pair for hot-swap. Spawns the
     * shared daemon thread on the first registration. Idempotent: a
     * predictor already registered for this modelKey is ignored.
     *
     * @param modelKey         the model that this predictor binds (e.g. "rl_pawn")
     * @param predictor        the per-bot {@link GenericPredictor} instance
     * @param initialOnnxPath  the snapshot path the predictor was opened with
     *                         at bot startup — the watcher's baseline for
     *                         "did promoted counter advance?" comparisons
     */
    public static void register(String modelKey, GenericPredictor predictor,
                                String initialOnnxPath) {
        INSTANCE.doRegister(modelKey, predictor, initialOnnxPath);
    }

    private void doRegister(String modelKey, GenericPredictor predictor,
                             String initialOnnxPath) {
        synchronized (stateLock) {
            ModelState state = modelStates.computeIfAbsent(
                    modelKey, k -> new ModelState(initialOnnxPath));
            if (!state.predictors.contains(predictor)) {
                state.predictors.add(predictor);
                LOG.info("CHAMPION_NEWEST_WATCHER registered predictor for "
                        + modelKey + " -> " + initialOnnxPath
                        + " (predictors=" + state.predictors.size() + ")");
            }
            ensureDaemonStarted();
        }
    }

    private void ensureDaemonStarted() {
        if (daemon != null) {
            return;
        }
        Thread t = Thread.ofVirtual()
                .name("ChampionNewestWatcher")
                .start(this::run);
        daemon = t;
        LOG.info("CHAMPION_NEWEST_WATCHER daemon started (poll=" + pollIntervalMs + "ms)");
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                checkAndSwap();
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "CHAMPION_NEWEST_WATCHER poll error", e);
            }
        }
        LOG.info("CHAMPION_NEWEST_WATCHER daemon stopped");
    }

    void checkAndSwap() {
        Path bundles = SnapshotResolver.bundlesJsonPath();
        File bundlesFile = bundles.toFile();
        if (!bundlesFile.exists()) {
            return;
        }
        long mtime = bundlesFile.lastModified();
        if (mtime == lastBundlesMtime) {
            return;
        }

        // Snapshot the registered models under the lock; do the heavy work
        // outside the lock so a slow ENV.createSession doesn't stall new
        // registrations.
        List<String> modelKeys;
        synchronized (stateLock) {
            modelKeys = new ArrayList<>(modelStates.keySet());
        }
        if (modelKeys.isEmpty()) {
            lastBundlesMtime = mtime;
            return;
        }

        // Read newest promoted counters once for this poll; abort early if
        // malformed or if a referenced snapshot has not finished syncing.
        Map<String, Integer> promotedCounters = readNewestPromotedCounters(modelKeys);
        if (promotedCounters == null) {
            // Don't update lastBundlesMtime — retry on next poll once the
            // file is well-formed again.
            return;
        }

        boolean anyChange = false;
        boolean allClean = true;  // false ⇒ a swap failed, keep mtime stale so we retry
        for (String modelKey : modelKeys) {
            Integer promotedCounter = promotedCounters.get(modelKey);
            if (promotedCounter == null) {
                continue;
            }
            SwapOutcome outcome = trySwapModel(modelKey, promotedCounter, bundlesFile);
            if (outcome == SwapOutcome.SWAPPED) {
                anyChange = true;
            } else if (outcome == SwapOutcome.FAILED) {
                allClean = false;
            }
        }
        if (anyChange) {
            LOG.info("CHAMPION_NEWEST_WATCHER swap cycle complete (bundles.json mtime=" + mtime + ")");
        }
        if (allClean) {
            lastBundlesMtime = mtime;
        }
    }

    /**
     * Outcome of a per-model swap attempt — drives whether the watcher
     * advances {@link #lastBundlesMtime} (and therefore skips re-reading
     * bundles.json on the next poll) or retries until the failure clears.
     */
    private enum SwapOutcome {
        /** Already at the requested counter, or pool didn't list this model. */
        UNCHANGED,
        /** New session installed on every registered predictor. */
        SWAPPED,
        /** Resolve / stability / replaceAndRefresh threw — keep mtime stale to retry. */
        FAILED,
    }

    private SwapOutcome trySwapModel(String modelKey, int poolCounter, File bundlesFile) {
        ModelState state;
        synchronized (stateLock) {
            state = modelStates.get(modelKey);
            if (state == null || state.predictors.isEmpty()) {
                return SwapOutcome.UNCHANGED;
            }
        }
        if (poolCounter == state.lastCounter) {
            return SwapOutcome.UNCHANGED;
        }

        // Resolve the new snapshot path via SnapshotResolver — this also
        // hard-validates fingerprints (features.json / model.json hashes).
        // A mismatch throws here, log + skip; the next poll retries (the
        // trainer typically commits compat changes alongside the snapshot).
        SnapshotResolver.OnnxRef newRef;
        try {
            newRef = SnapshotResolver.resolve(modelKey, modelKey + "/" + poolCounter);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CHAMPION_NEWEST_WATCHER resolve failed for "
                    + modelKey + "/" + poolCounter + " — will retry next poll", e);
            return SwapOutcome.FAILED;
        }

        String newOnnxPath = newRef.onnxPath();
        if (newOnnxPath.equals(state.currentOnnxPath)) {
            // bundles.json advanced but resolved path is identical — could
            // happen if a snapshot was renamed (e.g. with a tag). Just bump
            // the counter cache.
            state.lastCounter = poolCounter;
            return SwapOutcome.UNCHANGED;
        }

        // Stability check: wait until both the new ONNX and its companion
        // .onnx.data have settled. Reuses the same pattern as ModelWatcher.
        File newOnnx = new File(newOnnxPath);
        if (!waitForStableFiles(newOnnx, bundlesFile)) {
            LOG.info("CHAMPION_NEWEST_WATCHER files still changing for "
                    + modelKey + "/" + poolCounter + ", deferring to next poll");
            return SwapOutcome.FAILED;
        }

        // Snapshot predictors under lock so a concurrent register() while we
        // iterate doesn't get half-swapped (we'd miss them; that's fine —
        // they entered with the new path already as their initialOnnxPath).
        List<GenericPredictor> targets;
        synchronized (stateLock) {
            targets = new ArrayList<>(state.predictors);
        }

        String oldOnnxPath = state.currentOnnxPath;
        LOG.info("CHAMPION_NEWEST_WATCHER swapping " + modelKey
                + " counter " + state.lastCounter + " -> " + poolCounter
                + " (" + oldOnnxPath + " -> " + newOnnxPath + ")"
                + " across " + targets.size() + " predictor(s)");

        int swapped = 0;
        for (GenericPredictor predictor : targets) {
            try {
                predictor.replaceAndRefresh(modelKey, newOnnxPath);
                swapped++;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "CHAMPION_NEWEST_WATCHER swap failed for one predictor of "
                        + modelKey + " (will retry on next poll)", e);
            }
        }

        if (swapped < targets.size()) {
            // Partial failure: at least one predictor's replaceAndRefresh threw.
            // Failed predictors still hold a spec pointing at oldOnnxPath —
            // closing the old session now would force them to re-create it
            // on the next predict() (which works, the file is immutable, but
            // it's wasteful churn). Instead leave the old session alive and
            // retry on the next poll; replaceAndRefresh is idempotent on the
            // already-swapped predictors.
            LOG.info("CHAMPION_NEWEST_WATCHER partial swap for " + modelKey
                    + " (swapped=" + swapped + "/" + targets.size()
                    + ") — keeping old session alive, will retry");
            return SwapOutcome.FAILED;
        }

        // All swaps succeeded. Drain + close the old session. closeAbandonedSession
        // takes the write-lock on the old cacheKey, which waits for any in-flight
        // predicts on the old session to finish before closing.
        if (oldOnnxPath != null && !oldOnnxPath.equals(newOnnxPath)) {
            try {
                GenericPredictor.closeAbandonedSession(oldOnnxPath);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "CHAMPION_NEWEST_WATCHER closeAbandonedSession failed for "
                        + oldOnnxPath, e);
            }
        }

        state.currentOnnxPath = newOnnxPath;
        state.lastCounter = poolCounter;
        LOG.info("CHAMPION_NEWEST_WATCHER swap done for " + modelKey
                + " -> counter " + poolCounter + " (swapped=" + swapped + "/" + targets.size() + ")");
        return SwapOutcome.SWAPPED;
    }

    /**
     * Read the newest promoted counter for each registered model from
     * {@code bundles.json}.
     * Returns null on parse failure so the caller can retry on next poll
     * (the trainer rewrites bundles.json non-atomically during sync).
     */
    private Map<String, Integer> readNewestPromotedCounters(List<String> modelKeys) {
        try {
            Map<String, Integer> out = new HashMap<>();
            for (String modelKey : modelKeys) {
                Integer counter = SnapshotResolver.readNewestPromotedCounterFromBundles(modelKey);
                if (counter != null) {
                    out.put(modelKey, counter);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Wait until {@code newOnnx}, its {@code .onnx.data} sibling, and
     * {@code bundles.json} have stable mtime + size for
     * {@link #STABILITY_CHECKS} consecutive checks. Mirrors
     * {@link aiplay.rl.ModelWatcher#waitForStableFiles}.
     */
    private boolean waitForStableFiles(File newOnnx, File bundles) {
        File dataFile = new File(newOnnx.getPath() + ".data");
        long sleepPerCheck = STABILITY_WAIT_MS / STABILITY_CHECKS;
        int stableCount = 0;
        long prevOnnxMtime = newOnnx.lastModified();
        long prevOnnxSize = newOnnx.length();
        long prevDataMtime = dataFile.exists() ? dataFile.lastModified() : -1;
        long prevDataSize = dataFile.exists() ? dataFile.length() : -1;
        long prevBundlesMtime = bundles.lastModified();
        long prevBundlesSize = bundles.length();

        while (stableCount < STABILITY_CHECKS) {
            try {
                Thread.sleep(sleepPerCheck);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long curOnnxMtime = newOnnx.lastModified();
            long curOnnxSize = newOnnx.length();
            long curDataMtime = dataFile.exists() ? dataFile.lastModified() : -1;
            long curDataSize = dataFile.exists() ? dataFile.length() : -1;
            long curBundlesMtime = bundles.lastModified();
            long curBundlesSize = bundles.length();

            if (curOnnxMtime == prevOnnxMtime && curOnnxSize == prevOnnxSize
                    && curDataMtime == prevDataMtime && curDataSize == prevDataSize
                    && curBundlesMtime == prevBundlesMtime && curBundlesSize == prevBundlesSize) {
                stableCount++;
            } else {
                stableCount = 0;
                prevOnnxMtime = curOnnxMtime;
                prevOnnxSize = curOnnxSize;
                prevDataMtime = curDataMtime;
                prevDataSize = curDataSize;
                prevBundlesMtime = curBundlesMtime;
                prevBundlesSize = curBundlesSize;
            }
        }
        // Cross-check that both files still exist after stabilization.
        return newOnnx.exists() && bundles.exists();
    }

    /**
     * Per-model registration state — list of predictors that resolved their
     * snapshot via {@code "<mk>/newest"} plus the currently-loaded snapshot
     * path and counter (used to detect promoted-counter changes).
     */
    private static final class ModelState {
        final CopyOnWriteArrayList<GenericPredictor> predictors = new CopyOnWriteArrayList<>();
        volatile String currentOnnxPath;
        volatile int lastCounter;

        ModelState(String initialOnnxPath) {
            this.currentOnnxPath = initialOnnxPath;
            // Parse counter out of the path so the first poll doesn't trigger
            // a spurious self-swap. Path layout: ".../champions/<mk>/NNNN[-tag]/<mk>.onnx".
            this.lastCounter = parseCounterFromPath(initialOnnxPath);
        }

        private static int parseCounterFromPath(String onnxPath) {
            if (onnxPath == null) return -1;
            Path p = Path.of(onnxPath);
            // parent is "NNNN" or "NNNN-tag"
            Path parent = p.getParent();
            if (parent == null) return -1;
            String dirName = parent.getFileName().toString();
            int dash = dirName.indexOf('-');
            String num = (dash > 0) ? dirName.substring(0, dash) : dirName;
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
