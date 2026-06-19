package aiplay.scanners.model.writer.trainingcsvwriter.manifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects per-ZIP metrics during CSV generation and writes a manifest.json
 * to the shard output directory. Used for:
 * - Auditable run history (which ZIPs were processed, when, how fast)
 * - Historical throughput tracking (bytes/sec per machine for scheduling)
 * - Partial rerun support (completed vs failed shards)
 */
public class CsvShardManifest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final String runId;
    private final String model;
    private final String shardId;
    private final String startTime;

    private final List<ZipMetrics> zipMetricsList = new ArrayList<>();
    private long peakHeapMb;

    public CsvShardManifest(String runId, String model, String shardId) {
        this.runId = runId;
        this.model = model;
        this.shardId = shardId;
        this.startTime = LocalDateTime.now().format(FMT);
    }

    public void addZipResult(String zipName, long zipBytes, int framesIn,
                             int buckets, long rowsOut, long runtimeMs) {
        zipMetricsList.add(new ZipMetrics(zipName, zipBytes, framesIn, buckets, rowsOut, runtimeMs));
    }

    public void samplePeakHeap() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        if (usedMb > peakHeapMb) {
            peakHeapMb = usedMb;
        }
    }

    public void write(String outputDir, String status, int totalCsvParts, long totalCsvBytes) {
        String endTime = LocalDateTime.now().format(FMT);

        long totalZipBytes = 0;
        long totalFramesIn = 0;
        long totalRowsOut = 0;
        long totalRuntimeMs = 0;

        JSONArray zipsArray = new JSONArray();
        for (ZipMetrics z : zipMetricsList) {
            totalZipBytes += z.zipBytes;
            totalFramesIn += z.framesIn;
            totalRowsOut += z.rowsOut;
            totalRuntimeMs += z.runtimeMs;

            JSONObject zObj = new JSONObject();
            zObj.put("zip_name", z.zipName);
            zObj.put("zip_bytes", z.zipBytes);
            zObj.put("frames_in", z.framesIn);
            zObj.put("buckets", z.buckets);
            zObj.put("rows_out", z.rowsOut);
            zObj.put("runtime_ms", z.runtimeMs);
            zipsArray.put(zObj);
        }

        JSONObject totals = new JSONObject();
        totals.put("zip_count", zipMetricsList.size());
        totals.put("total_zip_bytes", totalZipBytes);
        totals.put("total_frames_in", totalFramesIn);
        totals.put("total_rows_out", totalRowsOut);
        totals.put("total_csv_parts", totalCsvParts);
        totals.put("total_csv_bytes", totalCsvBytes);
        totals.put("runtime_ms", totalRuntimeMs);
        totals.put("peak_heap_mb", peakHeapMb);

        JSONObject root = new JSONObject();
        root.put("run_id", runId);
        root.put("model", model);
        root.put("shard_id", shardId);
        root.put("start_time", startTime);
        root.put("end_time", endTime);
        root.put("status", status);
        root.put("zips", zipsArray);
        root.put("totals", totals);

        try {
            Path manifestPath = Path.of(outputDir, "manifest.json");
            Files.createDirectories(manifestPath.getParent());
            Files.writeString(manifestPath, root.toString(2));
            System.out.println("MANIFEST written: " + manifestPath
                    + " status=" + status
                    + " zips=" + zipMetricsList.size()
                    + " rows=" + totalRowsOut
                    + " peakHeap=" + peakHeapMb + "MB");
        } catch (IOException e) {
            System.err.println("WARNING: could not write manifest to " + outputDir + ": " + e.getMessage());
        }
    }

    private record ZipMetrics(String zipName, long zipBytes, int framesIn,
                              int buckets, long rowsOut, long runtimeMs) {}
}
