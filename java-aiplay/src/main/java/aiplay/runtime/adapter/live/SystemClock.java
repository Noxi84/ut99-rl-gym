package aiplay.runtime.adapter.live;

import aiplay.runtime.port.RuntimeClock;

import java.util.concurrent.locks.LockSupport;

/**
 * Live adapter: wall-clock with cooperative parking.
 * Uses 0.2ms slack to compensate for scheduler jitter.
 */
public final class SystemClock implements RuntimeClock {

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void waitUntilNano(long targetTimeNs) {
        while (true) {
            long now = System.nanoTime();
            long diff = targetTimeNs - now;
            if (diff <= 0) {
                return;
            }
            long parkNs = diff - 200_000L; // 0.2ms slack
            if (parkNs <= 0L) {
                LockSupport.parkNanos(diff);
            } else {
                LockSupport.parkNanos(parkNs);
            }
        }
    }
}
