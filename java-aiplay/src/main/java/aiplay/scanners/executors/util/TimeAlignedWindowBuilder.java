package aiplay.scanners.executors.util;

import aiplay.dto.GridFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TimeAlignedWindowBuilder {

    private TimeAlignedWindowBuilder() {}

    /**
     * Bouwt een tijd-gealigneerd venster van exact 'windowSize' uit de LAATSTE seconde.
     * - Filtert eerst alle frames ouder dan (lastTs - 1000 ms) weg.
     * - Align op CSV-FPS raster (dt = 1000 / csvFps).
     * - Oldest → Newest (zelfde volgorde als training CSV).
     * - Pad met oudste recente frame als er te weinig historie is.
     * - Fallback: als er 0 recente frames zijn, herhaal laatste bekende frame.
     */
    public static List<GridFrame> buildAlignedWindow(List<GridFrame> frames,
                                                      int windowSize,
                                                      int csvFps) {
        return buildAlignedWindow(frames, windowSize, csvFps, true);
    }

    public static List<GridFrame> buildAlignedWindow(List<GridFrame> frames,
                                                      int windowSize,
                                                      int csvFps,
                                                      boolean needsSort) {
        if (frames == null || frames.isEmpty() || windowSize <= 0 || csvFps <= 0) {
            return Collections.emptyList();
        }

        List<GridFrame> sorted;
        if (needsSort) {
            sorted = new ArrayList<>(frames);
            sorted.sort(Comparator.comparingLong(GridFrame::gridMs));
        } else {
            sorted = frames;
        }

        long lastTs = sorted.get(sorted.size() - 1).gridMs();
        long oldestAllowedTs = lastTs - 1000L;

        // 2) Knip alles weg dat ouder is dan 1s. De input is hier al
        // chronologisch, dus een lower-bound vermijdt elke tick een lineaire
        // scan over de hele ringbuffer.
        int startIndex = lowerBoundByGridMs(sorted, oldestAllowedTs);
        List<GridFrame> recent = startIndex < sorted.size()
                ? sorted.subList(startIndex, sorted.size())
                : Collections.<GridFrame>emptyList();

        // 3) Fallback als er geen frames in de laatste seconde zitten
        if (recent.isEmpty()) {
            List<GridFrame> fallback = new ArrayList<GridFrame>(windowSize);
            GridFrame last = sorted.get(sorted.size() - 1);
            for (int i = 0; i < windowSize; i++) {
                fallback.add(last);
            }
            return fallback;
        }

        // 4) Target timestamps op CSV-raster binnen die laatste seconde
        long dt = 1000L / (long) csvFps; // geen rounding (stabiel)
        if (dt <= 0L) dt = 1L;

        // 5) Voor elke target: dichtstbijzijnd recent frame
        GridFrame[] out = new GridFrame[windowSize];

        int j = recent.size() - 1;
        for (int i = windowSize - 1; i >= 0; i--) {
            int stepsFromEnd = windowSize - 1 - i;
            long target = lastTs - ((long) stepsFromEnd) * dt;
            if (target < oldestAllowedTs) {
                target = oldestAllowedTs;
            }

            while (j > 0 && recent.get(j).gridMs() > target) {
                j--;
            }

            GridFrame best = recent.get(j);
            long bestDiff = Math.abs(best.gridMs() - target);

            if (j + 1 < recent.size()) {
                GridFrame cand = recent.get(j + 1);
                long candDiff = Math.abs(cand.gridMs() - target);
                if (candDiff < bestDiff) {
                    best = cand;
                }
            }

            out[i] = best;
        }

        List<GridFrame> result = new ArrayList<>(windowSize);
        for (GridFrame frame : out) {
            result.add(frame);
        }
        return result;
    }

    /** Gemaksmethode: één seconde venster met windowSize == csvFps. */
    public static List<GridFrame> buildOneSecondWindow(List<GridFrame> frames, int csvFps) {
        return buildAlignedWindow(frames, csvFps, csvFps);
    }

    private static int lowerBoundByGridMs(List<GridFrame> frames, long targetMs) {
        int lo = 0;
        int hi = frames.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (frames.get(mid).gridMs() < targetMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
