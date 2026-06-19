package aiplay;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.runtime.config.SessionPaths;
import aiplay.scanners.model.TrainingModelService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Standalone CSV generator for training data.
 *
 * Converts pro gameplay JSON recordings into training CSVs that trainBC.py uses
 * for behavioral cloning pre-training of the RL policy network.
 *
 * Reads ZIPs/JSON from: <sessions_dir>/json-recording-sessions/
 * Output goes to:       <sessions_dir>/csv-training-data/
 *
 * Usage:
 *   java -cp java-aiplay/target/java-aiplay-1.0.jar aiplay.GenerateTrainingCsvMain
 */
public class GenerateTrainingCsvMain {

    public static void main(String[] args) {
        SessionPaths.ensureSessionDirsExist();
        aiplay.scanners.feature.contract.FeatureContractRepository.shared().validateAll();

        // Initialize player identity with the recording player name so all JSON-to-DTO
        // converters find the recorded player (e.g. "Noxi") instead of the bot name ("MrPython").
        GlobalConfigRepository globalConfig = GlobalConfigRepository.shared();
        String recordingName = globalConfig.recording().playerName();
        PlayerIdentityContext.init(
            recordingName,
            globalConfig.player().team(),
            globalConfig.player().role());
        System.out.println("Recording player name: " + recordingName);

        String sessionDir = SessionPaths.getSessionDir();

        // Parse CLI arguments
        CliArgs cli = parseCliArgs(args);

        // Generate CSV
        TrainingModelService service = new TrainingModelService();

        if (cli.hasOutputDir()) {
            // Distributed mode: single model with custom output dir
            // Optionally with explicit source-dir and zip-list-file for fine-grained sharding
            System.out.println("=== Distributed CSV generation ===");
            System.out.println("Model:      " + cli.model);
            System.out.println("Output dir: " + cli.outputDir);
            if (cli.sourceDir != null) System.out.println("Source dir: " + cli.sourceDir);
            if (cli.zipListFile != null) System.out.println("ZIP list:   " + cli.zipListFile);
            if (cli.runId != null) System.out.println("Run ID:     " + cli.runId);
            if (cli.shardId != null) System.out.println("Shard ID:   " + cli.shardId);
            System.out.println();

            long start = System.currentTimeMillis();
            try {
                Files.createDirectories(Path.of(cli.outputDir));

                if (cli.zipListFile != null && cli.sourceDir != null) {
                    // Fine-grained: explicit ZIP list from a source directory
                    List<String> zipNames = Files.readAllLines(Path.of(cli.zipListFile)).stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                    System.out.println("ZIPs to process: " + zipNames.size());
                    service.createTrainingCsvFilesDistributed("default", cli.model,
                            cli.sourceDir, zipNames, cli.outputDir, cli.runId, cli.shardId);
                } else {
                    // Whole-model: process all ZIPs from standard location, write to custom output
                    service.createTrainingCsvFilesWithOutputDir("default", cli.model, cli.outputDir);
                }

                long elapsed = System.currentTimeMillis() - start;
                System.out.println("=== Done (" + elapsed + " ms) ===");
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            System.out.println();
            System.out.println("CSV output directory:");
            listOutputFiles(Path.of(cli.outputDir));
        } else {
            // Standard mode: process all ZIPs for each model
            List<String> modelKeys = cli.modelKeys.isEmpty() ? service.getModelKeys() : List.copyOf(cli.modelKeys);

            System.out.println("Sessions dir: " + sessionDir);
            System.out.println("Model keys: " + modelKeys);
            System.out.println("JSON source: " + sessionDir + "/json-recording-sessions/");
            System.out.println("CSV output:  " + sessionDir + "/csv-training-data/<modelKey>/");
            System.out.println();

            System.out.println("=== Generating CSV ===");
            long start = System.currentTimeMillis();

            try {
                for (String modelKey : modelKeys) {
                    System.out.println("=== Generating CSV for " + modelKey + " ===");
                    service.createTrainingCsvFiles("default", modelKey);
                }
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("=== Done (" + elapsed + " ms) ===");
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            System.out.println();
            System.out.println("CSV output directory:");
            listOutputFiles(Path.of(sessionDir + "/csv-training-data"));
        }
    }

    private static class CliArgs {
        String model;
        String sourceDir;
        String outputDir;
        String zipListFile;
        String runId;
        String shardId;
        LinkedHashSet<String> modelKeys = new LinkedHashSet<>();

        boolean hasOutputDir() {
            return model != null && outputDir != null;
        }
    }

    private static CliArgs parseCliArgs(String[] args) {
        CliArgs cli = new CliArgs();
        if (args == null) return cli;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model" -> cli.model = args[++i];
                case "--source-dir" -> cli.sourceDir = args[++i];
                case "--output-dir" -> cli.outputDir = args[++i];
                case "--zip-list-file" -> cli.zipListFile = args[++i];
                case "--run-id" -> cli.runId = args[++i];
                case "--shard-id" -> cli.shardId = args[++i];
                default -> {
                    if (!args[i].startsWith("--") && !args[i].isBlank()) {
                        cli.modelKeys.add(args[i].trim());
                    }
                }
            }
        }
        return cli;
    }

    private static void listOutputFiles(Path dir) {
        if (!Files.exists(dir)) {
            System.out.println("  (no output directory)");
            return;
        }
        try (var walk = Files.walk(dir, 2)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            String sizeStr = (size > 1024 * 1024)
                                    ? String.format("%.1f MB", size / (1024.0 * 1024.0))
                                    : String.format("%.1f KB", size / 1024.0);
                            System.out.println("  " + dir.relativize(p) + " (" + sizeStr + ")");
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            System.out.println("  (could not list: " + e.getMessage() + ")");
        }
    }
}
