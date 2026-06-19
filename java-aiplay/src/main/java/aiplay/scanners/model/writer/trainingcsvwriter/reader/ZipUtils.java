package aiplay.scanners.model.writer.trainingcsvwriter.reader;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utilities voor:
 * - directory -> zip (met temp file + atomic move)
 * - directory tree delete
 */
public final class ZipUtils {

    private ZipUtils() {
    }

    public static Path zipDirectoryToSiblingZip(Path sessionDir) throws IOException {
        Objects.requireNonNull(sessionDir, "sessionDir");
        if (!Files.isDirectory(sessionDir)) {
            throw new IllegalArgumentException("Not a directory: " + sessionDir);
        }

        Path parent = sessionDir.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Directory has no parent: " + sessionDir);
        }

        String dirName = sessionDir.getFileName().toString();
        Path finalZip = parent.resolve(dirName + ".zip");

        // Als zip al bestaat: niet opnieuw maken.
        if (Files.exists(finalZip) && Files.isRegularFile(finalZip)) {
            return finalZip;
        }

        Path tmpZip = parent.resolve(dirName + ".zip.tmp");

        // Maak temp zip
        try (OutputStream os = Files.newOutputStream(tmpZip, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(os);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            // Voeg alle files onder sessionDir toe
            Files.walkFileTree(sessionDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path rel = sessionDir.relativize(file);
                    String zipName = rel.toString().replace("\\", "/");

                    ZipEntry entry = new ZipEntry(zipName);
                    // timestamp op entry
                    try {
                        entry.setTime(Files.getLastModifiedTime(file).toMillis());
                    } catch (Exception ignored) {
                        // ok
                    }
                    zos.putNextEntry(entry);

                    try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();

                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Atomic move naar definitieve zip (best effort)
        try {
            Files.move(tmpZip, finalZip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpZip, finalZip, StandardCopyOption.REPLACE_EXISTING);
        }

        return finalZip;
    }

    public static void deleteDirectoryRecursive(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
