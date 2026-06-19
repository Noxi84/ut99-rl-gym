package aiplay.scanners.model.writer.trainingcsvwriter.reader;

import aiplay.runtime.context.ActiveMapContext;
import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureService;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ReaderFacade {

    private final ReaderService readerService = new ReaderService();
    private final TrainingFeatureService trainingFeatureService = TrainingFeatureService.shared();

    /**
     * Zoekt in .../{subfolder}/{modelKey}/ naar:
     * - subdirs met recordingSessionId (worden automatisch gezipt)
     * - zip files met recordingSessionId in de naam
     * <p>
     * Geeft een lijst terug met paden (Strings) naar ZIP files.
     */
    public static List<String> getGameplaySources(String sessionId, String subfolder, String modelKey) throws IOException {
        String gameStatesDir = (modelKey == null || modelKey.isEmpty())
                ? aiplay.runtime.config.SessionPaths.getSessionDir() + "/" + subfolder
                : aiplay.runtime.config.SessionPaths.getSessionDir() + "/" + subfolder + "/" + modelKey;

        File f = new File(gameStatesDir);
        if (!f.exists()) {
            boolean created = f.mkdirs();
            if (created) {
                System.out.println("Created " + gameStatesDir);
            }
        }

        return discoverZipsInDirectory(Paths.get(gameStatesDir));
    }

    /**
     * Returns gameplay sources from an explicit source directory (for distributed CSV workers).
     */
    public static List<String> getGameplaySourcesFromDir(String sourceDir) throws IOException {
        Path dir = Paths.get(sourceDir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        return discoverZipsInDirectory(dir);
    }

    /**
     * Returns gameplay sources filtered to only the given ZIP filenames (for distributed shard workers).
     * The zipNames list contains just filenames (not paths); they are resolved against sourceDir.
     */
    public static List<String> getGameplaySourcesFromList(String sourceDir, List<String> zipNames) throws IOException {
        Path dir = Paths.get(sourceDir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String name : zipNames) {
            Path zip = dir.resolve(name);
            if (Files.isRegularFile(zip) && zip.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                result.add(zip.toAbsolutePath().toString());
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static List<String> discoverZipsInDirectory(Path modelRoot) throws IOException {
        if (!Files.exists(modelRoot) || !Files.isDirectory(modelRoot)) {
            return Collections.emptyList();
        }

        Set<Path> zips = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelRoot)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // recordingSessionId directory -> zip
                    if (containsAtLeastOneJson(entry)) {
                        Path zipPath = ZipUtils.zipDirectoryToSiblingZip(entry);
                        // directory verwijderen na zip, zodat alles "genormaliseerd" is naar zip
                        ZipUtils.deleteDirectoryRecursive(entry);
                        zips.add(zipPath);
                    }
                } else if (Files.isRegularFile(entry) && entry.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    zips.add(entry);
                }
            }
        }

        return zips.stream()
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList());
    }

    private static boolean containsAtLeastOneJson(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lees één sessiebron (zip of directory) in en converteer direct naar DTO’s.
     * Elke ruwe GameState wordt onmiddellijk geconverteerd en daarna vrijgegeven,
     * zodat er nooit een volledige List&lt;GameState&gt; naast een List&lt;GameStateDto&gt; in geheugen staat.
     */
    public List<GameStateDto> getGameStates(String sessionId, String dirOrZipPath) throws Exception {
        List<GameStateDto> out = new ArrayList<>();
        String previousContext = ActiveMapContext.get();
        try {
            readerService.forEachGameState(dirOrZipPath, gs -> {
                String rawMapName = (gs.MapInfo != null) ? gs.MapInfo.MapName : null;
                ActiveMapContext.set(rawMapName);
                out.add(trainingFeatureService.createGameStateDtoFromJsonSession(sessionId, gs));
            });
        } finally {
            if (previousContext == null) {
                ActiveMapContext.clear();
            } else {
                ActiveMapContext.set(previousContext);
            }
        }
        return out;
    }
}
