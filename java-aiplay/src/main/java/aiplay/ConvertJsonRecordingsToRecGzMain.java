package aiplay;

import aiplay.config.ModelConfigRepository;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.model.ModelConfig;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.runtime.config.SessionPaths;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.dto.KeyboardMoveDto;
import aiplay.dto.ViewRotationDto;
import aiplay.rl.RealtimeSequenceInputBuilder;
import aiplay.rl.recording.RawGameplayRecorder;
import aiplay.rl.targeting.JointTargetAttribution;
import aiplay.scanners.executors.rlpawn.RLPawnActionDecoder;
import aiplay.scanners.executors.rlpawn.RLPawnModelSpec;
import aiplay.scanners.feature.contract.FeatureContractRepository;
import aiplay.scanners.model.writer.trainingcsvwriter.reader.ReaderFacade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Converts human-recorded JSON gameplay sessions (from RecordLauncher) into
 * {@code .rec.gz} replay corpus files in {@code <recordings_dir>/from-dev/rl_pawn/},
 * matching the format produced by bot CAPTURE-mode. The replay tool then treats
 * human + bot recordings uniformly.
 *
 * <p>Scope: joint {@code rl_pawn} only. Actions are encoded in the
 * 10-dim pre-tanh layout that {@link RLPawnActionDecoder} expects
 * (movement[0..5], yaw/pitch[6..7], fire/altFire[8..9]). SAC ingestion applies
 * {@code np.tanh()}, so demonstrator actions live in the same space as the
 * raw model logits the live bot writes.</p>
 *
 * <p>Source layouts supported (auto-detected per file):
 * <ul>
 *   <li><b>flat zip</b>: ZIP containing per-tick {@code *.json} entries
 *       (the original RecordLauncher output).</li>
 *   <li><b>nested zip</b>: ZIP containing other {@code *.zip} entries (one
 *       per recording session). Each inner zip is extracted to a tmp file,
 *       read, then deleted in turn so peak disk use stays small.</li>
 *   <li><b>directory</b>: a folder of {@code *.json} files.</li>
 * </ul>
 */
public final class ConvertJsonRecordingsToRecGzMain {

    private static final String SESSION_ID = "human-replay";
    private static final int RAW_REC_ROTATE_SEC = 600;
    private static final int RAW_REC_QUEUE_CAP = 4096;

    private static final String MODEL_VR_SHOOTING = "rl_pawn";

    private ConvertJsonRecordingsToRecGzMain() {}

    public static void main(String[] args) throws Exception {
        SessionPaths.ensureSessionDirsExist();
        FeatureContractRepository.shared().validateAll();

        GlobalConfigRepository globalCfg = GlobalConfigRepository.shared();
        String playerName = globalCfg.recording().playerName();
        PlayerIdentityContext.init(
            playerName,
            globalCfg.player().team(),
            globalCfg.player().role());
        System.out.println("Recording player: " + playerName
            + " role=" + globalCfg.player().role());

        Args cli = parseArgs(args);
        Path jsonRoot = (cli.jsonSource != null)
                ? Path.of(cli.jsonSource)
                : Path.of(SessionPaths.getSessionDir(), "json-recording-sessions");
        Path outputRoot = (cli.outputDir != null)
                ? Path.of(cli.outputDir)
                : Path.of(SessionPaths.getRecordingsDir(), "from-dev");

        if (!Files.isDirectory(jsonRoot)) {
            throw new IllegalArgumentException("JSON source dir not found: " + jsonRoot);
        }

        long totalTicks = convertModel(MODEL_VR_SHOOTING, jsonRoot, outputRoot, globalCfg);

        System.out.println("=== Done. Total ticks: " + totalTicks + " ===");
    }

    private static long convertModel(String modelKey, Path jsonRoot, Path outputRoot,
                                      GlobalConfigRepository globalCfg)
            throws Exception {
        Path modelJsonDir = jsonRoot.resolve(modelKey);
        if (!Files.isDirectory(modelJsonDir)) {
            System.out.println("[" + modelKey + "] no source directory at " + modelJsonDir + " — skip");
            return 0;
        }
        List<Path> sessions = listSessionsInModelDir(modelJsonDir);
        if (sessions.isEmpty()) {
            System.out.println("[" + modelKey + "] no sessions found in " + modelJsonDir);
            return 0;
        }
        System.out.println("[" + modelKey + "] sessions to convert: " + sessions.size());

        // Wipe stale .rec.gz from previous runs so the next experience-generation
        // step doesn't see a mix of old + new corpus. Convert is idempotent over
        // the source JSONs; the output should reflect ONLY the current sources.
        cleanRecGzFiles(outputRoot.resolve(modelKey), modelKey);

        ModelState state = buildModelState(modelKey, outputRoot, globalCfg);
        ReaderFacade reader = new ReaderFacade();
        long total = 0;
        try (RawGameplayRecorder rec = state.recorder) {
            for (Path session : sessions) {
                System.out.println("--- [" + modelKey + "] Session: " + session.getFileName() + " ---");
                long ticks = processSession(session, reader, state, rec);
                total += ticks;
                System.out.println("  → session ticks: " + ticks);
            }
        }
        System.out.println("[" + modelKey + "] total ticks: " + total);
        return total;
    }

