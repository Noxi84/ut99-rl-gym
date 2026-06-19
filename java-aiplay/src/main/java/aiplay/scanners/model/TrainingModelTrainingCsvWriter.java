package aiplay.scanners.model;

import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.GameStateDto;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.dedup.DeduplicationPolicy;
import aiplay.scanners.model.feature.AugmentedFeatureResolver;
import aiplay.scanners.model.sample.AugmentedTrainingSample;
import aiplay.scanners.model.sample.TrainingSample;
import aiplay.scanners.model.sample.TrainingSampleGenerator;
import aiplay.scanners.model.target.TrainingTargetProjector;
import aiplay.scanners.model.validation.WindowValidationPolicy;
import aiplay.scanners.model.writer.FileDao;
import aiplay.scanners.model.writer.trainingcsvwriter.frames.CsvWriterFramesFacade;
import aiplay.scanners.model.writer.trainingcsvwriter.frames.RollingCsvWriter;
import aiplay.scanners.model.writer.trainingcsvwriter.manifest.CsvShardManifest;
import aiplay.scanners.model.writer.trainingcsvwriter.reader.ReaderFacade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Generic pipeline-driver and CSV serializer. Contains no model-specific
 * augmentation, validation, deduplication, or target logic.
 *
 * Pipeline:
 * canonical window → window validation → dedup → sample generation (base + augmented)
 * → feature projection → target projection → row serialization → CSV writer
 */
public class TrainingModelTrainingCsvWriter {

    private static final long DEFAULT_MAX_BYTES = 50L * 1024L * 1024L;

    private static final DecimalFormat DECIMAL_FMT;
    static {
        DecimalFormatSymbols syms = DecimalFormatSymbols.getInstance(Locale.ROOT);
        DECIMAL_FMT = new DecimalFormat("0.######", syms);
        DECIMAL_FMT.setMinimumFractionDigits(6);
        DECIMAL_FMT.setMaximumFractionDigits(6);
    }

    private final ITrainingModel model;
    private final ReaderFacade readerFacade = new ReaderFacade();
    private final CsvWriterFramesFacade csvWriterFramesFacade = new CsvWriterFramesFacade();
    public final FileDao fileDao = new FileDao();

    public TrainingModelTrainingCsvWriter(ITrainingModel model) {
        this.model = model;
    }

    public String getModelKey() {
        return model.getModelKey();
    }

    public boolean isEnabled() {
        return model.isCsvEnabled();
    }

    public long getMaxBytes() {
        return DEFAULT_MAX_BYTES;
    }

