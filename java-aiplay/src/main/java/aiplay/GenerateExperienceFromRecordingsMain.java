package aiplay;

import aiplay.config.ModelConfigRepository;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.model.ModelConfig;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.runtime.config.SessionPaths;
import aiplay.dto.GameStateDto;
import aiplay.rl.ExperienceCollector;
import aiplay.rl.PerModelExperienceRecorder;
import aiplay.rl.RLConfig;
import aiplay.rl.RewardComputer;
import aiplay.rl.recording.RawGameplayReader;
import aiplay.rl.recording.RawGameplayRecorder;
import aiplay.rl.rewards.core.RewardBreakdown;
import aiplay.rl.rewards.core.RewardSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Replay tool: reads .rec.gz raw-gameplay recordings produced by
 * {@link aiplay.rl.recording.RawGameplayRecorder} and generates experience
 * .npz batches as if the bot had just played, but with the current
 * {@link RewardComputer} configuration applied.
 *
 * <p>Lets you record a session once and re-derive training experience after
 * tweaking {@code rewards.json} — minutes instead of hours.
 *
 * <p>Usage:
 * <pre>
 *   java -cp java-aiplay-1.0.jar aiplay.GenerateExperienceFromRecordingsMain \
 *        [--recordings-dir &lt;path&gt;] \
 *        [--output-dir     &lt;path&gt;] \
 *        [--models         &lt;k1,k2,...&gt;]
 * </pre>
 *
 * Defaults: recordings-dir = {@code &lt;sessionDir&gt;/raw-gameplay-recordings},
 * output-dir = {@code &lt;sessionDir&gt;/rl-replay-buffer}, models = all model
 * subdirs found in recordings-dir.
 */
public final class GenerateExperienceFromRecordingsMain {

    private GenerateExperienceFromRecordingsMain() {}

    public static void main(String[] args) throws Exception {
        SessionPaths.ensureSessionDirsExist();

        // Tag the JVM's machineId so ExperienceCollector writes batch_replay-*.npz
        // and we never collide with live-bot batch_<host>-*.npz files.
        String origMachineId = resolveMachineId();
        System.setProperty("UT99_MACHINE_ID", "replay-" + origMachineId);

        // Replay tool reads recordings produced by live bots; each bot had its own
        // role at recording time, but the .rec.gz header doesn't carry it yet.
        // Use the runtime player config until per-recording role tagging lands.
        GlobalConfigRepository globalCfg = GlobalConfigRepository.shared();
        PlayerIdentityContext.init(
            globalCfg.player().name(),
            globalCfg.player().team(),
            globalCfg.player().role());

        Args cli = parseArgs(args);

        Path recordingsRoot = (cli.recordingsDir != null)
                ? Path.of(cli.recordingsDir)
                : Path.of(SessionPaths.getSessionDir(), "raw-gameplay-recordings");
        Path outputRoot = (cli.outputDir != null)
                ? Path.of(cli.outputDir)
                : Path.of(SessionPaths.getSessionDir(), "rl-replay-buffer");

        if (!Files.isDirectory(recordingsRoot)) {
            throw new IllegalArgumentException("Recordings dir does not exist: " + recordingsRoot);
        }

        List<String> modelKeys = (cli.models != null && !cli.models.isEmpty())
                ? cli.models
                : discoverModelSubdirs(recordingsRoot);
        if (modelKeys.isEmpty()) {
            throw new IllegalStateException("No models to process. Pass --models or place "
                    + ".rec.gz files under " + recordingsRoot + "/<modelKey>/");
        }

        System.out.println("=== Replay → experience ===");
        System.out.println("Recordings: " + recordingsRoot);
        System.out.println("Output:     " + outputRoot);
        System.out.println("Models:     " + modelKeys);
        System.out.println();

        Summary total = new Summary();
        long started = System.currentTimeMillis();
        for (String modelKey : modelKeys) {
            Summary perModel = replayModel(modelKey, recordingsRoot, outputRoot, cli.breakdown);
            total.add(perModel);
            System.out.printf("[%s] files=%d ticks=%d transitions=%d episodes=%d%n",
                    modelKey, perModel.files, perModel.ticksRead,
                    perModel.transitionsWritten, perModel.episodesEnded);
        }
        long elapsed = System.currentTimeMillis() - started;
        System.out.printf("=== Done in %.1fs — totals: files=%d transitions=%d ===%n",
                elapsed / 1000.0, total.files, total.transitionsWritten);
    }

