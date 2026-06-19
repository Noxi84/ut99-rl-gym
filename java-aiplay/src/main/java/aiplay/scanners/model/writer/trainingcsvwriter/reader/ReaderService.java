package aiplay.scanners.model.writer.trainingcsvwriter.reader;

import aiplay.scanners.model.writer.FileDao;
import aiplay.ut99webmodel.GameState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ReaderService {

    private static final boolean DELETE_SOURCE_DIR_AFTER_ZIP = true;

    private final FileDao fileDao = new FileDao();

    /**
     * Process each valid GameState from a ZIP or directory source via a consumer.
     * Each GameState is passed to the consumer and then becomes GC-eligible immediately,
     * avoiding the need to hold all GameStates in memory simultaneously.
     */
    public void forEachGameState(String path, Consumer<GameState> consumer) throws Exception {
        Path p = Paths.get(path);

        if (!Files.exists(p)) {
            return;
        }

        if (Files.isDirectory(p)) {
            // Only normalize directories that actually hold loose JSON tick files
            // (the canonical RecordLauncher session layout). A directory whose
            // children are themselves session zips IS already canonical — zipping
            // it would create a useless zip-of-zips, and DELETE_SOURCE_DIR_AFTER_ZIP
            // would then wipe those session zips out.
            if (!containsLooseJson(p)) {
                return;
            }
            Path zipPath = ZipUtils.zipDirectoryToSiblingZip(p);

            if (DELETE_SOURCE_DIR_AFTER_ZIP) {
                ZipUtils.deleteDirectoryRecursive(p);
            }

            forEachGameStateInZip(zipPath, consumer);
            return;
        }

        if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            forEachGameStateInZip(p, consumer);
        }
    }

    private static boolean containsLooseJson(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            return stream.iterator().hasNext();
        }
    }

    private void forEachGameStateInZip(Path zipPath, Consumer<GameState> consumer) throws Exception {
        long zipLastModified = Files.getLastModifiedTime(zipPath).toMillis();

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            List<ZipEntryWithTs> entries = listJsonEntriesWithTimestampsSorted(zipFile, zipLastModified);

            for (ZipEntryWithTs e : entries) {
                ZipEntry entry = e.entry;
                long ts = e.timestampMillis;

                try (InputStream in = zipFile.getInputStream(entry)) {
                    GameState gameState = fileDao.readStream(in, ts);
                    if (gameState.MapInfo != null && gameState.MapInfo.RemainingTime != null) {
                        consumer.accept(gameState);
                    }
                }
            }
        }
    }

    private List<ZipEntryWithTs> listJsonEntriesWithTimestampsSorted(ZipFile zipFile, long zipLastModified) {
        List<ZipEntryWithTs> out = new ArrayList<>();

        Enumeration<? extends ZipEntry> en = zipFile.entries();
        while (en.hasMoreElements()) {
            ZipEntry ze = en.nextElement();
            if (ze.isDirectory()) {
                continue;
            }
            String name = ze.getName();
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }

            long ts = extractTimestampMillisFromZipEntryName(name);

            // fallback 1: zip entry time
            if (ts <= 0) {
                long t = ze.getTime();
                if (t > 0) {
                    ts = t;
                }
            }

            // fallback 2: zip file last modified
            if (ts <= 0) {
                ts = zipLastModified;
            }

            out.add(new ZipEntryWithTs(ze, ts));
        }

        // sorteer op timestamp, en bij gelijk op naam
        out.sort((a, b) -> {
            int c = Long.compare(a.timestampMillis, b.timestampMillis);
            if (c != 0) return c;
            return a.entry.getName().compareTo(b.entry.getName());
        });

        return out;
    }

    /**
     * Probeert millis te halen uit de bestandsnaam.
     * Voorbeelden die werken:
     * - 1766343707734.json
     * - 1766343707734_0001.json
     * - subdir/1766343707734.json
     */
    private long extractTimestampMillisFromZipEntryName(String zipEntryName) {
        if (zipEntryName == null || zipEntryName.isEmpty()) {
            return -1L;
        }

        // neem basename
        String base = zipEntryName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }

        // strip .json
        if (base.toLowerCase(Locale.ROOT).endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }

        // pak leading digits (tot eerste niet-digit)
        int i = 0;
        while (i < base.length() && Character.isDigit(base.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return -1L;
        }

        String digits = base.substring(0, i);
        try {
            long v = Long.parseLong(digits);

            // sanity: verwacht epoch millis (13 digits) maar laat 10 digits (epoch seconds) ook toe
            if (digits.length() == 10) {
                return v * 1000L;
            }
            return v;
        } catch (Exception ex) {
            return -1L;
        }
    }

    private static final class ZipEntryWithTs {
        private final ZipEntry entry;
        private final long timestampMillis;

        private ZipEntryWithTs(ZipEntry entry, long timestampMillis) {
            this.entry = entry;
            this.timestampMillis = timestampMillis;
        }
    }
}
