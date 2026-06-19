package aiplay;

import aiplay.behaviortreebuilder.UT99HeadlessBehaviorTreeBuilder;
import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.instance.InstanceConfig;
import aiplay.play.udpstate.UdpStateReceiver;
import aiplay.runtime.BotRuntime;
import aiplay.runtime.BotRuntimeFactory;
import aiplay.runtime.InstanceContext;
import aiplay.runtime.SharedRuntimeServices;
import aiplay.runtime.SelfPlayTeamAlternator;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.executors.PlayExecutionService;
import aiplay.scanners.feature.resolver.enemyspawn.EnemySpawnTargetFeatureComponent.EnemySpawnTargetEnricher;
import aiplay.scanners.feature.resolver.mission.MissionAnnotationFeatureEnricher;
import behaviortree.BehaviorTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Launches multiple bot instances in a single JVM, each in its own thread.
 * Shares the ONNX model, JVM overhead, and constants across all instances.
 *
 * Usage: java -cp java-aiplay-1.0.jar aiplay.MultiInstanceLauncher
 *            --instances=3 --display-base=20 --web-port-base=6080
 *            --game-port-base=7777 --game-port-step=2
 */
public class MultiInstanceLauncher {

  public static void main(String[] args) throws Exception {
    int instances = intArg(args, "instances", 1);
    int gpuInstances = intArg(args, "gpu-instances", instances);
    int displayBase = intArg(args, "display-base", 20);
    int webPortBase = intArg(args, "web-port-base", 6080);
    int gamePortBase = intArg(args, "game-port-base", 7777);
    int gamePortStep = intArg(args, "game-port-step", 2);
    int udpPortBase = intArg(args, "udp-port-base", 0);
    int stateUdpPortBase = intArg(args, "state-udp-port-base", 0);
    int staggerMs = intArg(args, "stagger-ms", 5000);

    System.out.println("[MultiInstanceLauncher] instances=" + instances
        + " (gpu=" + gpuInstances + " cpu=" + (instances - gpuInstances) + ")"
        + " web=" + webPortBase + " game=" + gamePortBase + " step=" + gamePortStep
        + " udp=" + udpPortBase + " stateUdp=" + stateUdpPortBase);

    // Global one-time init
    aiplay.runtime.config.SessionPaths.ensureSessionDirsExist();
    aiplay.scanners.feature.contract.FeatureContractRepository.shared().validateAll();

    // Model role registry: validates role bindings before any instance starts
    ModelRoleRegistry roleRegistry = ModelRoleRegistry.shared();
    roleRegistry.validate();
    roleRegistry.logSnapshot();

    // Promotion observability: show which models are actively promoted
    aiplay.runtime.promotion.ActiveBindingsReader.logSnapshot();

    // Control plane: machine inventory from the shared server inventory
    aiplay.runtime.controlplane.MachineInventory.load().logSnapshot();

    // Shared services: created once, passed to every instance
    int maxFps = new PlayExecutionService().getMaxPredictionFps();
    SharedRuntimeServices shared = new SharedRuntimeServices(maxFps, roleRegistry);
    long tickDelayMillis = maxFps > 0 ? Math.max(1L, Math.round(1000.0 / maxFps)) : 50L;

    // Multi-bot config: only active RL bots join the server (RunUT99ServerActionNode filters
    // on active() when building the RLBotCount URL), and each gets a Java-side BotRuntime here.
    // The activeRlBots filter below is defensive in case this launcher is used standalone.
    List<BotConfig> aiBots = GlobalConfigRepository.shared().bots().stream()
        .filter(BotConfig::isRl).toList();
    List<BotConfig> activeRlBots = aiBots.stream()
        .filter(BotConfig::active).toList();
    int botsPerInstance = activeRlBots.size();
    boolean mapSymmetric = aiplay.runtime.config.ActiveMapConfigResolver
        .resolve(aiplay.runtime.context.MapKey.active()).symmetric();
    boolean selfPlay = SelfPlayTeamAlternator.isSelfPlay(activeRlBots);

    System.out.println("[MultiInstanceLauncher] " + aiBots.size() + " AI bot(s), "
        + activeRlBots.size() + " active: "
        + aiBots.stream().map(b -> b.name() + "(team=" + b.team()
            + (b.active() ? ",active" : ",inactive") + ")").toList());
    System.out.println("[MultiInstanceLauncher] selfPlay=" + selfPlay
        + " mapSymmetric=" + mapSymmetric
        + " teamAlternation=" + (selfPlay && mapSymmetric ? "auto-even-odd" : "off"));

    List<Thread> threads = new ArrayList<>();
    int botIdx = 0;

    for (int i = 0; i < instances; i++) {
      String display = ":" + (displayBase + i);
      int gamePort = gamePortBase + i * gamePortStep;
      int webPort = webPortBase + i;
      int udpPort = udpPortBase > 0 ? udpPortBase + i : 0;
      int stateUdpPort = stateUdpPortBase > 0 ? stateUdpPortBase + i : 0;

      boolean useGpu = i < gpuInstances;
      InstanceConfig config = new InstanceConfig(i, display, gamePort, webPort, udpPort, stateUdpPort, useGpu, gpuInstances, instances - gpuInstances);
      List<BotConfig> effectiveRlBots = SelfPlayTeamAlternator.effectiveBotsForInstance(
          activeRlBots, i, mapSymmetric);
      boolean swappedLayout = SelfPlayTeamAlternator.shouldSwap(activeRlBots, i, mapSymmetric);
      System.out.println("[MultiInstanceLauncher] instance=" + i
          + " selfPlayLayout=" + (swappedLayout ? "swapped" : "normal")
          + " bots=" + effectiveRlBots.stream()
              .map(b -> b.name() + "(team=" + b.team() + ",snapshots="
                  + b.models().entrySet().stream()
                      .map(e -> e.getKey() + "=" + e.getValue().snapshot())
                      .toList()
                  + ")")
              .toList());

      // Bind per-instance UDP state receiver — shared across all bots in this instance.
      // Fail loud if bind fails: without a receiver, the bot silently gets no game state
      // and stands still for the whole session, which is far worse than a visible crash.
      UdpStateReceiver stateReceiver = null;
      if (stateUdpPort > 0) {
        try {
          stateReceiver = new UdpStateReceiver(stateUdpPort, "instance-" + i);
        } catch (Exception e) {
          throw new RuntimeException(
              "[MultiInstanceLauncher] Failed to bind UdpStateReceiver on " + stateUdpPort
                  + " for instance " + i + ". Check for stale JVM or wrong port config.", e);
        }
      }

      // Per active RL bot in this instance: own BotRuntime + BehaviorTree + thread
      for (int b = 0; b < botsPerInstance; b++) {
        BotConfig botCfg = effectiveRlBots.get(b);
        String sessionId = "instance-" + i + "-" + botCfg.name();

        InstanceContext ctx = new InstanceContext(sessionId, config, botCfg, b, stateReceiver);

        // Set bot identity before building BT and BotRuntime (needed for per-bot model enabled checks)
        PlayerIdentityContext.setForCurrentThread(
            botCfg.name(), botCfg.team(), botCfg.role());

        BotRuntime runtime = BotRuntimeFactory.create(ctx, shared);

        BehaviorTree bt = new UT99HeadlessBehaviorTreeBuilder().build(sessionId, botCfg);

        runtime.populateBlackboard(bt.getContext().getBlackboard());
        bt.getContext().getBlackboard().set(BlackboardKeys.EFFECTIVE_RL_BOTS, effectiveRlBots);
        // Only the first bot per instance starts the UT99 server; subsequent bots skip
        if (b > 0) {
          bt.getContext().getBlackboard().set(BlackboardKeys.SKIP_SERVER_START, Boolean.TRUE);
        }
        runtime.start(bt.getContext());

        final int idx = botIdx++;
        final BotConfig botCfgFinal = botCfg;
        Thread t = Thread.ofVirtual()
            .name("bot-" + i + "-" + botCfg.name())
            .start(() -> {
              PlayerIdentityContext.setForCurrentThread(
                  botCfgFinal.name(), botCfgFinal.team(), botCfgFinal.role());
              runBotLoop(bt, runtime, tickDelayMillis, idx);
            });
        threads.add(t);

        System.out.println("[MultiInstanceLauncher] Started " + botCfg.name() + " (team=" + botCfg.team() + ") on instance " + i);
      }

      if (i < instances - 1) {
        Thread.sleep(staggerMs);
      }
    }

    int totalBots = instances * botsPerInstance;
    System.out.println("[MultiInstanceLauncher] All " + totalBots + " bots started across " + instances + " instances.");

    // Wait for all threads
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private static void runBotLoop(BehaviorTree tree, BotRuntime runtime, long tickDelayMillis, int idx) {
    try {
      while (true) {
        try {
          tree.tick();
          if (shouldExit(tree)) {
            System.out.println("[bot-" + idx + "] Exit requested, stopping.");
            return;
          }
          Thread.sleep(Math.max(1L, tickDelayMillis));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (Throwable t) {
          System.err.println("[bot-" + idx + "] Error: " + t.getMessage());
          t.printStackTrace(System.err);
          try {
            Thread.sleep(250L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    } finally {
      try {
        runtime.stop();
        String sid = tree.getContext().getBlackboard().get(BlackboardKeys.SESSION_ID);
        if (sid != null) {
          MissionAnnotationFeatureEnricher.unregisterSession(sid);
          aiplay.scanners.feature.resolver.viewtargetpitch.AimTargetEnricher.unregisterSession(sid);
          EnemySpawnTargetEnricher.unregisterSession(sid);
        }
      } catch (Exception ignore) {
      }
      System.out.println("[bot-" + idx + "] Session cleanup done.");
    }
  }

  private static boolean shouldExit(BehaviorTree tree) {
    try {
      if (tree == null || tree.getContext() == null || tree.getContext().getBlackboard() == null) {
        return false;
      }
      if (tree.getContext().getBlackboard().has(BlackboardKeys.APP_EXIT_REQUESTED)) {
        Boolean v = tree.getContext().getBlackboard().get(BlackboardKeys.APP_EXIT_REQUESTED);
        return v != null && v.booleanValue();
      }
    } catch (Exception ignore) {
    }
    return false;
  }

  private static int intArg(String[] args, String name, int defaultValue) {
    if (args == null) return defaultValue;
    String prefix = "--" + name + "=";
    for (String a : args) {
      if (a != null && a.startsWith(prefix)) {
        try {
          return Integer.parseInt(a.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
          System.err.println("Invalid value for " + name + ": " + a);
        }
      }
    }
    return defaultValue;
  }
}