    private static Summary replayModel(String modelKey, Path recordingsRoot, Path outputRoot,
                                         boolean breakdown) throws IOException {
        Path modelRecDir = recordingsRoot.resolve(modelKey);
        if (!Files.isDirectory(modelRecDir)) {
            System.out.println("[" + modelKey + "] No recordings dir — skip");
            return new Summary();
        }

        ModelConfig modelCfg = ModelConfigRepository.shared().get(modelKey);
        int expectedStateSize = modelCfg.sequenceLength()
                * modelCfg.features().inputFeatures().size();
        int expectedActionSize = modelCfg.features().targetFeatures().size();

        RLConfig rlCfg = new RLConfig(modelKey, PlayerIdentityContext.effectiveRole());
        RewardComputer rewardComputer = new RewardComputer(rlCfg);
        rewardComputer.setSessionId("replay-" + modelKey);

        Path outputDir = outputRoot.resolve(modelKey);
        Files.createDirectories(outputDir);

        // Joint VR+shooting schrijft reward decomp + target_label supervision
        // in elke NPZ — collector heeft die arrays nu altijd geactiveerd.
        ExperienceCollector collector = new ExperienceCollector(
                expectedStateSize, expectedActionSize, rlCfg, outputDir,
                /*policyRole=*/ 0);

        // Group .rec.gz files by runId (filename prefix matches header.runId).
        // Each run is one continuous episode-stream; episodes inside a run are
        // delimited by death (deaths increment) or playerPawn==null.
        List<Path> files;
        try (Stream<Path> s = Files.list(modelRecDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".rec.gz"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        Map<String, List<Path>> byRun = new LinkedHashMap<>();
        for (Path file : files) {
            try (RawGameplayReader r = RawGameplayReader.open(file)) {
                RawGameplayRecorder.Header h = r.header();
                if (!modelKey.equals(h.modelKey())) {
                    System.err.println("WARN model mismatch: file=" + file
                            + " header.modelKey=" + h.modelKey() + " expected=" + modelKey + " — skipping");
                    continue;
                }
                if (h.stateSize() != expectedStateSize || h.actionSize() != expectedActionSize) {
                    System.err.println("WARN size mismatch in " + file
                            + ": header=(state=" + h.stateSize() + ",action=" + h.actionSize() + ")"
                            + " expected=(state=" + expectedStateSize + ",action=" + expectedActionSize + ")"
                            + " — likely produced with different feature config; skipping");
                    continue;
                }
                byRun.computeIfAbsent(h.runId(), k -> new ArrayList<>()).add(file);
            } catch (IOException e) {
                System.err.println("WARN unreadable file: " + file + " — " + e.getMessage());
            }
        }

        Summary summary = new Summary();
        BreakdownAccumulator modelAcc = breakdown ? new BreakdownAccumulator() : null;
        for (Map.Entry<String, List<Path>> entry : byRun.entrySet()) {
            BreakdownAccumulator runAcc = breakdown ? new BreakdownAccumulator() : null;
            replayRun(entry.getKey(), entry.getValue(), collector, rewardComputer,
                modelKey, summary, runAcc);
            if (runAcc != null) {
                runAcc.print("[" + modelKey + "] runId=" + entry.getKey());
                modelAcc.merge(runAcc);
            }
        }

        if (modelAcc != null) {
            modelAcc.print("[" + modelKey + "] TOTAL");
        }

        collector.flush();
        return summary;
    }

    private static void replayRun(String runId,
                                    List<Path> files,
                                    ExperienceCollector collector,
                                    RewardComputer rewardComputer,
                                    String modelKey,
                                    Summary summary,
                                    BreakdownAccumulator acc) {
        // Replay delegateert het transition-recording aan PerModelExperienceRecorder
        // zodat replay 1:1 dezelfde provisional label + retro-fill + recordJoint pad
        // gebruikt als de live bot — single source of truth.
        PerModelExperienceRecorder jointRecorder =
            new PerModelExperienceRecorder(collector, rewardComputer, modelKey, /*recordInterval=*/ 1);
        RawGameplayReader.Tick prev = null;

        for (Path file : files) {
            summary.files++;
            try (RawGameplayReader r = RawGameplayReader.open(file)) {
                RawGameplayReader.Tick curr;
                while ((curr = r.nextTick()) != null) {
                    summary.ticksRead++;
                    long preCount = jointRecorder.getRecordCount();
                    jointRecorder.onTickFromFlat(curr.flatState(), curr.action(),
                        curr.gameState(), curr.actionLogProb(),
                        curr.targetIdx(), curr.targetLogProb());
                    long delta = jointRecorder.getRecordCount() - preCount;
                    summary.transitionsWritten += delta;
                    if (acc != null && prev != null) {
                        RewardBreakdown b = rewardComputer.computeWithBreakdown(
                            prev.gameState(), curr.gameState(), prev.action());
                        acc.add(b);
                    }
                    if (prev != null && isEpisodeEnd(prev.gameState(), curr.gameState())) {
                        summary.episodesEnded++;
                    }
                    prev = curr;
                }
            } catch (IOException e) {
                System.err.println("WARN failed reading " + file + " — " + e.getMessage());
            }
        }

        // Run boundary — geen cross-run transitions.
        jointRecorder.resetEpisode();
    }

    private static boolean isEpisodeEnd(GameStateDto prev, GameStateDto curr) {
        if (curr == null || curr.playerPawn == null) return true;
        if (prev == null || prev.playerPawn == null) return false;
        return curr.playerPawn.deaths > prev.playerPawn.deaths;
    }

    private static List<String> discoverModelSubdirs(Path root) throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
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

    // ===== arg parsing =====

    private record Args(String recordingsDir, String outputDir, List<String> models, boolean breakdown) {}

    private static Args parseArgs(String[] args) {
        String recordingsDir = null;
        String outputDir = null;
        List<String> models = new ArrayList<>();
        boolean breakdown = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--recordings-dir" -> recordingsDir = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--models" -> {
                    String csv = args[++i];
                    for (String m : csv.split(",")) {
                        if (!m.isBlank()) models.add(m.trim());
                    }
                }
                case "--breakdown" -> breakdown = true;
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }
        return new Args(recordingsDir, outputDir, models, breakdown);
    }

    private static void printUsage() {
        System.out.println("Usage: GenerateExperienceFromRecordingsMain"
                + " [--recordings-dir <path>] [--output-dir <path>] [--models <k1,k2,...>]"
                + " [--breakdown]");
    }

    private static final class Summary {
        long files = 0;
        long ticksRead = 0;
        long transitionsWritten = 0;
        long episodesEnded = 0;

        void add(Summary other) {
            files += other.files;
            ticksRead += other.ticksRead;
            transitionsWritten += other.transitionsWritten;
            episodesEnded += other.episodesEnded;
        }
    }

    private static final class BreakdownAccumulator {
        long ticks = 0;
        double total = 0;
        final double[] sums = new double[RewardSignal.COUNT];
        final long[] nonZero = new long[RewardSignal.COUNT];

        void add(RewardBreakdown b) {
            ticks++;
            total += b.total();
            for (RewardSignal s : RewardSignal.values()) {
                double v = b.value(s);
                sums[s.ordinal()] += v;
                if (v != 0.0) nonZero[s.ordinal()]++;
            }
        }

        void merge(BreakdownAccumulator other) {
            ticks += other.ticks;
            total += other.total;
            for (int i = 0; i < sums.length; i++) {
                sums[i] += other.sums[i];
                nonZero[i] += other.nonZero[i];
            }
        }

        void print(String label) {
            System.out.printf("  %s: ticks=%d totalReward=%+.3f mean=%+.5f%n",
                    label, ticks, total, total / Math.max(ticks, 1));
            // Sort by absolute sum desc — surface dominant signals first.
            Integer[] idx = new Integer[RewardSignal.COUNT];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(Math.abs(sums[b]), Math.abs(sums[a])));
            for (int i : idx) {
                if (sums[i] == 0.0 && nonZero[i] == 0) continue;
                System.out.printf("    %-28s sum=%+10.3f mean=%+9.5f hits=%6d (%5.1f%%)%n",
                        RewardSignal.values()[i].fieldName(), sums[i],
                        sums[i] / Math.max(ticks, 1),
                        nonZero[i],
                        100.0 * nonZero[i] / Math.max(ticks, 1));
            }
        }
    }
}