    public void createTrainingCsvFileStreaming(String sessionId) {
        try {
            List<String> zipPaths = ReaderFacade.getGameplaySources(
                    sessionId, "json-recording-sessions", model.getModelKey());
            String modelKey = getModelKey();
            String outputDir = aiplay.runtime.config.SessionPaths.getSessionDir() + "/csv-training-data/" + modelKey;
            processZipsAndWriteCsv(sessionId, zipPaths, outputDir);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Generate CSV for this model using standard source discovery, but write to a custom output dir.
     * Used for whole-model distributed assignment (server processes all ZIPs for one model).
     */
    public void createTrainingCsvFileStreamingWithOutputDir(String sessionId, String outputDir) {
        try {
            List<String> zipPaths = ReaderFacade.getGameplaySources(
                    sessionId, "json-recording-sessions", model.getModelKey());
            processZipsAndWriteCsv(sessionId, zipPaths, outputDir);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Distributed CSV generation: process only the given ZIP files and write to a custom output dir.
     */
    public void createTrainingCsvFileStreamingDistributed(String sessionId,
                                                           String sourceDir,
                                                           List<String> zipNames,
                                                           String outputDir) {
        createTrainingCsvFileStreamingDistributed(sessionId, sourceDir, zipNames, outputDir, null, null);
    }

    /**
     * Distributed CSV generation with run context for manifest tracking.
     */
    public void createTrainingCsvFileStreamingDistributed(String sessionId,
                                                           String sourceDir,
                                                           List<String> zipNames,
                                                           String outputDir,
                                                           String runId,
                                                           String shardId) {
        try {
            List<String> zipPaths = ReaderFacade.getGameplaySourcesFromList(sourceDir, zipNames);
            processZipsAndWriteCsv(sessionId, zipPaths, outputDir, runId, shardId);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Core streaming pipeline: processes one ZIP at a time, writes CSV rows, then releases memory
     * before loading the next ZIP. Memory peak = size of one ZIP's data, not all ZIPs combined.
     */
    private void processZipsAndWriteCsv(String sessionId, List<String> zipPaths, String outputDir) {
        processZipsAndWriteCsv(sessionId, zipPaths, outputDir, null, null);
    }

    private void processZipsAndWriteCsv(String sessionId, List<String> zipPaths, String outputDir,
                                         String runId, String shardId) {
        Logger log = SessionRollingLogger.get(sessionId, SessionLogPaths.framesLog());

        if (!isEnabled()) {
            log.info("WRITER " + getModelKey() + " disabled -> skip");
            return;
        }
        if (zipPaths == null || zipPaths.isEmpty()) {
            log.info("WRITER " + getModelKey() + " no sessions -> skip");
            return;
        }

        String modelKey = getModelKey();
        String baseFilename = outputDir + "/data.csv";
        String headerLine = buildHeaderLine();
        long maxBytes = getMaxBytes();
        int fps = model.getCsvFPS();

        log.info("WRITER " + modelKey + " CSV start modelKey=" + modelKey
                + " zips=" + zipPaths.size()
                + " baseFile=" + baseFilename
                + " maxBytes=" + maxBytes);

        CsvShardManifest manifest = (runId != null && shardId != null)
                ? new CsvShardManifest(runId, modelKey, shardId) : null;
        RollingCsvWriter rollingWriter = new RollingCsvWriter(new FileDao(), baseFilename, headerLine, maxBytes);
        String manifestStatus = "complete";

        try {
            int sIdx = 0;
            long totalFramesIn = 0L;

            for (String zipPath : zipPaths) {
                sIdx++;
                long zipStartMs = System.currentTimeMillis();

                String filename = Path.of(zipPath).getFileName().toString();
                List<String> rolesForZip = rolesFromPrefix(filename);

                // Save thread-local identity so we can restore after per-role passes.
                String identityName = PlayerIdentityContext.effectivePlayerName();
                int identityTeam = PlayerIdentityContext.effectivePlayerTeam();
                String identityRole = PlayerIdentityContext.effectiveRole();

                try {
                    long zipBytes = Files.size(Path.of(zipPath));
                    long rowsBeforeAllRoles = rollingWriter.getTotalRows();
                    int totalBucketsAcrossRoles = 0;
                    int totalFramesAcrossRoles = 0;

                    for (String role : rolesForZip) {
                        // Switch role for this pass — affects role-aware enrichers
                        // (RuleBasedEngagementPolicy.pickAttentionPrior, AimTargetSelector
                        // hard-pin Attack vs sticky-closest, etc.).
                        PlayerIdentityContext.setForCurrentThread(identityName, identityTeam, role);

                        // Append role to sessionId so any per-session enricher state
                        // (mission/skill/route progressions) is isolated between passes.
                        String roleSessionId = sessionId + "_" + role;

                        // 1. Re-read ZIP per role pass — DTOs get mutated by enrichers below,
                        //    so each pass needs a fresh deserialization. Cheap relative to enrich.
                        List<GameStateDto> session = readerFacade.getGameStates(roleSessionId, zipPath);
                        if (session == null || session.isEmpty()) {
                            continue;
                        }

                        // 2. Filter, enrich, group (one ZIP, this role)
                        LinkedHashMap<Integer, List<GameStateDto>> grouped =
                                csvWriterFramesFacade.groupByElapsedTime(
                                        roleSessionId, modelKey, session, fps, fps);
                        session = null;

                        int buckets = (grouped != null ? grouped.size() : 0);
                        List<GameStateDto> sessionFrames = flattenAndRelease(grouped);
                        grouped = null;
                        int framesIn = sessionFrames.size();
                        totalFramesIn += framesIn;
                        totalFramesAcrossRoles += framesIn;
                        totalBucketsAcrossRoles += buckets;

                        // 3. Write CSV rows
                        writeCsvSessionAppend(roleSessionId, rollingWriter, sessionFrames);
                        sessionFrames.clear();
                    }

                    long rowsForZip = rollingWriter.getTotalRows() - rowsBeforeAllRoles;
                    long zipRuntimeMs = System.currentTimeMillis() - zipStartMs;

                    log.info("WRITER " + modelKey + " CSV session#" + sIdx
                            + " roles=" + rolesForZip
                            + " buckets=" + totalBucketsAcrossRoles
                            + " framesIn=" + totalFramesAcrossRoles
                            + " rows=" + rowsForZip
                            + " ms=" + zipRuntimeMs);

                    if (manifest != null) {
                        manifest.addZipResult(
                                filename,
                                zipBytes, totalFramesAcrossRoles,
                                totalBucketsAcrossRoles, rowsForZip, zipRuntimeMs);
                        manifest.samplePeakHeap();
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to process ZIP: " + zipPath, ex);
                } finally {
                    // Always restore the original thread-local identity.
                    PlayerIdentityContext.setForCurrentThread(identityName, identityTeam, identityRole);
                }
            }

            log.info("WRITER " + modelKey + " CSV done zips=" + sIdx
                    + " totalFramesIn=" + totalFramesIn
                    + " totalRows=" + rollingWriter.getTotalRows()
                    + " parts=" + rollingWriter.getPartCount()
                    + " baseFile=" + baseFilename);
        } catch (RuntimeException e) {
            manifestStatus = "failed";
            throw e;
        } finally {
            if (manifest != null) {
                manifest.write(outputDir, manifestStatus,
                        rollingWriter.getPartCount(), rollingWriter.getTotalBytesWritten());
            }
            rollingWriter.close();
        }
    }

    /**
     * Consumes one joint transition sample built by the exact CSV-writer
     * pipeline: the flattened canonical state window (frame-major, identical to
     * {@code states.npy} layout produced by the live {@link aiplay.rl.ExperienceCollector}),
     * the projected target-action vector, and the window's current frame (the
     * last frame in the window — the game state the action/reward pertains to).
     */
    @FunctionalInterface
    public interface JointTransitionConsumer {
        void accept(float[] flatState, float[] action, GameStateDto currentFrame);
    }

    /**
     * Streams joint experience transitions for one gameplay source (zip or dir)
     * through the FULL CSV-writer front-end: per-role identity switching (from
     * the filename prefix, identical to {@link #processZipsAndWriteCsv}), JSON→DTO
     * conversion via {@link ReaderFacade}, frame filtering + FPS grouping via
     * {@link CsvWriterFramesFacade#groupByElapsedTime}, then
     * {@link #forEachJointTransition}. The {@code consumer} sees exactly the
     * frames (post-filter, post-dedup) that the BC CSV pipeline would have
     * serialized, so the resulting experience matches BC frame-for-frame.
     *
     * <p>Used by {@code DemoToExperienceMain}; the consumer typically drives a
     * {@link aiplay.rl.PerModelExperienceRecorder} and calls its
     * {@code resetEpisode()} between role passes (handled by the caller via the
     * {@code rolePassBoundary} callback).</p>
     *
     * @return total number of transitions handed to the consumer.
     */
    public long streamJointTransitions(String sessionId, String zipPath,
                                       JointTransitionConsumer consumer,
                                       Runnable rolePassBoundary) {
        if (!isEnabled()) {
            throw new IllegalStateException("CSV writer disabled for model " + getModelKey()
                    + " — cannot stream joint transitions");
        }
        String modelKey = getModelKey();
        int fps = model.getCsvFPS();

        String filename = Path.of(zipPath).getFileName().toString();
        List<String> rolesForZip = rolesFromPrefix(filename);

        String identityName = PlayerIdentityContext.effectivePlayerName();
        int identityTeam = PlayerIdentityContext.effectivePlayerTeam();
        String identityRole = PlayerIdentityContext.effectiveRole();

        long[] count = {0L};
        try {
            for (String role : rolesForZip) {
                PlayerIdentityContext.setForCurrentThread(identityName, identityTeam, role);
                String roleSessionId = sessionId + "_" + role;
                List<GameStateDto> session;
                try {
                    session = readerFacade.getGameStates(roleSessionId, zipPath);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to read gameplay source: " + zipPath, ex);
                }
                if (session == null || session.isEmpty()) {
                    continue;
                }
                LinkedHashMap<Integer, List<GameStateDto>> grouped =
                        csvWriterFramesFacade.groupByElapsedTime(
                                roleSessionId, modelKey, session, fps, fps);
                List<GameStateDto> sessionFrames = flattenAndRelease(grouped);
                forEachJointTransition(roleSessionId, sessionFrames, (s, a, f) -> {
                    count[0]++;
                    consumer.accept(s, a, f);
                });
                if (rolePassBoundary != null) {
                    rolePassBoundary.run();
                }
            }
        } finally {
            PlayerIdentityContext.setForCurrentThread(identityName, identityTeam, identityRole);
        }
        return count[0];
    }

    /**
     * Offline experience driver — reuses the IDENTICAL canonical feature
     * projection ({@link #precomputeCanonicalValues}) and target projection
     * ({@link TrainingTargetProjector}) as {@link #writeCsvSessionAppend}, but
     * instead of serializing CSV rows it hands each window's
     * {@code (flatState, action, currentFrame)} to {@code consumer}.
     *
     * <p>Single source of truth for the BC CSV state/action extraction: this
     * walks the same sliding window with the same {@link WindowValidationPolicy}
     * and {@link DeduplicationPolicy}, so the emitted state vectors are
     * byte-for-byte equal to the BC training CSV (rl_pawn emits only the
     * identity sample — no augmentation). Used by {@code DemoToExperienceMain}
     * to inject a human demo into SAC-from-demonstrations.</p>
     *
     * <p>Only the canonical (identity) sample is emitted — live experience is
     * never perspective-augmented.</p>
     */
    public void forEachJointTransition(String sessionId, List<GameStateDto> sessionFrames,
                                       JointTransitionConsumer consumer) {
        if (sessionFrames == null || sessionFrames.isEmpty()) {
            return;
        }
        int windowSize = model.getCsvNumberOfColumns();
        if (sessionFrames.size() < windowSize) {
            return;
        }

        String modelKey = model.getModelKey();
        List<String> inputFeatures = model.getInputFeatures();
        List<String> targetFeatures = model.getCsvTargetFeatures();
        int nFeatures = inputFeatures.size();
        int nActions = targetFeatures.size();

        WindowValidationPolicy windowValidation = model.getWindowValidationPolicy();
        DeduplicationPolicy dedup = model.createDeduplicationPolicy();
        AugmentedFeatureResolver featureResolver = model.getAugmentedFeatureResolver();
        TrainingTargetProjector targetProjector = model.getTrainingTargetProjector();
        TrainingFeatureService featureService = model.getTrainingFeatureService();

        for (int i = 0; i <= sessionFrames.size() - windowSize; i++) {
            int currentIndex = i + (windowSize - 1);
            List<GameStateDto> frameWindow = sessionFrames.subList(i, i + windowSize);

            TrainingSample sample = new TrainingSample(
                    sessionId, modelKey, sessionFrames, frameWindow, currentIndex);

            WindowValidationPolicy.Decision decision = windowValidation.validate(sample);
            if (decision == WindowValidationPolicy.Decision.STOP) {
                break;
            }
            if (decision == WindowValidationPolicy.Decision.SKIP) {
                continue;
            }
            if (!dedup.shouldAccept(sample)) {
                continue;
            }

            float[][] canonicalValues = precomputeCanonicalValues(
                    sample, frameWindow, inputFeatures, featureResolver, featureService);

            // Flatten frame-major: flat[t * nFeatures + f] — identical to
            // PerModelExperienceRecorder.flattenInput / ExperienceCollector states layout.
            float[] flatState = new float[windowSize * nFeatures];
            for (int t = 0; t < windowSize; t++) {
                System.arraycopy(canonicalValues[t], 0, flatState, t * nFeatures, nFeatures);
            }

            AugmentedTrainingSample identity = AugmentedTrainingSample.identity(sample);
            float[] action = new float[nActions];
            for (int a = 0; a < nActions; a++) {
                action[a] = targetProjector.resolveTargetValue(targetFeatures.get(a), identity);
            }

            consumer.accept(flatState, action, frameWindow.get(windowSize - 1));
        }
    }

    /**
     * Append 1 session to CSV using the augmentation pipeline.
     */
    public void writeCsvSessionAppend(String sessionId, RollingCsvWriter rollingWriter,
                                       List<GameStateDto> sessionFrames) {
        if (rollingWriter == null || sessionFrames == null || sessionFrames.isEmpty()) {
            return;
        }
        int windowSize = model.getCsvNumberOfColumns();
        if (sessionFrames.size() < windowSize) {
            return;
        }

        String modelKey = model.getModelKey();
        List<String> inputFeatures = model.getInputFeatures();
        List<String> targetFeatures = model.getCsvTargetFeatures();

        WindowValidationPolicy windowValidation = model.getWindowValidationPolicy();
        DeduplicationPolicy dedup = model.createDeduplicationPolicy();
        TrainingSampleGenerator sampleGenerator = model.getTrainingSampleGenerator();
        AugmentedFeatureResolver featureResolver = model.getAugmentedFeatureResolver();
        TrainingTargetProjector targetProjector = model.getTrainingTargetProjector();
        TrainingFeatureService featureService = model.getTrainingFeatureService();

        for (int i = 0; i <= sessionFrames.size() - windowSize; i++) {
            int currentIndex = i + (windowSize - 1);
            List<GameStateDto> frameWindow = sessionFrames.subList(i, i + windowSize);

            TrainingSample sample = new TrainingSample(
                    sessionId, modelKey, sessionFrames, frameWindow, currentIndex);

            // Window validation
            WindowValidationPolicy.Decision decision = windowValidation.validate(sample);
            if (decision == WindowValidationPolicy.Decision.STOP) {
                break;
            }
            if (decision == WindowValidationPolicy.Decision.SKIP) {
                continue;
            }

            // Deduplication (on canonical sample, before augmentation)
            if (!dedup.shouldAccept(sample)) {
                continue;
            }

            // Pre-compute canonical feature values for the base window (shared by all augmented variants)
            float[][] canonicalValues = precomputeCanonicalValues(
                    sample, frameWindow, inputFeatures, featureResolver, featureService);

            // Generate base + augmented variants
            Iterator<AugmentedTrainingSample> variants = sampleGenerator.generateSamples(sample);
            while (variants.hasNext()) {
                AugmentedTrainingSample aug = variants.next();
                String csvLine = buildCsvRow(aug, inputFeatures,
                        targetFeatures, featureResolver, targetProjector, featureService,
                        canonicalValues);
                rollingWriter.appendLine(csvLine);
            }
        }
    }

    /**
     * Pre-compute canonical (non-augmented) feature values for all frames in a window.
     * Returns float[frameIndex][featureIndex] so augmented variants can reuse them.
     */
    private float[][] precomputeCanonicalValues(TrainingSample sample,
                                                 List<GameStateDto> frameWindow,
                                                 List<String> inputFeatures,
                                                 AugmentedFeatureResolver featureResolver,
                                                 TrainingFeatureService featureService) {
        AugmentedTrainingSample identity = AugmentedTrainingSample.identity(sample);
        float[][] values = new float[frameWindow.size()][inputFeatures.size()];
        for (int t = 0; t < frameWindow.size(); t++) {
            GameStateDto frame = frameWindow.get(t);
            for (int f = 0; f < inputFeatures.size(); f++) {
                values[t][f] = featureResolver.resolveFeatureValue(inputFeatures.get(f), identity, frame);
            }
        }
        return values;
    }

    private String buildCsvRow(AugmentedTrainingSample aug,
                                List<String> inputFeatures,
                                List<String> targetFeatures,
                                AugmentedFeatureResolver featureResolver,
                                TrainingTargetProjector targetProjector,
                                TrainingFeatureService featureService,
                                float[][] canonicalValues) {

        TrainingSample base = aug.getBaseSample();
        List<GameStateDto> frameWindow = base.getFrameWindow();

        StringBuilder line = new StringBuilder(4096 * 12);

        // Timeline features: per frame in window
        for (int t = 0; t < frameWindow.size(); t++) {
            for (int f = 0; f < inputFeatures.size(); f++) {
                appendFeatureValue(inputFeatures.get(f), line, canonicalValues[t][f], featureService, false);
            }
        }

        // Target features: via target projector
        for (String featureId : targetFeatures) {
            float v = targetProjector.resolveTargetValue(featureId, aug);
            appendFeatureValue(featureId, line, v, featureService, targetProjector.isTargetBoolean(featureId));
        }

        // Phase 2 aux targets (e.g. target_index, target_index_confidence). Same
        // projector handles both — aux columns are NOT model output_size, only
        // consumed by aux losses on Python side.
        for (String featureId : this.model.getCsvAuxTargetFeatures()) {
            float v = targetProjector.resolveTargetValue(featureId, aug);
            appendFeatureValue(featureId, line, v, featureService, targetProjector.isTargetBoolean(featureId));
        }

        // Remove trailing semicolon
        if (line.length() > 0 && line.charAt(line.length() - 1) == ';') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    private void appendFeatureValue(String featureId, StringBuilder line, float v,
                                     TrainingFeatureService featureService, boolean forceBoolean) {
        if (forceBoolean || featureService.isBooleanFeature(featureId)) {
            line.append(Integer.toString((int) Math.round(v)));
        } else {
            line.append(DECIMAL_FMT.format(v));
        }
        line.append(';');
    }

    public String buildHeaderLine() {
        StringBuilder builder = new StringBuilder(8192);

        int windowSize = model.getCsvNumberOfColumns();
        List<String> perFrameOrder = model.getInputFeatures();
        List<String> targetsOrder = model.getCsvTargetFeatures();
        List<String> auxTargetsOrder = model.getCsvAuxTargetFeatures();

        for (int frame = 1; frame <= windowSize; frame++) {
            String suffix = (frame == windowSize) ? "" : "_F" + frame;
            for (String featureId : perFrameOrder) {
                builder.append(featureId).append(suffix).append(';');
            }
        }

        for (String featureId : targetsOrder) {
            builder.append(featureId).append(';');
        }

        // Phase 2 aux targets (e.g. target_index, target_index_confidence) — appended
        // after main targets so Python BC reads input + targets + aux deterministically.
        for (String featureId : auxTargetsOrder) {
            builder.append(featureId).append(';');
        }

        // Remove trailing semicolon
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) == ';') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    public String writeCsvHeader(String filename) {
        String header = buildHeaderLine();
        fileDao.writeFileOverwrite(filename, header);
        return header;
    }

    /**
     * Determine which role(s) to process a ZIP under, based on filename prefix:
     * <ul>
     *   <li>{@code Default_*} — process 4× (Attack, Cover, Defend, DeathMatch). Same human
     *       behavior is replicated across all roles, so the model gets at least DM-style
     *       tracking labeled under every role-conditioned reward signal.</li>
     *   <li>{@code Attack_*} / {@code Cover_*} / {@code Defend_*} / {@code DeathMatch_*} —
     *       process 1× under that specific role. Use for sessions actually played as that role
     *       so the role-aware aim target (carrier for Defend, nearest-to-attacker for Cover,
     *       hard-pin closest for Attack) drives the labeled features.</li>
     *   <li>No prefix — process 1× under the runtime.json default role
     *       ({@link PlayerIdentityContext#effectiveRole()} as set by the caller).</li>
     * </ul>
     */
    private static List<String> rolesFromPrefix(String filename) {
        if (filename == null) return List.of(PlayerIdentityContext.effectiveRole());
        int us = filename.indexOf('_');
        if (us <= 0) return List.of(PlayerIdentityContext.effectiveRole());
        String prefix = filename.substring(0, us);
        return switch (prefix) {
            case "Default" -> List.of("Attack", "Cover", "Defend", "DeathMatch");
            case "Attack" -> List.of("Attack");
            case "Cover" -> List.of("Cover");
            case "Defend" -> List.of("Defend");
            case "DeathMatch" -> List.of("DeathMatch");
            default -> List.of(PlayerIdentityContext.effectiveRole());
        };
    }

    private static List<GameStateDto> flattenAndRelease(LinkedHashMap<Integer, List<GameStateDto>> grouped) {
        if (grouped == null || grouped.isEmpty()) {
            return new ArrayList<>();
        }
        int totalSize = 0;
        for (List<GameStateDto> bucket : grouped.values()) {
            totalSize += bucket.size();
        }
        List<GameStateDto> result = new ArrayList<>(totalSize);
        for (List<GameStateDto> bucket : grouped.values()) {
            result.addAll(bucket);
        }
        grouped.clear();
        return result;
    }
}
