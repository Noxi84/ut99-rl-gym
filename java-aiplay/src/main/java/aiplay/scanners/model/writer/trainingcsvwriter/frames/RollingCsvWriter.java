package aiplay.scanners.model.writer.trainingcsvwriter.frames;

import aiplay.scanners.model.writer.FileDao;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class RollingCsvWriter implements AutoCloseable {

    private final FileDao fileDao;
    private final String baseFilename;
    private final String headerLine;
    private final long maxBytesPerFile;

    private int partIndex;
    private String currentFilename;
    private long currentBytesWritten;
    private long totalRows;
    private long totalBytesAllParts;

    private BufferedWriter currentWriter;

    public RollingCsvWriter(FileDao fileDao, String baseFilename, String headerLine, long maxBytesPerFile) {
        this.fileDao = fileDao;
        this.baseFilename = baseFilename;
        this.headerLine = headerLine;
        this.maxBytesPerFile = maxBytesPerFile;
        this.partIndex = 1;
        this.currentFilename = null;
        this.currentWriter = null;
    }

    public synchronized String appendLine(String csvLine) {
        int lineBytes = csvLine.length() + 1; // + newline (ASCII, so length ≈ bytes)
        if (currentFilename == null || currentBytesWritten + lineBytes > maxBytesPerFile) {
            rotateToNewPart();
        }
        try {
            currentWriter.write(csvLine);
            currentWriter.newLine();
            currentBytesWritten += lineBytes;
            totalRows++;
            return currentFilename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to append CSV line to " + currentFilename, e);
        }
    }

    public long getTotalRows() {
        return totalRows;
    }

    public long getTotalBytesWritten() {
        return totalBytesAllParts + currentBytesWritten;
    }

    public int getPartCount() {
        return currentFilename != null ? partIndex - 1 : 0;
    }

    private void rotateToNewPart() {
        closeQuietlyCurrentWriter();
        totalBytesAllParts += currentBytesWritten;

        currentFilename = buildPartFilename(partIndex);
        partIndex++;
        currentBytesWritten = 0;

        // header (overwrite) via FileDao (maakt dir aan)
        fileDao.writeFileOverwrite(currentFilename, headerLine);

        // open writer in append mode en hou open
        try {
            this.currentWriter = new BufferedWriter(new FileWriter(currentFilename, true));
        } catch (IOException e) {
            throw new RuntimeException("Failed to open CSV writer for " + currentFilename, e);
        }
    }

    private void closeQuietlyCurrentWriter() {
        if (currentWriter != null) {
            try {
                currentWriter.flush();
            } catch (IOException ignore) {
            }
            try {
                currentWriter.close();
            } catch (IOException ignore) {
            }
            currentWriter = null;
        }
    }

    private String buildPartFilename(int index) {
        int dot = baseFilename.lastIndexOf('.');
        String prefix;
        String ext;

        if (dot > 0) {
            prefix = baseFilename.substring(0, dot);
            ext = baseFilename.substring(dot); // ".csv"
        } else {
            prefix = baseFilename;
            ext = "";
        }

        return String.format("%s_part%05d%s", prefix, index, ext);
    }

    @Override
    public synchronized void close() {
        closeQuietlyCurrentWriter();
    }
}
