package aiplay.runtime.port;

/**
 * Port for time and cooperative pacing. The runtime kernel uses this
 * for FPS-aligned scheduling without direct System.nanoTime() or
 * LockSupport.parkNanos() calls.
 *
 * <p>Live adapter: {@code SystemClock} (wall-clock with cooperative parking).
 * Test adapter: deterministic clock for reproducible timing.</p>
 */
public interface RuntimeClock {

    long nanoTime();

    /**
     * Block until the target time is reached. Implementations should use
     * cooperative parking (not busy-spinning) to avoid CPU waste.
     */
    void waitUntilNano(long targetTimeNs);
}
