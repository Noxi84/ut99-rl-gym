package aiplay;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.GameStateDto;
import aiplay.runtime.config.SessionPaths;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.rl.ExperienceCollector;
import aiplay.rl.PerModelExperienceRecorder;
import aiplay.rl.RLConfig;
import aiplay.rl.RewardComputer;
import aiplay.scanners.model.ITrainingModel;
import aiplay.scanners.model.TrainingModelTrainingCsvWriter;
import aiplay.scanners.model.resolver.rlpawn.RLPawnTrainingModelComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * SAC-from-Demonstrations (SACfD) converter: turns a HUMAN gameplay demo
 * (json-recording-session zip, e.g. one full CTF flag-run by player "Noxi")
 * into joint {@code rl_pawn} SAC replay-buffer {@code .npz} files, so the
 * demo's successful trajectory can later be injected into the SAC replay
 * buffer. The bot collects experience with deterministic continuous actions
 * and cannot discover the flag-run on its own; feeding these transitions to
 * the critic lets it learn the flag-run's high Q, pulling the policy toward it.
 *
 * <p><b>This tool only PRODUCES + writes the npz to a SEPARATE demo-experience
 * directory.</b> It does not inject into the live buffer, does not deploy, and
 * does not touch any trainer state.</p>
 *
 * <h2>Identical-to-live guarantee</h2>
 * State feature vectors, the 10-dim action labels, and the per-tick reward /
 * decomposition / teammate-state / target-attribution are all produced by the
 * SAME production components used by the BC CSV pipeline
 * ({@link TrainingModelTrainingCsvWriter#streamJointTransitions}) and the live
 * experience pipeline ({@link PerModelExperienceRecorder} →
 * {@link ExperienceCollector}). No feature/action/reward logic is
 * reimplemented here.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp java-aiplay/target/java-aiplay-1.0.jar aiplay.DemoToExperienceMain \
 *        &lt;demoZipOrDir&gt; [--out &lt;dir&gt;] [--reward-group &lt;role&gt;]
 * </pre>
 * Default out = {@code &lt;sessionDir&gt;/demo-experience/rl_pawn/}.
 * Default reward-group = derived from the zip filename prefix
 * ({@code Attack_*} → {@code Attack}); pass {@code --reward-group} to override.
 *
 * <p>Accepts either the zip path
 * ({@code .../json-recording-sessions/rl_pawn/Attack_CTF-Face-...zip}) or a
 * directory of {@code <millis>.json} demo frames (e.g. {@code /tmp/demo}).
 * A directory is wrapped on the fly: the underlying reader handles both.</p>
 */
public final class DemoToExperienceMain {

    private static final String MODEL_KEY = "rl_pawn";

    /**
     * Timestamp gap (ms) between two consecutive post-filter demo frames above
     * which we treat the boundary as a death/respawn (or recording pause) and
     * reset the episode. Post-grouping frame spacing is well under 100ms (the
     * recording runs ~30fps); a death drops ~2s of dead+respawn-cooldown frames.
     * 500ms sits safely between the two regimes.
     */
    private static final long DEATH_GAP_MS = 500L;

    private DemoToExperienceMain() {}

    public static void main(String[] args) throws Exception {
        SessionPaths.ensureSessionDirsExist();
        aiplay.scanners.feature.contract.FeatureContractRepository.shared().validateAll();

        Args cli = parseArgs(args);

        // Tag the JVM machineId so produced files are batch_demo-*.npz and can
        // never collide with live-bot batch_<host>-*.npz files.
        String origMachineId = resolveMachineId();
        System.setProperty("UT99_MACHINE_ID", "demo-" + origMachineId);

        // Init player identity to the recording player (e.g. "Noxi") so every
        // JSON→DTO converter resolves the recorded human instead of the bot.
        GlobalConfigRepository globalConfig = GlobalConfigRepository.shared();
        String recordingName = globalConfig.recording().playerName();
        PlayerIdentityContext.init(
                recordingName,
                globalConfig.player().team(),
                globalConfig.player().role());

        Path source = Path.of(cli.demoPath).toAbsolutePath();
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Demo source does not exist: " + source);
        }
        // Resolve the source the reader will consume. A directory of <millis>.json
        // frames is read directly; a zip is read directly. Filename prefix drives
        // the reward-group role (Attack_* → Attack), so keep the original name.
        String zipPath = source.toString();
        String rewardGroup = (cli.rewardGroup != null)
                ? cli.rewardGroup
                : rewardGroupFromName(source.getFileName().toString());

        Path outDir = (cli.outDir != null)
                ? Path.of(cli.outDir).toAbsolutePath()
                : Path.of(SessionPaths.getSessionDir(), "demo-experience", MODEL_KEY);
        Files.createDirectories(outDir);

        System.out.println("=== Demo → SAC experience (SACfD) ===");
        System.out.println("Recording player: " + recordingName);
        System.out.println("Source:           " + zipPath);
        System.out.println("Reward group:     " + rewardGroup);
        System.out.println("Output dir:       " + outDir);
        System.out.println("Machine tag:      demo-" + origMachineId);
        System.out.println();

        ITrainingModel model = new RLPawnTrainingModelComponent();
        TrainingModelTrainingCsvWriter writer = model.getTrainingModelCsvWriter();

        int seqLen = model.getCsvNumberOfColumns();
        int nFeatures = model.getInputFeatures().size();
        int stateSize = seqLen * nFeatures;
        int actionSize = model.getCsvTargetFeatures().size();
        System.out.printf("Model: seqLen=%d nFeatures=%d stateSize=%d actionSize=%d%n",
                seqLen, nFeatures, stateSize, actionSize);

        RLConfig rlCfg = new RLConfig(MODEL_KEY, rewardGroup);
        RewardComputer rewardComputer = new RewardComputer(rlCfg);
        rewardComputer.setSessionId("demo-" + MODEL_KEY);

        // Separate demo-experience collector — never the live rl-replay-buffer dir.
        ExperienceCollector collector = new ExperienceCollector(
                stateSize, actionSize, rlCfg, outDir, /*policyRole=*/ 0);

        // Drive the SAME recorder the live bot uses: identical reward/decomp/
        // teammate/target-attribution/done logic. The human did not sample from
        // a policy, so actionLogProb=NaN (Python recomputes from the actor) and
        // targetIdx=-1 (recorder derives the provisional target_label itself,
        // exactly as the offline rec.gz replay does).
        PerModelExperienceRecorder recorder =
                new PerModelExperienceRecorder(collector, rewardComputer, MODEL_KEY, /*recordInterval=*/ 1);

        // Death/respawn boundary detection (offline). The shared JSON→DTO
        // converters do NOT populate PlayerDto.deaths (only the live UDP path
        // does), so the recorder's built-in deaths-increment done-detection can
        // never fire here. Worse, groupByElapsedTime DROPS dead + respawn-cooldown
        // frames, so a death manifests only as a large timestamp gap between two
        // surviving frames (~2s) — every normal post-filter gap is <100ms. We
        // therefore resetEpisode() across that gap so no bogus transition
        // teleports across the death (mirrors the live done=reset, minus the
        // death's negative reward — which we intentionally exclude: a SUCCESS
        // demo should teach the critic the flag-run, not Noxi's deaths).
        long[] prevTsMs = {Long.MIN_VALUE};
        long started = System.currentTimeMillis();
        long handed = writer.streamJointTransitions(
                "demo", zipPath,
                (flatState, action, currentFrame) -> {
                    long ts = currentFrame.timestampMillis;
                    if (prevTsMs[0] != Long.MIN_VALUE
                            && (ts - prevTsMs[0]) > DEATH_GAP_MS) {
                        recorder.resetEpisode();
                    }
                    prevTsMs[0] = ts;
                    recorder.onTickFromFlat(flatState, action, currentFrame,
                            Float.NaN, /*targetIdx=*/ -1, /*targetLogProb=*/ 0.0f);
                },
                // Role-pass boundary: no cross-role transitions. (Attack_* yields
                // a single role, so this fires once at the end.) Also resets the
                // timestamp tracker for the next role pass.
                () -> { recorder.resetEpisode(); prevTsMs[0] = Long.MIN_VALUE; });

        recorder.flush();
        // Flush is async inside ExperienceCollector; give the writer pool time to
        // finish before the JVM exits so the last batch lands on disk.
        Thread.sleep(1500);

        long elapsed = System.currentTimeMillis() - started;
        long npzCount;
        try (Stream<Path> s = Files.list(outDir)) {
            npzCount = s.filter(p -> p.getFileName().toString().endsWith(".npz")).count();
        }

        System.out.println();
        System.out.printf("=== Done in %.1fs ===%n", elapsed / 1000.0);
        System.out.println("Transitions handed to recorder: " + handed);
        System.out.println("Transitions recorded:           " + recorder.getRecordCount());
        System.out.println("npz files in out dir:           " + npzCount);
        System.out.println();
        System.out.println("Produced npz files:");
        listNpz(outDir);
    }

    private static String rewardGroupFromName(String filename) {
        if (filename == null) return PlayerIdentityContext.effectiveRole();
        int us = filename.indexOf('_');
        if (us <= 0) return PlayerIdentityContext.effectiveRole();
        String prefix = filename.substring(0, us);
        return switch (prefix) {
            case "Attack", "Cover", "Defend", "DeathMatch" -> prefix;
            // "Default_" maps to 4 roles in BC; for a single demo injection pick
            // Attack (the flag-run role). Override with --reward-group if needed.
            case "Default" -> "Attack";
            default -> PlayerIdentityContext.effectiveRole();
        };
    }

    private static String resolveMachineId() {
        String env = System.getenv("UT99_MACHINE_ID");
        if (env != null && !env.isBlank()) return env.trim();
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            return hostname.length() > 8 ? hostname.substring(0, 8) : hostname;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void listNpz(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".npz"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            System.out.printf("  %s (%.1f KB)%n",
                                    p.getFileName(), size / 1024.0);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            System.out.println("  (could not list: " + e.getMessage() + ")");
        }
    }

    // ===== arg parsing =====

    private record Args(String demoPath, String outDir, String rewardGroup) {}

    private static Args parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            throw new IllegalArgumentException("Missing <demoZipOrDir> argument");
        }
        String demoPath = null;
        String outDir = null;
        String rewardGroup = null;
        List<String> positionals = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = args[++i];
                case "--reward-group" -> rewardGroup = args[++i];
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("Unknown arg: " + args[i]);
                    }
                    positionals.add(args[i]);
                }
            }
        }
        if (positionals.isEmpty()) {
            throw new IllegalArgumentException("Missing <demoZipOrDir> argument");
        }
        demoPath = positionals.get(0);
        return new Args(demoPath, outDir, rewardGroup);
    }

    private static void printUsage() {
        System.out.println("Usage: DemoToExperienceMain <demoZipOrDir> [--out <dir>] [--reward-group <role>]");
        System.out.println("  <demoZipOrDir>   path to a json-recording-session zip OR a dir of <millis>.json frames");
        System.out.println("  --out <dir>      output dir (default: <sessionDir>/demo-experience/rl_pawn/)");
        System.out.println("  --reward-group   reward role (default: derived from zip filename prefix, e.g. Attack)");
    }
}
