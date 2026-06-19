package aiplay.rl.recording;

import aiplay.dto.GameStateDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Streaming reader for {@link RawGameplayRecorder} files. Used by the offline
 * replay tool and by roundtrip tests. Single-threaded, sequential.
 *
 * <p>Usage:
 * <pre>
 *   try (RawGameplayReader r = RawGameplayReader.open(path)) {
 *       Header header = r.header();
 *       Tick tick;
 *       while ((tick = r.nextTick()) != null) {
 *           // ...
 *       }
 *   }
 * </pre>
 */
public final class RawGameplayReader implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataInputStream in;
    private final RawGameplayRecorder.Header header;

    private RawGameplayReader(DataInputStream in, RawGameplayRecorder.Header header) {
        this.in = in;
        this.header = header;
    }

    public static RawGameplayReader open(Path path) throws IOException {
        InputStream raw = Files.newInputStream(path);
        InputStream unzipped = new GZIPInputStream(new BufferedInputStream(raw, 64 * 1024));
        DataInputStream in = new DataInputStream(unzipped);

        byte type = in.readByte();
        if (type != RawGameplayRecorder.TYPE_HEADER) {
            in.close();
            throw new IOException("Expected header record (0x01), got 0x"
                    + Integer.toHexString(type & 0xFF) + " at start of " + path);
        }
        int jsonLen = in.readInt();
        byte[] jsonBytes = in.readNBytes(jsonLen);
        if (jsonBytes.length != jsonLen) {
            in.close();
            throw new EOFException("Truncated header in " + path);
        }
        RawGameplayRecorder.Header header = MAPPER.readValue(jsonBytes,
                RawGameplayRecorder.Header.class);
        return new RawGameplayReader(in, header);
    }

    public RawGameplayRecorder.Header header() {
        return header;
    }

    /** Reads the next tick. Returns null at clean EOF. */
    public Tick nextTick() throws IOException {
        byte type;
        try {
            type = in.readByte();
        } catch (EOFException eof) {
            return null;
        }
        if (type == RawGameplayRecorder.TYPE_EOF) {
            return null;
        }
        if (type != RawGameplayRecorder.TYPE_TICK) {
            throw new IOException("Expected tick record (0x02) or EOF (0x00), got 0x"
                    + Integer.toHexString(type & 0xFF));
        }

        int totalLen = in.readInt();
        // Read totalLen bytes into a buffer, parse from there. This way a corrupt
        // tick record doesn't desynchronize the reader.
        byte[] payload = in.readNBytes(totalLen);
        if (payload.length != totalLen) {
            throw new EOFException("Truncated tick record (expected " + totalLen
                    + " bytes, got " + payload.length + ")");
        }
        DataInputStream p = new DataInputStream(new java.io.ByteArrayInputStream(payload));

        int frameNumber = p.readInt();
        long gameTimeMs = p.readLong();
        int targetIdx = p.readInt();
        float targetLogProb = p.readFloat();
        float actionLogProb = header.version() >= 2 ? p.readFloat() : Float.NaN;

        int stateLen = p.readInt();
        float[] flatState = new float[stateLen];
        for (int i = 0; i < stateLen; i++) flatState[i] = p.readFloat();

        int actionLen = p.readInt();
        float[] action = new float[actionLen];
        for (int i = 0; i < actionLen; i++) action[i] = p.readFloat();

        int gsJsonLen = p.readInt();
        byte[] gsJson = p.readNBytes(gsJsonLen);
        if (gsJson.length != gsJsonLen) {
            throw new EOFException("Truncated gameState JSON in tick frame=" + frameNumber);
        }
        GameStateDto gameState = MAPPER.readValue(gsJson, GameStateDto.class);

        return new Tick(frameNumber, gameTimeMs, flatState, action,
                actionLogProb, targetIdx, targetLogProb, gameState);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    public record Tick(
            int frameNumber,
            long gameTimeMs,
            float[] flatState,
            float[] action,
            float actionLogProb,
            int targetIdx,
            float targetLogProb,
            GameStateDto gameState
    ) {}
}
