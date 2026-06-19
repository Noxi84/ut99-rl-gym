package aiplay.play.udpstate;

import java.util.HashMap;
import java.util.Map;

/**
 * Reassembles multi-packet state frames keyed by {@code frameId}. A frame may be
 * split across 1..N UDP packets; this buffers partial frames and returns the
 * concatenated payload once all slices for a frame have arrived.
 *
 * <p>Not thread-safe by design: {@link #accept} is called only from the
 * {@code UdpStateReceiver} reader thread. Bounded at {@link #MAX_PENDING_FRAMES}
 * pending frames; on overflow the frame furthest behind the current id is evicted
 * (counted as a drop) so a lost slice can never stall the buffer.
 */
final class FrameReassembler {

    static final int MAX_PENDING_FRAMES = 8;

    private final Map<Integer, PendingFrame> pending = new HashMap<>();
    private final ReceiverStats stats;

    FrameReassembler(ReceiverStats stats) {
        this.stats = stats;
    }

    /**
     * Add one packet slice. Returns the fully reassembled payload when this slice
     * completes its frame, otherwise {@code null}. Duplicate/out-of-range slices
     * and evictions are counted as drops in {@link ReceiverStats}.
     */
    byte[] accept(int frameId, int packetIdx, int packetCount, byte[] slice) {
        PendingFrame asm = pending.get(frameId);
        if (asm == null) {
            asm = new PendingFrame(packetCount);
            pending.put(frameId, asm);
            evictIfTooMany(frameId);
        }
        if (!asm.put(packetIdx, slice)) {
            stats.packetsDropped.incrementAndGet();
            return null;
        }
        if (asm.isComplete()) {
            byte[] combined = asm.concatenate();
            pending.remove(frameId);
            return combined;
        }
        return null;
    }

    private void evictIfTooMany(int currentFrameId) {
        if (pending.size() <= MAX_PENDING_FRAMES) return;
        int victim = -1;
        int maxDistance = -1;
        for (int fid : pending.keySet()) {
            int distance = (currentFrameId - fid) & 0xFFFF;
            if (distance > maxDistance) { maxDistance = distance; victim = fid; }
        }
        if (victim >= 0) { pending.remove(victim); stats.packetsDropped.incrementAndGet(); }
    }

    private static final class PendingFrame {
        final int packetCount;
        final byte[][] slices;
        int receivedCount;
        PendingFrame(int packetCount) {
            this.packetCount = packetCount;
            this.slices = new byte[packetCount][];
        }
        boolean put(int idx, byte[] payload) {
            if (idx < 0 || idx >= packetCount || slices[idx] != null) return false;
            slices[idx] = payload; receivedCount++; return true;
        }
        boolean isComplete() { return receivedCount == packetCount; }
        byte[] concatenate() {
            int total = 0;
            for (byte[] s : slices) total += s.length;
            byte[] out = new byte[total];
            int off = 0;
            for (byte[] s : slices) {
                System.arraycopy(s, 0, out, off, s.length);
                off += s.length;
            }
            return out;
        }
    }
}
