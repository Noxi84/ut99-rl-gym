package aiplay.rl.champion;

import aiplay.runtime.config.PolicyRole;
import aiplay.runtime.config.SessionPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a snapshot specifier from gameplay.json into a concrete ONNX
 * path + predictor key, hard-rejecting incompatible champions.
 *
 * <p>Supported specs (MVP — phase 3):
 * <ul>
 *   <li>{@code "current"} — live trainingmodel ONNX</li>
   *   <li>{@code "<modelKey>/<counter>"} — specific frozen champion (e.g.
   *       {@code "rl_pawn/0007"})</li>
 * </ul>
 *
 * <p>Bundle specs ({@code "bundle:<id>"}) are not supported. Use
 * {@code "<modelKey>/newest"} to dynamically resolve to the newest promoted
 * snapshot in {@code bundles.json}, or pin a concrete counter directly.
 */
public final class SnapshotResolver {

    private static final Pattern COUNTER_DIR_RE = Pattern.compile("^(\\d{4,})(?:-.+)?$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Resolves project root with the same precedence as
     * {@code PropertyReaderUtils.getProjectRoot()}: {@code UT99_PROJECT_ROOT}
     * env-var → {@code user.dir} (if it contains resources/ or train/) →
     * {@code user.dir.parent} (handles running from java-aiplay/ via maven) →
     * {@code user.dir} fallback. Resolved per call so tests can override.
     */
    private static Path resourcesModels() {
        String configured = System.getenv("UT99_PROJECT_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).resolve("resources").resolve("models");
        }
        Path userDir = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(userDir.resolve("resources"))
            || Files.isDirectory(userDir.resolve("train"))) {
            return userDir.resolve("resources").resolve("models");
        }
        Path parent = userDir.getParent();
        if (parent != null
            && (Files.isDirectory(parent.resolve("resources"))
                || Files.isDirectory(parent.resolve("train")))) {
            return parent.resolve("resources").resolve("models");
        }
        return userDir.resolve("resources").resolve("models");
    }

    private SnapshotResolver() {}

    /**
     * Resolved reference for a single (modelKey, snapshotSpec) pair.
     *
     * @param predictorKey   unique key passed to {@link aiplay.prediction.GenericPredictor}
     *                       for registration + lookup. For "current" this is
     *                       just the modelKey; for champions it embeds the
     *                       counter, e.g. {@code "rl_pawn@0007"}.
     * @param onnxPath       absolute path to the ONNX file to load.
     * @param policyRole     {@link PolicyRole#CURRENT} or {@link PolicyRole#CHAMPION}.
     * @param dynamicNewest  true when the spec was {@code "<mk>/newest"} and the
     *                       resolved counter therefore tracks the newest
     *                       promoted snapshot in {@code bundles.json}.
     *                       {@link aiplay.rl.champion.ChampionNewestWatcher}
     *                       uses this flag to decide whether to hot-swap the
     *                       champion at runtime when a new PROMOTE updates
     *                       bundles.json. Pinned-counter specs ({@code "<mk>/0007"})
     *                       set this to false and stay frozen until bot restart.
     */
    public record OnnxRef(String predictorKey, String onnxPath, PolicyRole policyRole,
                          boolean dynamicNewest) {}

    public static OnnxRef resolve(String modelKey, String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException(
                "Snapshot spec is required for model '" + modelKey + "' (use \"current\" for live)");
        }
        if ("current".equals(snapshot)) {
            String onnx = SessionPaths.getModelTrainingDir() + "/" + modelKey + ".onnx";
            return new OnnxRef(modelKey, onnx, PolicyRole.CURRENT, false);
        }
        if (snapshot.startsWith("bundle:")) {
            throw new UnsupportedOperationException(
                "Bundle snapshot specs not supported — use \"<model_key>/<counter>\" "
                + "or \"<model_key>/newest\" instead (got: '" + snapshot + "')");
        }

        int slash = snapshot.indexOf('/');
        if (slash <= 0 || slash >= snapshot.length() - 1) {
            throw new IllegalArgumentException(
                "Invalid snapshot spec '" + snapshot + "' for model '" + modelKey
                + "' (expected \"current\", \"<model_key>/<counter>\", or \"<model_key>/newest\")");
        }

        String specMk = snapshot.substring(0, slash);
        if (!specMk.equals(modelKey)) {
            throw new IllegalArgumentException(
                "Snapshot model_key mismatch: spec is '" + specMk
                + "' but bot configures it under model '" + modelKey + "'");
        }

        String counterPart = snapshot.substring(slash + 1);
        int counter;
        boolean dynamicNewest;
        if ("newest".equals(counterPart)) {
            // Dynamic spec: resolve to the newest PROMOTED counter for this
            // model from bundles.json. Deployed-only/bootstrap snapshots are
            // deliberately ignored so champion bots never track the live
            // trainingmodel unless it actually passed promotion.
            counter = readNewestCounterFromBundles(modelKey);
            dynamicNewest = true;
        } else {
            try {
                counter = Integer.parseInt(counterPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Snapshot counter must be an integer or 'newest': " + snapshot, e);
            }
            dynamicNewest = false;
        }

        Path snapshotDir = findSnapshotDir(modelKey, counter);
        if (snapshotDir == null) {
            throw new IllegalStateException(
                "Champion snapshot not found on disk: "
                + championsRoot().resolve(modelKey).resolve(String.format("%04d", counter))
                + " (or with -tag suffix). Run '.venv/bin/python3 -m train.common.champion_store list'"
                + " on the trainer to see what is available.");
        }

        SnapshotMeta meta = SnapshotMeta.read(snapshotDir.resolve("snapshot.json"));
        validateFingerprints(meta, modelKey, snapshotDir);

        String predictorKey = modelKey + "@" + String.format("%04d", counter);
        String onnxPath = snapshotDir.resolve(modelKey + ".onnx").toString();
        return new OnnxRef(predictorKey, onnxPath, PolicyRole.CHAMPION, dynamicNewest);
    }

