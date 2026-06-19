package aiplay.behaviortreebuilder.startaiplay.decorator;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.EXECUTOR_THREADS;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.GAMESTATE_BUS;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.RUNNING_ATOMIC_BOOLEAN;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.START_TIME_NS;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.logging.SessionRollingLogger;
import aiplay.runtime.port.RuntimeClock;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.shared.state.GameStateBus;
import aiplay.shared.state.GameStateSnapshot;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeNode;
import behaviortree.BehaviorTreeStatus;
import behaviortree.DecoratorNode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorFpsDecorator extends DecoratorNode {

  /**
   * Minimum spacing between restart attempts. The tree ticks at ~1000/maxFps ms
   * (≈17 ms at 60 fps); without this a hard crash-loop would respawn a consumer
   * thread on every tick. A died-then-stable thread is unaffected — the gap since
   * its last (re)start already exceeds the backoff, so recovery is immediate.
   */
  private static final long RESTART_BACKOFF_MS = 1_000L;

  private final AtomicInteger restartCount = new AtomicInteger(0);
  private volatile long lastStartMs = 0L;

  private final ExecutorSlot slot;

  public ExecutorFpsDecorator(ExecutorSlot slot, BehaviorTreeNode child) {
    super(slot.displayName(), child);
    this.slot = slot;
  }

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    // Executor is pre-populated on the blackboard by BotRuntime under the slot's key.
    IPlayExecutor executor = context.getBlackboard().get(slot.executorKey());
    if (executor == null) {
      throw new IllegalStateException("Executor not found on blackboard for slot " + slot
          + " (key=" + slot.executorKey() + "). Ensure BotRuntime.populateBlackboard() was called before first tick.");
    }

    ConcurrentMap<String, Thread> threads = context.getBlackboard().get(EXECUTOR_THREADS);
    if (threads == null) {
      threads = new ConcurrentHashMap<>();
      context.getBlackboard().set(EXECUTOR_THREADS, threads);
    }

    Thread existing = threads.get(getName());

    if (existing == null) {
      startConsumerThread(context, executor, threads, "initial start");
    } else if (!existing.isAlive()) {
      // Supervisor: a consumer thread that died (uncaught exception, OOM in a
      // virtual thread, …) is restarted rather than silently failing. The old
      // code returned FAILURE here, which propagated to a dead end — the bot
      // kept running minus one executor (e.g. no movement, no commands) with no
      // signal at all. The backoff stops a hard crash-loop from respawning a
      // thread on every ~17 ms tick.
      if (System.currentTimeMillis() - lastStartMs >= RESTART_BACKOFF_MS) {
        int attempt = restartCount.incrementAndGet();
        reportThreadDeath(context, executor, attempt);
        threads.remove(getName(), existing);
        startConsumerThread(context, executor, threads, "restart #" + attempt);
      }
    }

    return recordStatus(context, BehaviorTreeStatus.RUNNING);
  }

  /** Spawns the consumer virtual thread, tracks it for centralized shutdown, and stamps the (re)start time. */
  private void startConsumerThread(BehaviorTreeContext context, IPlayExecutor executor,
                                   ConcurrentMap<String, Thread> threads, String reason) {
    Thread t = Thread.ofVirtual()
        .name("ut99-consumer-" + executor.getClass().getSimpleName())
        .unstarted(() -> runConsumerAtFPS(context, executor));
    threads.put(getName(), t);
    lastStartMs = System.currentTimeMillis();
    log("INFO", "Consumer thread '" + getName() + "' " + reason);
    System.out.println("[executor] " + getName() + " consumer thread: " + reason);
    t.start();
  }

  /**
   * Loudly reports a dead consumer thread to every sink: the per-node ring buffer
   * (web UI), stderr (process log), and the per-bot session "executor" log — which
   * stays empty for a healthy bot, so any content there pinpoints a crashed executor
   * (e.g. the virtual-thread OOM "bots stil" signature).
   */
  private void reportThreadDeath(BehaviorTreeContext context, IPlayExecutor executor, int attempt) {
    String msg = "Consumer thread '" + getName() + "' ("
        + executor.getClass().getSimpleName() + ", key=" + executor.getExecutorKey()
        + ") died — restarting (#" + attempt + ")";
    log("WARN", msg);
    System.err.println("⚠️ [executor] " + msg);
    String sessionId = context.getBlackboard().get(BlackboardKeys.SESSION_ID);
    if (sessionId != null) {
      SessionRollingLogger.get(sessionId, "executor").warning(msg);
    }
  }

  private void runConsumerAtFPS(BehaviorTreeContext context, IPlayExecutor playExecutor) {
    // Propagate bot identity to this consumer thread
    if (context.getBlackboard().has(BlackboardKeys.BOT_IDENTITY_NAME)) {
      String name = context.getBlackboard().get(BlackboardKeys.BOT_IDENTITY_NAME);
      int team = context.getBlackboard().get(BlackboardKeys.BOT_IDENTITY_TEAM);
      String role = context.getBlackboard().get(BlackboardKeys.BOT_IDENTITY_ROLE);
      aiplay.runtime.context.PlayerIdentityContext.setForCurrentThread(name, team, role);
    }
    final AtomicBoolean running = context.getBlackboard().get(RUNNING_ATOMIC_BOOLEAN);
    final Long startTimeNs = context.getBlackboard().get(START_TIME_NS);
    final GameStateBus bus = context.getBlackboard().get(GAMESTATE_BUS);
    final RuntimeClock clock = context.getBlackboard().get(BlackboardKeys.RUNTIME_CLOCK);

    int fps = playExecutor.getPredictionFps();
    if (fps <= 0) {
      throw new IllegalStateException("predictionFps must be > 0 for " + playExecutor.getClass().getName());
    }

    long frameIntervalNs = (long) (1_000_000_000.0 / fps);
    // Resume at the current wall-clock phase instead of replaying frame indices
    // from 0. On the initial start startTimeNs ≈ now so this is ~0; on a supervised
    // restart (startTimeNs far in the past) it avoids spinning i up to now.
    long elapsedNs = clock.nanoTime() - startTimeNs;
    long i = elapsedNs > 0 ? elapsedNs / frameIntervalNs : 0L;
    long lastSeenSeq = -1;

    final String key = "live";

    while (running.get()) {
      long target = startTimeNs + i * frameIntervalNs;
      clock.waitUntilNano(target);

      GameStateSnapshot snap = bus.latest(key);
      if (snap != null && snap.frames != null && !snap.frames.isEmpty()) {
        if (snap.seq != lastSeenSeq) {
          context.getBlackboard().set(slot.gameStatesKey(), snap.frames);
          lastSeenSeq = snap.seq;
          getChild().tick(context);
        }
      }

      i++;
    }
  }
}
