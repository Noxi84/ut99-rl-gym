package aiplay.rl.recording;

import aiplay.dto.GameStateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import java.util.logging.Logger;

/**
 * Raw bot-gameplay recorder. Captures (GameStateDto + state vector + action +
 * log_probs) per tick into rolling .rec.gz files so an offline replay tool can
 * regenerate experience .npz batches with tweaked rewards.
 *
 * <p>One recorder per model — each model executor has its own instance, with
 * its own output subdirectory and tick frequency. The recorder is hot-path
 * safe: {@link #onTick} only enqueues; an internal flush thread does all I/O.
 *
 * <p>File format (after gunzip): a stream of length-prefixed records. The first
 * record is the JSON header ({@link Header}); subsequent records are tick
 * payloads with binary state/action arrays + JSON-encoded GameStateDto.
 *
 * <pre>
 *   header_record:
 *     uint8  type = 0x01
 *     uint32 jsonLen
 *     bytes  jsonHeader (UTF-8)
 *
 *   tick_record:
 *     uint8  type = 0x02
 *     uint32 totalLen   (everything after this field, for skip-on-error)
 *     int32  tickIdx
 *     int64  gameTimeMs
 *     int32  targetIdx       (-1 = no target_head)
 *     float  targetLogProb
 *     float  actionLogProb   (v2+, NaN when unavailable)
 *     int32  stateLen
 *     float[stateLen]
 *     int32  actionLen
 *     float[actionLen]
 *     int32  gameStateJsonLen
 *     bytes  gameStateJson (UTF-8)
 *
 *   eof_record:
 *     uint8  type = 0x00
 * </pre>
 */
