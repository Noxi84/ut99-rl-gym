package aiplay.runtime;

import aiplay.config.model.ModelConfig;
import aiplay.runtime.config.SessionPaths;
import aiplay.instance.InstanceConfig;
import aiplay.play.UdpCommandSender;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.InferenceBatchingConfig;
import aiplay.prediction.GenericPredictor;
import aiplay.prediction.ModelSpec;
import aiplay.prediction.batch.BatchingInferencePort;
import aiplay.rl.ExperienceCollector;
import aiplay.rl.ModelWatcher;
import aiplay.rl.PerModelExperienceRecorder;
import aiplay.rl.RLConfig;
import aiplay.rl.RewardComputer;
import aiplay.rl.champion.ChampionNewestWatcher;
import aiplay.runtime.config.PolicyRole;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.rl.champion.SnapshotResolver;
import aiplay.config.global.BotConfig;
import aiplay.runtime.adapter.live.LiveGameStateSource;
import aiplay.runtime.adapter.live.SystemClock;
import aiplay.runtime.port.CommandSink;
import aiplay.runtime.port.GameStateSource;
import aiplay.runtime.port.InferencePort;
import aiplay.runtime.port.RuntimeClock;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.executors.PlayContext;
import aiplay.scanners.executors.PlayExecutionService;
import aiplay.shared.movement.MovementIntentBus;
import aiplay.shared.movement.PolicyIntentBus;
import aiplay.shared.shooting.ShootIntentBus;
import aiplay.shared.state.GameStateBus;
import aiplay.shared.view.ViewTurnIntentBus;
import aiplay.shared.weapon.WeaponSelectIntentBus;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Builds a complete {@link BotRuntime} from instance config and shared services.
 *
 * <p>This is the explicit composition root for one bot instance. All services
 * that were previously lazy-initialized inside ProducerFpsDecorator are now
 * created here, before the behavior tree's first tick.</p>
 *
 * <p>Model keys are resolved via {@link ModelRoleRegistry} — no hardcoded
 * model key strings appear in this class. The joint {@code rl_pawn}
 * policy is bound to {@link ModelRole#PAWN_POLICY} and is the single
 * low-level policy in productie.</p>
 */
public final class BotRuntimeFactory {

    private static final Logger LOG = Logger.getLogger(BotRuntimeFactory.class.getName());

    private BotRuntimeFactory() {}

  /**
   * Resolves the ONNX path for a model, taking the bot's snapshot config
   * into account. {@code "current"} → live trainingmodel path;
   * {@code "<modelKey>/<counter>"} → frozen champion path. Fingerprint
   * compatibility is hard-validated for champions — mismatch crashes here
   * with a clear message instead of silently loading against a wrong
   * features schema.
   */
  private static SnapshotResolver.OnnxRef resolveOnnxRef(BotConfig bot, String modelKey) {
    if (bot != null && bot.isRl()) {
      String snapshot = bot.snapshotFor(modelKey);
      try {
        return SnapshotResolver.resolve(modelKey, snapshot);
      } catch (IllegalStateException e) {
        if (PolicyRole.forSnapshot(snapshot) == PolicyRole.CHAMPION) {
          String bcBaseline = SessionPaths.getModelTrainingDir() + "/" + modelKey + "_bc_baseline.onnx";
          if (new java.io.File(bcBaseline).exists()) {
            LOG.warning("Champion resolution failed for " + modelKey
                + ", using BC baseline as frozen opponent: " + e.getMessage());
            return new SnapshotResolver.OnnxRef(modelKey, bcBaseline, PolicyRole.CHAMPION, true);
          }
        }
        throw e;
      }
    }
    String onnx = SessionPaths.getModelTrainingDir() + "/" + modelKey + ".onnx";
    return new SnapshotResolver.OnnxRef(modelKey, onnx, PolicyRole.CURRENT, false);
  }

  /**
   * Binds a model for this bot.
   *
   * <ul>
   *   <li>{@code "current"} (live policy): hot-reload watcher on
   *       {@code trainingmodel/{model}.onnx} via {@link ModelWatcher}
   *       — refreshes the OrtSession whenever the trainer rewrites the file.
   *   <li>{@code "<mk>/newest"} (dynamic champion): registers with
   *       {@link ChampionNewestWatcher}, which polls {@code bundles.json} and
   *       hot-swaps the session when a PROMOTE advances pool[0]. Match-cycle
   *       deploys (10-min matches with ServerTravel) now pick up the new
   *       champion without a bot restart.
   *   <li>{@code "<mk>/<counter>"} (pinned champion): registered once and
   *       deliberately not watched — baseline/validation bots stay frozen at
   *       the requested counter until the next bot restart.
   * </ul>
   */
  private static void bindModel(GenericPredictor predictor,
                                aiplay.scanners.model.ITrainingModel trainingModel,
                                SnapshotResolver.OnnxRef ref,
                                String modelKey,
                                String sessionId) throws Exception {
    if (ref.policyRole() == PolicyRole.CHAMPION) {
      predictor.register(new ModelSpec(trainingModel, modelKey, ref.onnxPath()));
      if (ref.dynamicNewest()) {
        ChampionNewestWatcher.register(modelKey, predictor, ref.onnxPath());
        LOG.info("[" + sessionId + "] Champion model registered (dynamic newest): "
            + modelKey + " -> " + ref.onnxPath()
            + " predictorId=" + System.identityHashCode(predictor)
            + " (will hot-swap on PROMOTE)");
      } else {
        LOG.info("[" + sessionId + "] Frozen champion model registered: "
            + modelKey + " -> " + ref.onnxPath()
            + " predictorId=" + System.identityHashCode(predictor)
            + " (pinned counter, no hot-reload)");
      }
      return;
    }

    LOG.info("[" + sessionId + "] Current model watcher started: "
        + modelKey + " -> " + ref.onnxPath()
        + " predictorId=" + System.identityHashCode(predictor));
    new ModelWatcher(modelKey, ref.onnxPath(), predictor, trainingModel).startDaemon();
  }

  /**
   * Returns the int-encoded policy role for tagging this bot's experience.
   * 0 = CURRENT (live trainable policy), 1 = CHAMPION (frozen snapshot).
   * Used by ExperienceCollector to flag every flushed transition so Python
   * trainers can filter out CHAMPION rollouts before feeding SAC.
   */
  private static int policyRoleInt(BotConfig bot, String modelKey) {
    if (bot == null || !bot.isRl()) {
      return PolicyRole.CURRENT.ordinal();
    }
    String snapshot = bot.snapshotFor(modelKey);
    return PolicyRole.forSnapshot(snapshot).ordinal();
  }

    public static BotRuntime create(InstanceContext ctx, SharedRuntimeServices shared) {
        ModelRoleRegistry registry = shared.getModelRoleRegistry();
        if (!registry.isRoleBound(ModelRole.PAWN_POLICY)) {
            throw new IllegalStateException(
                "pawn_policy is niet bound in resources/config/roles.json. "
                    + "Joint rl_pawn is de enige low-level policy in productie.");
        }
        boolean policyActive = registry.isRoleActive(ModelRole.PAWN_POLICY);

        // 1. Core coordination objects
        GameStateBus gameStateBus = new GameStateBus();
        AtomicBoolean running = new AtomicBoolean(true);

        // 2. Intent buses
        MovementIntentBus movementIntentBus = new MovementIntentBus();
        PolicyIntentBus policyIntentBus = new PolicyIntentBus();
        ViewTurnIntentBus viewTurnIntentBus = new ViewTurnIntentBus();
        ShootIntentBus shootIntentBus = policyActive ? new ShootIntentBus() : null;
        aiplay.shared.tactical.TacticalIntentBus tacticalIntentBus = new aiplay.shared.tactical.TacticalIntentBus();
        WeaponSelectIntentBus weaponSelectIntentBus = new WeaponSelectIntentBus();

        // 3. Ports: GameState source, Inference, Command sink, Clock
        RuntimeClock clock = new SystemClock();
        GameStateSource gameStateSource = new LiveGameStateSource();
        long startTimeNs = clock.nanoTime();

        GenericPredictor predictor = new GenericPredictor();
        predictor.setUseGpu(ctx.isUseGpu());
        InferenceBatchingConfig batchingCfg = GlobalConfigRepository.shared().inferenceBatching();
        InferencePort inferencePort = new BatchingInferencePort(predictor, batchingCfg);
        if (batchingCfg.enabled()) {
            LOG.info("[" + ctx.getSessionId() + "] Batched inference ENABLED (max_batch_size="
                + batchingCfg.maxBatchSize() + ", submit_timeout_ms=" + batchingCfg.submitTimeoutMs() + ")");
        }

        CommandSink commandSink = null;
        InstanceConfig ic = ctx.getInstanceConfig();
        if (ic != null) {
            if (ic.getUdpListenPort() <= 0 || ctx.getBotConfig() == null) {
                throw new IllegalStateException(
                    "UDP command channel not configured for " + ctx.getSessionId()
                        + " (udpListenPort=" + ic.getUdpListenPort()
                        + ", botConfig=" + ctx.getBotConfig() + ")");
            }
            commandSink = new UdpCommandSender(ic, ctx.getBotIdx());
            LOG.info("[" + ctx.getSessionId() + "] bot=" + ctx.getBotConfig().name()
                + " botIdx=" + ctx.getBotIdx()
                + " CommandSink -> UdpCommandSender @ 127.0.0.1:" + ic.getUdpListenPort());
        }

        // 4. RL experience collection — joint policy only.
        RewardComputer rewardComputer = null;
        ExperienceCollector experienceCollector = null;
        PerModelExperienceRecorder jointRecorder = null;
        // CAPTURE-mode toggle: env var set by start-bots.sh based on
        // deploy.json's recordings_sync.enabled. When true, the bot dumps
        // raw .rec.gz files for offline replay and skips live .npz writing
        // (no double work, no double bias in the trainer's experience pool).
        boolean rawRecordingMode = "true".equalsIgnoreCase(
            System.getenv("UT99_RAW_RECORDING"));
        try {
            if (policyActive) {
                String replayBaseDir = SessionPaths.getSessionDir() + "/rl-replay-buffer";

                ModelConfig jointCfg = registry.resolve(ModelRole.PAWN_POLICY);
                String jointKey = jointCfg.modelKey();
                RLConfig jointRlConfig = new RLConfig(jointKey, ctx.getEffectiveRole());
                if (jointRlConfig.isExperienceSyncEnabled() && !rawRecordingMode) {
                    RewardComputer jointRewardComputer = new RewardComputer(jointRlConfig);
                    jointRewardComputer.setSessionId(ctx.getSessionId());
                    int jointSeqLen = jointCfg.sequenceLength();
                    int jointStateSize = jointSeqLen * jointCfg.features().inputFeatures().size();
                    int jointActionSize = jointCfg.features().targetFeatures().size();
                    int jointRoleInt = policyRoleInt(ctx.getBotConfig(), jointKey);
                    ExperienceCollector jointCollector = new ExperienceCollector(
                            jointStateSize, jointActionSize, jointRlConfig,
                            Path.of(replayBaseDir, jointKey), jointRoleInt);
                    jointRecorder = new PerModelExperienceRecorder(jointCollector, jointRewardComputer, jointKey, jointRlConfig.getExperienceRecordInterval());
                    rewardComputer = jointRewardComputer;
                    experienceCollector = jointCollector;
                    LOG.info("[" + ctx.getSessionId() + "] Experience sync enabled for " + jointKey
                        + " (per-skill reward decomp + target_label aux supervision)");
                }

                SnapshotResolver.OnnxRef jointRef = resolveOnnxRef(ctx.getBotConfig(), jointKey);
                String jointOnnxPath = jointRef.onnxPath();
                aiplay.scanners.model.ITrainingModel jointTrainingModel =
                        new aiplay.scanners.model.resolver.rlpawn.RLPawnTrainingModelComponent();
                bindModel(predictor, jointTrainingModel, jointRef, jointKey, ctx.getSessionId());
                PlayerIdentityContext.setPredictorKey(jointKey, jointKey);
                LOG.info("[" + ctx.getSessionId() + "] Joint VR+shooting model bound: " + jointOnnxPath
                    + " (predictorKey=" + jointRef.predictorKey() + ")");
            }
        } catch (Exception e) {
            LOG.warning("[" + ctx.getSessionId() + "] RL init skipped: " + e.getMessage());
        }

        // 5. PlayExecutionService — scans, creates and inits executors
        PlayContext playContext = new PlayContext(ctx.getSessionId(), inferencePort, movementIntentBus);
        PlayExecutionService playExecutionService = new PlayExecutionService(playContext);

        // 6. Assemble
        InstanceServices services = new InstanceServices(
            gameStateBus, running, startTimeNs,
            movementIntentBus, policyIntentBus, viewTurnIntentBus, shootIntentBus,
            tacticalIntentBus, weaponSelectIntentBus,
            gameStateSource, commandSink, inferencePort, clock, playExecutionService,
            rewardComputer, experienceCollector,
            jointRecorder
        );

        return new BotRuntime(ctx, services, shared.getMaxPredictionFps());
    }
}