    private static void validateFingerprints(SnapshotMeta meta, String modelKey, Path snapshotDir) {
        Path resDir = resourcesModels().resolve(modelKey);
        String featCur = ChampionFingerprint.compute(resDir.resolve("features.json"));
        String archCur = ChampionFingerprint.compute(resDir.resolve("model.json"));

        if (!featCur.equals(meta.featureFingerprint())) {
            throw new IllegalStateException(
                "Champion fingerprint mismatch (features.json) for " + modelKey
                + "/" + String.format("%04d", meta.counter())
                + " at " + snapshotDir
                + ": snapshot was made for fingerprint=" + meta.featureFingerprint()
                + " but current resources/models/" + modelKey + "/features.json hashes to "
                + featCur + ". The features schema changed since this snapshot was created. "
                + "Either revert features.json or delete this incompatible snapshot.");
        }
        if (!archCur.equals(meta.archFingerprint())) {
            throw new IllegalStateException(
                "Champion fingerprint mismatch (model.json) for " + modelKey
                + "/" + String.format("%04d", meta.counter())
                + " at " + snapshotDir
                + ": snapshot was made for fingerprint=" + meta.archFingerprint()
                + " but current resources/models/" + modelKey + "/model.json hashes to "
                + archCur + ". The model architecture changed since this snapshot was created. "
                + "Either revert model.json or delete this incompatible snapshot.");
        }
        // rewards_fingerprint mismatch is informational only; champions are
        // frozen ONNXes whose policy reflects the rewards in effect at training
        // time. A mismatch tells you which reward shape created this champion
        // but does not block inference.
    }

    /**
     * Reads {@code bundles.json} to resolve {@code <mk>/newest} to the newest
     * promoted counter. Iterates pool head-to-tail and ignores bootstrap/manual
     * snapshots whose {@code snapshot.json} does not carry
     * {@code kpi_at_snapshot.decision = PROMOTE}.
     */
    private static int readNewestCounterFromBundles(String modelKey) {
        Integer counter = readNewestPromotedCounterFromBundles(modelKey);
        if (counter == null) {
            throw new IllegalStateException(
                "Cannot resolve '" + modelKey + "/newest': bundles.json has no promoted "
                + "snapshot for " + modelKey
                + ". A deployed-only/bootstrap snapshot is not eligible as champion.");
        }
        return counter;
    }

    static Integer readNewestPromotedCounterFromBundles(String modelKey) {
        Path bundles = championsRoot().resolve("bundles.json");
        if (!Files.exists(bundles)) {
            throw new IllegalStateException(
                "Cannot resolve '" + modelKey + "/newest': bundles.json not found at "
                + bundles + ". Run '.venv/bin/python3 -m train.common.champion_pool bootstrap'"
                + " on the trainer first.");
        }
        try {
            JsonNode root = MAPPER.readTree(bundles.toFile());
            JsonNode pool = root.get("pool");
            if (pool == null || !pool.isArray() || pool.size() == 0) {
                throw new IllegalStateException(
                    "Cannot resolve '" + modelKey + "/newest': bundles.json pool is empty");
            }

            for (JsonNode bundle : pool) {
                JsonNode counters = bundle.get("counters");
                if (counters == null || !counters.has(modelKey)) {
                    continue;
                }
                int counter = counters.get(modelKey).asInt();
                if (isPromotedSnapshot(modelKey, counter)) {
                    return counter;
                }
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read bundles.json for '" + modelKey + "/newest' resolution", e);
        }
    }

    private static boolean isPromotedSnapshot(String modelKey, int counter) {
        Path snapshotDir = findSnapshotDir(modelKey, counter);
        if (snapshotDir == null) {
            throw new IllegalStateException(
                "Cannot inspect champion snapshot metadata: "
                + championsRoot().resolve(modelKey).resolve(String.format("%04d", counter))
                + " not found (or with -tag suffix).");
        }
        SnapshotMeta meta = SnapshotMeta.read(snapshotDir.resolve("snapshot.json"));
        return meta.isPromoted();
    }

    private static Path championsRoot() {
        return Path.of(SessionPaths.getSessionsBaseDir(), "models", "champions");
    }

    /**
     * Absolute path to the shared {@code bundles.json} file that tracks
     * promoted champion bundles. {@link ChampionNewestWatcher}
     * polls this file's mtime so a PROMOTE on the trainer side refreshes
     * any {@code "<mk>/newest"} bot without a restart.
     */
    public static Path bundlesJsonPath() {
        return championsRoot().resolve("bundles.json");
    }

    private static Path findSnapshotDir(String modelKey, int counter) {
        Path modelDir = championsRoot().resolve(modelKey);
        File dir = modelDir.toFile();
        if (!dir.isDirectory()) {
            return null;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return null;
        }
        for (File e : entries) {
            if (!e.isDirectory()) continue;
            Matcher m = COUNTER_DIR_RE.matcher(e.getName());
            if (!m.matches()) continue;
            if (Integer.parseInt(m.group(1)) == counter) {
                return e.toPath();
            }
        }
        return null;
    }
}