public final class RawGameplayRecorder implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RawGameplayRecorder.class.getName());

    public static final byte TYPE_HEADER = 0x01;
    public static final byte TYPE_TICK = 0x02;
    public static final byte TYPE_EOF = 0x00;

    public static final int FORMAT_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final String modelKey;
    private final String machineId;
    private final String runId;
    private final Path outputDir;
    private final int rotateSeconds;
    /** When true, {@link #onTick} blocks instead of dropping if the queue is
     *  full. Used by offline converters where every sample matters. The live
     *  bot keeps {@code blockOnFull=false} to never stall the 60 Hz tick. */
    private final boolean blockOnFull;

    private final ArrayBlockingQueue<Tick> queue;
    private final Thread flushThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong enqueuedCount = new AtomicLong(0);
    private final AtomicLong writtenCount = new AtomicLong(0);

    public RawGameplayRecorder(String modelKey,
                                Path outputDir,
                                int rotateSeconds,
                                int queueCapacity) {
        this(modelKey, outputDir, rotateSeconds, queueCapacity, /*blockOnFull=*/ false);
    }

    public RawGameplayRecorder(String modelKey,
                                Path outputDir,
                                int rotateSeconds,
                                int queueCapacity,
                                boolean blockOnFull) {
        this.modelKey = modelKey;
        this.outputDir = outputDir;
        this.rotateSeconds = rotateSeconds;
        this.blockOnFull = blockOnFull;
        this.machineId = resolveMachineId();
        this.runId = String.format("%s_%s_%d", machineId, modelKey, System.currentTimeMillis());
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to create raw recording directory: " + outputDir, e);
        }

        this.flushThread = new Thread(this::flushLoop,
                "RawGameplayRecorder-" + modelKey);
        this.flushThread.setDaemon(true);
        this.flushThread.start();

        LOG.info("RAW_REC_INIT model=" + modelKey + " runId=" + runId
                + " dir=" + outputDir + " rotateSeconds=" + rotateSeconds
                + " queueCap=" + queueCapacity);
    }

    /** Hot-path entry — non-blocking. Drops on queue full and counts the drop. */
    public void onTick(float[][][] input, float[] action, GameStateDto gameState) {
        onTick(input, action, gameState, -1, 0.0f);
    }

    /** Hot-path entry with shooting target-head log_prob. */
    public void onTick(float[][][] input,
                        float[] action,
                        GameStateDto gameState,
                        int targetIdx,
                        float targetLogProb) {
        onTick(input, action, gameState, Float.NaN, targetIdx, targetLogProb);
    }

    /** Hot-path entry with behavior action log_prob and shooting target-head
     * log_prob. */
    public void onTick(float[][][] input,
                        float[] action,
                        GameStateDto gameState,
                        float actionLogProb,
                        int targetIdx,
                        float targetLogProb) {
        if (!running.get()) return;
        if (input == null || action == null || gameState == null) return;

        // Flatten input[0] of shape [seqLen][nFeatures] into one float[].
        float[] flatState = flatten(input);

        Tick tick = new Tick(
                System.currentTimeMillis(),
                gameState.timestampMillis,
                gameState.frameNumber,
                flatState,
                action.clone(),
                actionLogProb,
                targetIdx,
                targetLogProb,
                gameState.deepCopy()
        );

        if (blockOnFull) {
            try {
                queue.put(tick);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            enqueuedCount.incrementAndGet();
            return;
        }
        if (!queue.offer(tick)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                LOG.warning("RAW_REC_DROP model=" + modelKey + " dropped=" + dropped
                        + " queueSize=" + queue.size());
            }
            return;
        }
        enqueuedCount.incrementAndGet();
    }

    private static float[] flatten(float[][][] input) {
        float[][] matrix = input[0];
        int seqLen = matrix.length;
        int nFeatures = matrix[0].length;
        float[] flat = new float[seqLen * nFeatures];
        for (int t = 0; t < seqLen; t++) {
            System.arraycopy(matrix[t], 0, flat, t * nFeatures, nFeatures);
        }
        return flat;
    }

    private void flushLoop() {
        DataOutputStream out = null;
        long currentFileStartMs = 0L;
        int rotationIdx = 0;
        Header header = null;

        try {
            while (running.get() || !queue.isEmpty()) {
                Tick tick;
                try {
                    tick = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (tick == null) continue;

                long now = System.currentTimeMillis();
                if (out == null
                        || (now - currentFileStartMs) >= rotateSeconds * 1000L) {
                    if (out != null) {
                        closeFile(out);
                    }
                    rotationIdx++;
                    if (header == null) {
                        header = new Header(FORMAT_VERSION, modelKey, machineId, runId,
                                tick.flatState.length, tick.action.length, now);
                    }
                    out = openFile(rotationIdx, header);
                    currentFileStartMs = now;
                }

                writeTick(out, tick);
                writtenCount.incrementAndGet();
            }
        } catch (IOException e) {
            LOG.severe("RAW_REC_IO_ERROR model=" + modelKey + " msg=" + e.getMessage());
        } finally {
            if (out != null) {
                closeFile(out);
            }
            LOG.info("RAW_REC_CLOSE model=" + modelKey
                    + " written=" + writtenCount.get()
                    + " enqueued=" + enqueuedCount.get()
                    + " dropped=" + droppedCount.get());
        }
    }

    private DataOutputStream openFile(int rotationIdx, Header header) throws IOException {
        String fileName = String.format("%s_%04d.rec.gz", runId, rotationIdx);
        Path path = outputDir.resolve(fileName);
        OutputStream fileStream = Files.newOutputStream(path);
        OutputStream gzipStream = new GZIPOutputStream(new BufferedOutputStream(fileStream, 64 * 1024));
        DataOutputStream out = new DataOutputStream(gzipStream);
        writeHeader(out, header);
        LOG.info("RAW_REC_FILE_OPEN model=" + modelKey + " path=" + path);
        return out;
    }

    private void closeFile(DataOutputStream out) {
        try {
            out.writeByte(TYPE_EOF);
            out.flush();
            out.close();
        } catch (IOException e) {
            LOG.warning("RAW_REC_CLOSE_ERROR model=" + modelKey + " msg=" + e.getMessage());
        }
    }

    private void writeHeader(DataOutputStream out, Header header) throws IOException {
        byte[] headerJson = MAPPER.writeValueAsBytes(header);
        out.writeByte(TYPE_HEADER);
        out.writeInt(headerJson.length);
        out.write(headerJson);
    }

    private void writeTick(DataOutputStream out, Tick tick) throws IOException {
        byte[] gsJson = MAPPER.writeValueAsBytes(tick.gameState);

        // Compute total payload length so a reader can skip a corrupt record.
        // total = 4 (tickIdx) + 8 (gameTimeMs) + 4 (targetIdx)
        //       + 4 (targetLogProb) + 4 (actionLogProb)
        //       + 4 (stateLen) + stateLen*4
        //       + 4 (actionLen) + actionLen*4
        //       + 4 (gsJsonLen) + gsJson.length
        int totalLen = 4 + 8 + 4 + 4 + 4
                + 4 + tick.flatState.length * 4
                + 4 + tick.action.length * 4
                + 4 + gsJson.length;

        out.writeByte(TYPE_TICK);
        out.writeInt(totalLen);
        out.writeInt(tick.frameNumber);
        out.writeLong(tick.gameTimeMs);
        out.writeInt(tick.targetIdx);
        out.writeFloat(tick.targetLogProb);
        out.writeFloat(tick.actionLogProb);

        out.writeInt(tick.flatState.length);
        for (float v : tick.flatState) {
            out.writeFloat(v);
        }
        out.writeInt(tick.action.length);
        for (float v : tick.action) {
            out.writeFloat(v);
        }
        out.writeInt(gsJson.length);
        out.write(gsJson);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            // Wait without a timeout — the flush loop is guaranteed to exit once
            // the queue drains (no external blocker), and a premature timeout
            // here would leave the GZIPOutputStream half-written and corrupt the
            // .rec.gz file on disk. For the live bot this is fine: shutdown is
            // a one-shot event and we want the corpus to remain intact.
            flushThread.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public long getWrittenCount() { return writtenCount.get(); }
    public long getDroppedCount() { return droppedCount.get(); }
    public long getEnqueuedCount() { return enqueuedCount.get(); }
    public String getRunId() { return runId; }

    private static String resolveMachineId() {
        String env = System.getenv("UT99_MACHINE_ID");
        if (env != null && !env.isBlank()) return env.trim();
        String sys = System.getProperty("UT99_MACHINE_ID");
        if (sys != null && !sys.isBlank()) return sys.trim();
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            return hostname.length() > 8 ? hostname.substring(0, 8) : hostname;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** File header (one per .rec.gz). Serialized as JSON. */
    public record Header(
            int version,
            String modelKey,
            String machineId,
            String runId,
            int stateSize,
            int actionSize,
            long startTimeMs
    ) {}

    /** Internal queue payload — one tick worth of capture. */
    private record Tick(
            long wallclockMs,
            long gameTimeMs,
            int frameNumber,
            float[] flatState,
            float[] action,
            float actionLogProb,
            int targetIdx,
            float targetLogProb,
            GameStateDto gameState
    ) {}
}
