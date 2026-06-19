package aiplay.recordlauncher.x11;

import aiplay.config.global.GlobalConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class UT99WindowDetector {

    // Per-instance state (multi-bot mode uses one detector per BT, stored on Blackboard)
    private volatile String cachedWindowId;
    private volatile long cachedWindowIdAtMs;
    private static final long WINDOW_ID_CACHE_MS = 1000L;

    // Per-display detectors for multi-bot mode (keyed by display name)
    private static final java.util.concurrent.ConcurrentHashMap<String, UT99WindowDetector> PER_DISPLAY =
        new java.util.concurrent.ConcurrentHashMap<>();
    // Legacy static singleton for backward compatibility (single-instance mode)
    private static final UT99WindowDetector LEGACY = new UT99WindowDetector();

    public UT99WindowDetector() {
    }

    /** Returns the detector for the current thread's display (multi-bot) or the legacy singleton. */
    private static UT99WindowDetector current() {
        String display = CurrentDisplay.get();
        if (display == null) return LEGACY;
        return PER_DISPLAY.computeIfAbsent(display, k -> new UT99WindowDetector());
    }

    /** Static entry point. */
    public static void waitForUT99WindowActive() {
        current().waitForWindowActive();
    }

    // ===== Instance methods =====

    public String findWindowIdOrNull() {
        long now = System.currentTimeMillis();
        String cached = cachedWindowId;
        if (cached != null && (now - cachedWindowIdAtMs) < WINDOW_ID_CACHE_MS) {
            return cached;
        }

        String expectedWindowName = GlobalConfigRepository.shared().view().windowName();
        String focused = focusedWindowIdIfMatches(expectedWindowName);
        if (focused != null) {
            cachedWindowId = focused;
            cachedWindowIdAtMs = now;
            return focused;
        }

        String found = searchWindowIdByName(expectedWindowName);
        if (found != null) {
            cachedWindowId = found;
            cachedWindowIdAtMs = now;
            return found;
        }
        return null;
    }

    public void waitForWindowActive() {
        String expectedWindowName = GlobalConfigRepository.shared().view().windowName();
        long start = System.currentTimeMillis();
        long timeoutMs = 120_000L;

        while (true) {
            String id = findWindowIdOrNull();
            if (id != null && !id.isBlank()) {
                try {
                    DisplayAwareProcessBuilder.create(ExternalTools.xdotool(), "windowactivate", "--sync", id).start().waitFor();
                } catch (Exception ignore) {
                }
                break;
            }

            long now = System.currentTimeMillis();
            if ((now - start) > timeoutMs) {
                ConsoleLog.warn("⏸ UT99 window not found within " + (timeoutMs / 1000) + "s (expected name contains: '" + expectedWindowName + "'). Continuing anyway.");
                break;
            }

            ConsoleLog.info("⏸ UT99 window not found yet. Waiting... (expected name contains: '" + expectedWindowName + "')");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static String focusedWindowIdIfMatches(String expectedWindowName) {
        try {
            Process proc = DisplayAwareProcessBuilder.create(ExternalTools.xdotool(), "getwindowfocus", "getwindowname").start();
            Scanner scanner = new Scanner(proc.getInputStream()).useDelimiter("\\A");
            String out = scanner.hasNext() ? scanner.next() : "";
            if (out == null || out.isBlank()) {
                return null;
            }
            String[] lines = out.split("\\R");
            if (lines.length < 2) {
                return null;
            }
            String id = lines[0].trim();
            String name = lines[1].trim();
            if (!name.toLowerCase().contains(expectedWindowName.toLowerCase())) {
                return null;
            }
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String searchWindowIdByName(String expectedWindowName) {
        try {
            Process proc = DisplayAwareProcessBuilder.create(ExternalTools.xdotool(), "search", "--name", expectedWindowName).start();
            Scanner scanner = new Scanner(proc.getInputStream()).useDelimiter("\\A");
            String out = scanner.hasNext() ? scanner.next() : "";
            if (out == null || out.isBlank()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    ids.add(trimmed);
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            return ids.get(ids.size() - 1);
        } catch (Exception e) {
            return null;
        }
    }
}
