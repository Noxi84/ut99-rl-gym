package aiplay.shared.state;

import aiplay.dto.GridFrame;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class GameStateBus {

    private final ConcurrentHashMap<String, GameStateSnapshot> latestByKey = new ConcurrentHashMap<String, GameStateSnapshot>();
    private final AtomicLong seq = new AtomicLong(0);

    public void publish(String executorKey, List<GridFrame> frames) {
        long s = seq.incrementAndGet();
        latestByKey.put(executorKey, new GameStateSnapshot(s, frames));
    }

    public GameStateSnapshot latest(String executorKey) {
        return latestByKey.get(executorKey);
    }
}
