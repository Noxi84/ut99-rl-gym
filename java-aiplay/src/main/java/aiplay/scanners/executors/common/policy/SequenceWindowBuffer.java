package aiplay.scanners.executors.common.policy;

import aiplay.dto.GridFrame;
import aiplay.scanners.executors.util.TimeAlignedWindowBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Synchronized ring buffer for sequence-based policy executors.
 * Appends game state frames, trims to capacity, and builds
 * time-aligned windows via {@link TimeAlignedWindowBuilder}.
 *
 * <p>Thread-safe: all public methods are synchronized.
 */
public final class SequenceWindowBuffer {

    private final Deque<GridFrame> ring;
    private final int maxCapacity;

    /**
     * @param capacityHint expected window size (e.g. numberOfColumns from model config)
     * @param minCapacity  minimum ring capacity — use 1024 for viewrotation (needs
     *                     long history for irregular sampling), 256 for movement
     */
    public SequenceWindowBuffer(int capacityHint, int minCapacity) {
        int floor = Math.max(64, minCapacity);
        this.maxCapacity = Math.max(floor, capacityHint);
        this.ring = new ArrayDeque<>(Math.max(floor, capacityHint * 4));
    }

    public synchronized void append(List<GridFrame> newFrames) {
        if (newFrames == null) return;
        for (GridFrame gs : newFrames) {
            if (gs == null) continue;
            ring.addLast(gs);
            while (ring.size() > maxCapacity) {
                if (ring.pollFirst() == null) break;
            }
        }
    }

    public synchronized boolean hasEnoughFor(int n) {
        return ring.size() >= n;
    }

    public synchronized List<GridFrame> buildAlignedWindow(int windowN, int csvFps) {
        // The alignment code only uses the last second. Avoid copying the full
        // 1024-frame ring on every policy tick for every bot.
        List<GridFrame> src = recentFramesForAlignment(windowN);
        return TimeAlignedWindowBuilder.buildAlignedWindow(src, windowN, csvFps, false);
    }

    private List<GridFrame> recentFramesForAlignment(int windowN) {
        GridFrame latest = ring.peekLast();
        if (latest == null) {
            return Collections.emptyList();
        }

        long latestMs = latest.gridMs();
        long cutoffMs = latestMs - 1000L;
        int expectedRecent = Math.min(ring.size(), Math.max(windowN * 2, 64));
        List<GridFrame> out = new ArrayList<>(expectedRecent);

        Iterator<GridFrame> it = ring.descendingIterator();
        while (it.hasNext()) {
            GridFrame frame = it.next();
            if (frame == null) {
                continue;
            }
            long gridMs = frame.gridMs();
            if (!out.isEmpty() && (gridMs < cutoffMs || gridMs > latestMs)) {
                break;
            }
            if (gridMs >= cutoffMs && gridMs <= latestMs) {
                out.add(frame);
            }
        }
        Collections.reverse(out);
        return out;
    }

    public synchronized GridFrame latestFrame() {
        return ring.peekLast();
    }

    public synchronized void clear() {
        ring.clear();
    }

    /**
     * Fills the buffer with {@code n} copies of {@code frame}.
     * Use after {@link #clear()} on mission transition so that
     * {@link #hasEnoughFor(int)} is immediately satisfied and the LSTM
     * receives coherent context instead of a null-padded blackout window.
     */
    public synchronized void prefill(GridFrame frame, int n) {
        for (int i = 0; i < n; i++) {
            ring.addLast(frame);
        }
        while (ring.size() > maxCapacity) {
            ring.pollFirst();
        }
    }
}