    private static long processSession(Path session, ReaderFacade reader,
                                         ModelState st, RawGameplayRecorder rec)
            throws Exception {

        if (Files.isDirectory(session) || !isZip(session)) {
            return processSourcePath(session, reader, st, rec);
        }

        // Detect nested-zip layout (zip-of-zips).
        List<String> innerZipNames = listInnerZipEntries(session);
        if (innerZipNames.isEmpty()) {
            return processSourcePath(session, reader, st, rec);
        }

        System.out.println("  nested-zip detected: " + innerZipNames.size() + " inner zips");
        long total = 0;
        Path tmpDir = Files.createTempDirectory("rec-extract-");
        try {
            for (String innerName : innerZipNames) {
                Path tmpZip = tmpDir.resolve(safeFileName(innerName));
                try (ZipFile outer = new ZipFile(session.toFile())) {
                    ZipEntry entry = outer.getEntry(innerName);
                    if (entry == null) continue;
                    try (InputStream in = outer.getInputStream(entry)) {
                        Files.copy(in, tmpZip, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                System.out.println("  inner: " + innerName + " (" + Files.size(tmpZip) + " bytes)");
                long ticks = processSourcePath(tmpZip, reader, st, rec);
                System.out.println("    ticks=" + ticks);
                total += ticks;
                Files.deleteIfExists(tmpZip);
                System.gc(); // help reclaim large DTO lists between inner zips
            }
        } finally {
            try (Stream<Path> s = Files.list(tmpDir)) {
                s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
            } catch (IOException ignore) {}
            Files.deleteIfExists(tmpDir);
        }
        return total;
    }

    private static long processSourcePath(Path source, ReaderFacade reader,
                                            ModelState st, RawGameplayRecorder rec)
            throws Exception {

        List<GameStateDto> dtos;
        try {
            dtos = reader.getGameStates(SESSION_ID, source.toString());
        } catch (Exception e) {
            System.err.println("    ! reader failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return 0;
        }
        if (dtos.isEmpty()) {
            System.out.println("    (0 DTOs loaded)");
            return 0;
        }
        System.out.println("    " + dtos.size() + " DTOs loaded");

        return emitJoint(st, rec, dtos);
    }

    private static long emitJoint(ModelState st, RawGameplayRecorder rec,
                                  List<GameStateDto> dtos) {
        if (dtos.size() < st.seqLen + 1) return 0;
        long ticks = 0;
        long skippedNullPawn = 0;
        long skippedBuildFail = 0;
        String firstErr = null;

        for (int t = st.seqLen; t < dtos.size(); t++) {
            GameStateDto curr = dtos.get(t);
            GameStateDto prev = dtos.get(t - 1);
            if (curr == null || curr.playerPawn == null) {
                skippedNullPawn++;
                continue;
            }
            List<GameStateDto> window = dtos.subList(t - st.seqLen + 1, t + 1);
            float[][][] input;
            try {
                input = st.inputBuilder.build(SESSION_ID, window, curr);
            } catch (Exception e) {
                skippedBuildFail++;
                if (firstErr == null) firstErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                continue;
            }

            float[] action = jointPawnAction(prev, curr, st.maxYawStep, st.maxPitchStep);
            // targetIdx in .rec.gz = post-hoc geattribueerde slot (kill of
            // closest-visible). Stage 3 (GenerateExperienceFromRecordingsMain)
            // gebruikt deze ALS de policy argmax was — voor offline-replay
            // van human demos is dat de beste beschikbare proxy. Confidence
            // wordt door PerModelExperienceRecorder zelf opnieuw afgeleid uit
            // de GameStateDto in elke tick.
            JointTargetAttribution.TargetLabel label = JointTargetAttribution.offline(
                dtos, t, JointTargetAttribution.KILL_ATTRIBUTION_WINDOW_TICKS);
            int targetIdx = label.slot();

            rec.onTick(input, action, curr, /*actionLogProb=*/ Float.NaN,
                /*targetIdx=*/ targetIdx, /*targetLogProb=*/ 0.0f);
            ticks++;
        }
        if (skippedNullPawn > 0 || skippedBuildFail > 0) {
            System.out.println("    [" + st.modelKey + "] skip nullPawn=" + skippedNullPawn
                    + " buildFail=" + skippedBuildFail
                    + (firstErr != null ? " firstErr=" + firstErr : ""));
        }
        return ticks;
    }

    private static List<String> listInnerZipEntries(Path zipPath) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private static boolean isZip(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static String safeFileName(String name) {
        return name.replace('/', '_').replace('\\', '_');
    }

    private static ModelState buildModelState(String modelKey, Path outputRoot,
                                                GlobalConfigRepository globalCfg) throws IOException {
        ModelConfig cfg = ModelConfigRepository.shared().get(modelKey);
        Path outDir = outputRoot.resolve(modelKey);
        Files.createDirectories(outDir);

        float maxYawStep = (float) globalCfg.commandController().yawHeading().continuousMaxStep();
        if (maxYawStep <= 0f) maxYawStep = 3000f;
        float maxPitchStep = (float) globalCfg.commandController().pitch().continuousMaxStep();
        if (maxPitchStep <= 0f) maxPitchStep = 960f;

        return new ModelState(
                modelKey,
                cfg.sequenceLength(),
                RLPawnModelSpec.loadStrict().inputBuilder(),
                new RawGameplayRecorder(modelKey, outDir, RAW_REC_ROTATE_SEC, RAW_REC_QUEUE_CAP,
                        /*blockOnFull=*/ true),
                maxYawStep,
                maxPitchStep
        );
    }

    /** Clamp before atanh to avoid +/-inf at the asymptotes. */
    private static final float ATANH_CLAMP = 0.99f;

    private static float toPreTanh(float postTanh) {
        float v = Math.max(-ATANH_CLAMP, Math.min(ATANH_CLAMP, postTanh));
        return 0.5f * (float) Math.log((1.0f + v) / (1.0f - v));
    }

    private static float[] movementAction(GameStateDto prev, GameStateDto curr) {
        // 6-dim movement layout: [moveDir_sin, moveDir_cos, dodge, bJump, bDuck, bIdle].
        // All values are pre-tanh logits (SAC applies np.tanh on ingest).
        float[] action = new float[6];
        if (curr == null || curr.playerPawn == null) {
            return action;
        }

        // Direction: world-velocity unit vector — matches the joint policy's
        // movement output so SAC sees the same action distribution as the
        // live bot writes.
        float vx = curr.playerPawn.velocityX_norm;
        float vy = curr.playerPawn.velocityY_norm;
        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        float sin = 0f, cos = 0f;
        if (speed >= 0.01f) {
            sin = vy / speed;
            cos = vx / speed;
        }
        action[0] = toPreTanh(sin);
        action[1] = toPreTanh(cos);

        // Dodge initiation: 1 only on the frame where DodgeState transitions
        // NONE → directional.
        boolean dodgeInit = false;
        DodgeState currState = curr.playerPawn.dodgeState;
        if (currState != null && currState != DodgeState.NONE
                && currState != DodgeState.ACTIVE && currState != DodgeState.DONE) {
            DodgeState prevState = (prev != null && prev.playerPawn != null)
                    ? prev.playerPawn.dodgeState : null;
            dodgeInit = (prevState == DodgeState.NONE);
        }
        action[2] = toPreTanh(dodgeInit ? 1f : 0f);

        // Jump (held): bJump lives one level deeper than bDuck in the DTO graph.
        boolean jump = false;
        boolean idle = false;
        if (curr.playerPawn.playerPawn != null) {
            KeyboardMoveDto bJump = curr.playerPawn.playerPawn.bJump;
            jump = (bJump != null && bJump.value);
            KeyboardMoveDto moveIdle = curr.playerPawn.playerPawn.moveIdle;
            idle = (moveIdle != null && moveIdle.value);
        }
        action[3] = toPreTanh(jump ? 1f : 0f);

        // Duck (held).
        KeyboardMoveDto bDuck = curr.playerPawn.bDuck;
        boolean duck = (bDuck != null && bDuck.value);
        action[4] = toPreTanh(duck ? 1f : 0f);

        // Idle: 1 when player explicitly pressed no-move key (moveIdle).
        // Distinguishes intentional rest from being stuck against a wall.
        action[5] = toPreTanh(idle ? 1f : 0f);

        return action;
    }

    /**
     * Joint full-action vector (10 dims) matching {@link RLPawnActionDecoder}
     * indexering. Layout:
     * <pre>
     *   [0..5] = movement[moveDir_sin, moveDir_cos, dodge, bJump, bDuck, bIdle]
     *   [6]    = yawDelta_norm  (pre-tanh)
     *   [7]    = pitchDelta_norm (pre-tanh)
     *   [8]    = fire   (pre-tanh van {@code playerPawn.fireActive})
     *   [9]    = altFire (pre-tanh van {@code playerPawn.altFireActive})
     * </pre>
     *
     * <p>Fire state komt uit {@code fireActive}/{@code altFireActive} op de
     * outer PlayerDto — dat is de UC-bound fire-flag NA cooldown gating. Voor
     * human play is dit het beste beschikbare signaal: de speler drukte de
     * vuurknop in én het wapen vuurde daadwerkelijk. Pre-cooldown intent op
     * {@code playerPawn.bFire.value_norm} is wel beschikbaar maar geeft een
     * hogere fire-rate dan wat de live bot policy zou registreren, dus we
     * houden de UC-bound versie aan voor consistentie.</p>
     */
    private static float[] jointPawnAction(GameStateDto prev, GameStateDto curr,
                                                  float maxYawStep, float maxPitchStep) {
        float[] action = new float[RLPawnActionDecoder.ACTIONS_LENGTH];

        float[] mov = movementAction(prev, curr);
        System.arraycopy(mov, 0, action, RLPawnActionDecoder.IDX_MOVE_SIN, mov.length);

        float[] vr = viewRotationAction(prev, curr, maxYawStep, maxPitchStep);
        action[RLPawnActionDecoder.IDX_YAW] = vr[0];
        action[RLPawnActionDecoder.IDX_PITCH] = vr[1];

        boolean fire = curr != null && curr.playerPawn != null && curr.playerPawn.fireActive;
        boolean altFire = curr != null && curr.playerPawn != null && curr.playerPawn.altFireActive;
        action[RLPawnActionDecoder.IDX_FIRE] = toPreTanh(fire ? 1f : 0f);
        action[RLPawnActionDecoder.IDX_ALTFIRE] = toPreTanh(altFire ? 1f : 0f);

        return action;
    }

    private static float[] viewRotationAction(GameStateDto prev, GameStateDto curr,
                                                float maxYawStep, float maxPitchStep) {
        ViewRotationDto pVr = (prev != null && prev.playerPawn != null) ? prev.playerPawn.viewRotation : null;
        ViewRotationDto cVr = (curr != null && curr.playerPawn != null) ? curr.playerPawn.viewRotation : null;
        if (pVr == null || cVr == null) return new float[]{0f, 0f};

        int prevX = pVr.x & 0xFFFF;
        int currX = cVr.x & 0xFFFF;
        int dyaw = ((currX - prevX + 32768) % 65536) - 32768;
        int dpitch = cVr.y - pVr.y;

        return new float[]{
                toPreTanh(clamp(dyaw / maxYawStep, -1f, 1f)),
                toPreTanh(clamp(dpitch / maxPitchStep, -1f, 1f))
        };
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void cleanRecGzFiles(Path dir, String modelKey) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(p) && name.endsWith(".rec.gz")) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            System.out.println("[" + modelKey + "] cleaned " + removed
                    + " stale .rec.gz from " + dir);
        }
    }

    private static List<Path> listSessionsInModelDir(Path modelDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(modelDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) || name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    out.add(p);
                }
            }
        }
        out.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return out;
    }

    private record Args(String jsonSource, String outputDir) {}

    private static Args parseArgs(String[] args) {
        String json = null, out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json-source" -> json = args[++i];
                case "--output-dir" -> out = args[++i];
                case "--help", "-h" -> {
                    System.out.println("Usage: ConvertJsonRecordingsToRecGzMain"
                            + " [--json-source <path>] [--output-dir <path>]");
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }
        return new Args(json, out);
    }

    /** Per-model state held open across all sessions and inner zips. */
    private static final class ModelState {
        final String modelKey;
        final int seqLen;
        final RealtimeSequenceInputBuilder inputBuilder;
        final RawGameplayRecorder recorder;
        final float maxYawStep;
        final float maxPitchStep;

        ModelState(String modelKey, int seqLen, RealtimeSequenceInputBuilder inputBuilder,
                    RawGameplayRecorder recorder, float maxYawStep, float maxPitchStep) {
            this.modelKey = modelKey;
            this.seqLen = seqLen;
            this.inputBuilder = inputBuilder;
            this.recorder = recorder;
            this.maxYawStep = maxYawStep;
            this.maxPitchStep = maxPitchStep;
        }
    }
}
