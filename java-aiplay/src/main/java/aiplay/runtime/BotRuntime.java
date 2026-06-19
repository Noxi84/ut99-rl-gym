package aiplay.runtime;

import ai.onnxruntime.OrtException;
import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.dto.GameStateDto;
import aiplay.dto.GridFrame;
import aiplay.instance.InstanceConfig;
import aiplay.runtime.port.RuntimeClock;
import aiplay.scanners.feature.contract.RealTimeFeatureEnricher;
import aiplay.shared.state.GameStateBus;
import behaviortree.BehaviorTreeContext;
import behaviortree.Blackboard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Explicit runtime composition root for one bot instance.
 *
 * <p>Owns all per-instance state: identity, buses, sender, predictor,
 * RL components, executors, and lifecycle. Created by {@link BotRuntimeFactory}.</p>
 *
 * <p>The behavior tree is subordinate to the runtime: it reads services
 * from the blackboard (populated by this runtime) rather than creating them.</p>
 *
 * <p>Thread ownership: the producer thread is owned by this runtime.
 * Consumer threads are started by behavior tree decorators but tracked
 * here via the EXECUTOR_THREADS blackboard key for centralized shutdown.</p>
 */
public final class BotRuntime {

    private static final Logger LOG = Logger.getLogger(BotRuntime.class.getName());

    private final InstanceContext context;
    private final InstanceServices services;
    private final int maxPredictionFps;
    private volatile RuntimeLifecycle lifecycle = RuntimeLifecycle.CREATED;

    private final RealTimeFeatureEnricher trainingFeatureService = RealTimeFeatureEnricher.shared();

    private Thread producerThread;
    private BehaviorTreeContext treeContext;

    public BotRuntime(InstanceContext context, InstanceServices services, int maxPredictionFps) {
        this.context = context;
        this.services = services;
        this.maxPredictionFps = maxPredictionFps;
    }

    public InstanceContext getContext() {
        return context;
    }

    public InstanceServices getServices() {
        return services;
    }

    public int getMaxPredictionFps() {
        return maxPredictionFps;
    }

    public RuntimeLifecycle getLifecycle() {
        return lifecycle;
    }

    /**
     * Populates the given blackboard with all runtime services so that
     * existing behavior tree nodes can read them unchanged.
     */
    public void populateBlackboard(Blackboard bb) {
        bb.set(BlackboardKeys.SESSION_ID, context.getSessionId());
        if (context.getInstanceConfig() != null) {
            bb.set(BlackboardKeys.INSTANCE_CONFIG, context.getInstanceConfig());
        }
        if (context.getStateFrameSource() != null) {
            bb.set(BlackboardKeys.UDP_STATE_RECEIVER, context.getStateFrameSource());
        }
        // Multi-bot identity: stored on blackboard so all threads (producer, consumers)
        // can set their ThreadLocal PlayerIdentityContext from it.
        String botName = context.getEffectiveBotName();
        if (botName != null) {
            bb.set(BlackboardKeys.BOT_IDENTITY_NAME, botName);
            bb.set(BlackboardKeys.BOT_IDENTITY_TEAM, context.getEffectiveTeam());
            bb.set(BlackboardKeys.BOT_IDENTITY_ROLE, context.getEffectiveRole());
        }
        bb.set(BlackboardKeys.BOT_RUNTIME, this);
        services.populateBlackboard(bb);
    }

    /**
     * Validates that all required services are present, starts the producer
     * thread, logs the startup snapshot, and transitions to RUNNING.
     *
     * <p>Must be called after {@link #populateBlackboard} and before the
     * behavior tree's first tick.</p>
     */
    public void start(BehaviorTreeContext treeContext) {
        if (lifecycle != RuntimeLifecycle.CREATED) {
            throw new IllegalStateException("Cannot start runtime in state " + lifecycle);
        }
        this.treeContext = treeContext;
        lifecycle = RuntimeLifecycle.STARTING;

        validate();
        startProducerThread(treeContext);
        logStartupSnapshot();

        lifecycle = RuntimeLifecycle.RUNNING;
    }

