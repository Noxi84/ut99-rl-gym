package aiplay;

import aiplay.runtime.context.ActiveMapContext;
import aiplay.runtime.config.SessionPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scan {@code <sessions>/json-recording-sessions/} for unique map names present in recordings.
 *
 * <p>For each source (ZIP or folder with {@code *.json} files), parses the first JSON frame
 * it finds, reads {@code MapInfo.MapName}, and normalizes via {@link ActiveMapContext#normalize(String)}.
 * The deduplicated set is printed to stdout, one map name per line — suitable for piping
 * into {@code extract-map-bounds.sh}.
 *
 * <p>Usage:
 *   java -cp java-aiplay-1.0.jar aiplay.DiscoverMapsInRecordingsMain [--root &lt;dir&gt;]
 */
public final class DiscoverMapsInRecordingsMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WALK_DEPTH = 3;

    private DiscoverMapsInRecordingsMain() {}

    public static void main(String[] args) throws Exception {
        Path rootArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--root".equals(args[i]) && i + 1 < args.length) {
                rootArg = Path.of(args[++i]);
            }
        }
        final Path root = (rootArg != null)
            ? rootArg
            : Path.of(SessionPaths.getSessionDir(), "json-recording-sessions");

        Set<String> maps = new TreeSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root, WALK_DEPTH)) {
                walk.forEach(p -> {
                    if (Files.isRegularFile(p) && hasExt(p, ".zip")) {
                        scanZip(p, maps);
                    } else if (Files.isDirectory(p) && !p.equals(root)) {
                        scanDir(p, maps);
                    }
                });
            }
        } else {
            System.err.println("Recordings root not a directory: " + root);
        }

        for (String m : maps) {
            System.out.println(m);
        }
    }

    private static boolean hasExt(Path p, String ext) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(ext);
    }

    private static void scanZip(Path zipPath, Set<String> out) {
        try (ZipFile z = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (!e.getName().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    String m = extractMap(in);
                    if (m != null) {
                        out.add(m);
                        return;
                    }
                }
            }
        } catch (Exception ignore) {
            // unreadable zip: skip
        }
    }

    private static void scanDir(Path dir, Set<String> out) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    String m = extractMap(in);
                    if (m != null) {
                        out.add(m);
                        return;
                    }
                }
            }
        } catch (Exception ignore) {
            // unreadable dir: skip
        }
    }

    private static String extractMap(InputStream in) {
        try {
            JsonNode root = MAPPER.readTree(in);
            JsonNode mi = root.path("MapInfo");
            if (!mi.isObject()) return null;
            String raw = mi.path("MapName").asText("");
            String normalized = ActiveMapContext.normalize(raw);
            return normalized.isEmpty() ? null : normalized;
        } catch (Exception ignore) {
            return null;
        }
    }
}