    /**
     * Requests a graceful stop: signals all loops to end, joins the
     * producer thread, and joins any consumer threads tracked in
     * EXECUTOR_THREADS on the blackboard.
     */
    public void stop() {
        if (lifecycle == RuntimeLifecycle.STOPPED || lifecycle == RuntimeLifecycle.STOPPING) {
            return;
        }
        lifecycle = RuntimeLifecycle.STOPPING;
        services.getRunning().set(false);

        joinThread(producerThread, "producer");

        if (treeContext != null) {
            ConcurrentMap<String, Thread> consumerThreads =
                treeContext.getBlackboard().get(BlackboardKeys.EXECUTOR_THREADS);
            if (consumerThreads != null) {
                for (var entry : consumerThreads.entrySet()) {
                    joinThread(entry.getValue(), entry.getKey());
                }
            }
        }

        LOG.info("[" + context.getSessionId() + "] Runtime stopped");
        lifecycle = RuntimeLifecycle.STOPPED;
    }

    // ─────────────────────── validation ───────────────────────

    private void validate() {
        String sid = context.getSessionId();
        List<String> missing = new ArrayList<>();

        if (services.getGameStateBus() == null) missing.add("GameStateBus");
        if (services.getRunning() == null) missing.add("running");
        if (services.getInferencePort() == null) missing.add("InferencePort");
        if (services.getMovementIntentBus() == null) missing.add("MovementIntentBus");
        if (services.getPolicyIntentBus() == null) missing.add("PolicyIntentBus");
        if (services.getViewTurnIntentBus() == null) missing.add("ViewTurnIntentBus");
        if (services.getTacticalIntentBus() == null) missing.add("TacticalIntentBus");
        if (services.getGameStateSource() == null) missing.add("GameStateSource");
        if (services.getClock() == null) missing.add("RuntimeClock");

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "[" + sid + "] Runtime validation failed — missing: " + String.join(", ", missing));
        }
    }

    // ─────────────────────── producer thread ───────────────────────

    private void startProducerThread(BehaviorTreeContext btContext) {
        String sessionId = context.getSessionId();
        int fps = maxPredictionFps;
        AtomicBoolean running = services.getRunning();
        GameStateBus bus = services.getGameStateBus();
        long startTimeNs = services.getStartTimeNs();

        // Capture bot identity for the producer thread (ThreadLocal must be set per thread)
        String botName = context.getEffectiveBotName();
        int botTeam = context.getEffectiveTeam();
        String role = context.getEffectiveRole();

        producerThread = Thread.ofVirtual()
            .name("ut99-producer-live")
            .unstarted(() -> {
                if (botName != null) {
                    aiplay.runtime.context.PlayerIdentityContext.setForCurrentThread(
                            botName, botTeam, role);
                }
                try {
                    runLiveProducer(sessionId, fps, running, bus, startTimeNs, btContext);
                } catch (OrtException e) {
                    throw new RuntimeException(e);
                }
            });
        btContext.getBlackboard().set(BlackboardKeys.PRODUCER, producerThread);
        producerThread.start();
    }

    private void runLiveProducer(String sessionId, int fps, AtomicBoolean running,
                                 GameStateBus bus, long startTimeNs,
                                 BehaviorTreeContext btContext) throws OrtException {
        RuntimeClock clock = services.getClock();
        long frameIntervalNs = (long) (1_000_000_000.0 / fps);
        long i = 0;
        boolean parked = false;
        aiplay.runtime.port.CommandSink commandSink = services.getCommandSink();
        // Per-bot 1/min snapshot of all 6 players' scores, used by the
        // DeltaGate eval pipeline (see train/rl/shared/delta_gate.py).
        PlayerScoresLogger playerScoresLogger = new PlayerScoresLogger(sessionId);
        // Per-bot match-end transition detector — emits MATCH_ENDED log-tag
        // so the trainer-side gate fires after N completed matches instead
        // of every eval_cycle_seconds (ServerTravel overhead ~90s would
        // otherwise push the wall-clock cadence out of sync with match grids).
        MatchEndLogger matchEndLogger = new MatchEndLogger(sessionId);

        AmmoDeadlockGuard ammoDeadlockGuard = new AmmoDeadlockGuard(
            aiplay.config.global.GlobalConfigRepository.shared().ammoDeadlockGuard(),
            context.getEffectiveBotName());

        while (running.get()) {
            long target = startTimeNs + i * frameIntervalNs;
            clock.waitUntilNano(target);

            List<GridFrame> gridFrames = services.getGameStateSource().poll(btContext, fps);

            if (gridFrames != null && !gridFrames.isEmpty()) {
                List<GridFrame> cleaned = new ArrayList<>(gridFrames.size());
                List<GameStateDto> dtosForEnrichment = new ArrayList<>(gridFrames.size());
                for (GridFrame gf : gridFrames) {
                    if (gf != null) {
                        cleaned.add(gf);
                        dtosForEnrichment.add(gf.state());
                    }
                }

                if (!cleaned.isEmpty()) {
                    // Bot is back in game state — unpark if needed
                    if (parked && commandSink != null) {
                        LOG.fine("[" + sessionId + "] Bot restored in game state, unparking");
                        commandSink.notifyParked(false);
                        parked = false;
                    }

                    trainingFeatureService.enrichIncrementalForRealTimePlay(
                        sessionId, "PlayExecutionService", dtosForEnrichment);

                    GameStateDto latest = dtosForEnrichment.get(dtosForEnrichment.size() - 1);

                    // PLAYER_SCORES snapshot for DeltaGate eval (throttled to 1/min)
                    playerScoresLogger.maybeEmit(latest);
                    // MATCH_ENDED transition emit on bGameEnded false→true
                    matchEndLogger.maybeEmit(latest);

                    if (commandSink != null
                        && ammoDeadlockGuard.tick(latest, System.currentTimeMillis())) {
                        commandSink.sendSuicide();
                    }

                    bus.publish("live", cleaned);
                }
            } else {
                // Bot not found in game state — notify dispatcher so other bots are not blocked
                if (!parked && commandSink != null) {
                    LOG.fine("[" + sessionId + "] Bot not in game state, parking");
                    commandSink.notifyParked(true);
                    parked = true;
                }
            }

            i++;
        }
    }

    // ─────────────────────── observability ───────────────────────

    private void logStartupSnapshot() {
        String sid = context.getSessionId();
        InstanceConfig ic = context.getInstanceConfig();

        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════╗\n");
        sb.append("║  BOT RUNTIME STARTUP: ").append(sid).append("\n");
        sb.append("╠══════════════════════════════════════════╣\n");

        if (ic != null) {
            sb.append("║  endpoint    : ").append(ic.getNeuralNetUrl()).append("\n");
            sb.append("║  gpu         : ").append(ic.isUseGpu()).append("\n");
            sb.append("║  game port   : ").append(ic.getServerPort()).append("\n");
            sb.append("║  group       : ").append(ic.getGroupIndex()).append("/").append(ic.getGroupSize()).append("\n");
        }

        sb.append("║  producer fps: ").append(maxPredictionFps).append("\n");
        sb.append("║  RL enabled  : ").append(services.getRewardComputer() != null ? "yes" : "no").append("\n");
        sb.append("║  lifecycle   : ").append(lifecycle).append("\n");
        sb.append("╠── Port Bindings ──────────────────────────╣\n");
        sb.append("║  gamestate   : ").append(adapterName(services.getGameStateSource())).append("\n");
        sb.append("║  command sink: ").append(adapterName(services.getCommandSink())).append("\n");
        sb.append("║  inference   : ").append(adapterName(services.getInferencePort())).append("\n");
        sb.append("║  clock       : ").append(adapterName(services.getClock())).append("\n");
        sb.append("╚══════════════════════════════════════════╝");

        LOG.info(sb.toString());
    }

    // ─────────────────────── helpers ───────────────────────

    private static String adapterName(Object port) {
        if (port == null) return "not bound";
        return port.getClass().getSimpleName();
    }

    private void joinThread(Thread thread, String name) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(5000);
                if (thread.isAlive()) {
                    LOG.warning("[" + context.getSessionId() + "] Thread '" + name + "' did not stop within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString() {
        return "BotRuntime[" + context.getSessionId() + " lifecycle=" + lifecycle + "]";
    }
}
