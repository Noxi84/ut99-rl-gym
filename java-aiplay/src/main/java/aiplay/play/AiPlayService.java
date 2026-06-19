package aiplay.play;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.GENERIC_PREDICTOR;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.MOVEMENT_INTENT_BUS;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.SESSION_ID;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.UDP_STATE_RECEIVER;

import aiplay.dto.GameStateDto;
import aiplay.dto.KeyboardMoveDto;
import aiplay.dto.PlayerPawnDto;
import aiplay.rl.MovementPrimitive;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.play.udpstate.StateFrameSource;
import aiplay.play.udpstate.model.StateFrame;
import aiplay.runtime.port.InferencePort;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.TrainingModelService;
import aiplay.shared.movement.MovementIntentBus;
import aiplay.shared.movement.MovementIntent;
import aiplay.ut99webmodel.GameState;
import behaviortree.BehaviorTreeContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AiPlayService {

  private final TrainingFeatureService trainingFeatureService = TrainingFeatureService.shared();

  private volatile boolean udpFirstOkLogged = false;

  private long startWallClockMillis = -1L;
  private long sessionStartFetchWallClockMillis = -1L;
  private double lastElapsedTime = -1.0;
  private int lastConvertedFrameId = -1;
  private long lastConvertedFrameReceivedAtNanos = Long.MIN_VALUE;
  private GameState lastConvertedGameState;

  TrainingModelService trainingModelService = new TrainingModelService();
  private volatile Logger log;
  private static final ConcurrentHashMap<String, Boolean> CONFIG_LOGGED = new ConcurrentHashMap<String, Boolean>();

  private boolean initFinished = false;

  public AiPlayService() {
  }

  public GameStateDto getCurrentGameStatus(BehaviorTreeContext context) {
    try {
      final String sessionId = context.getBlackboard().get(SESSION_ID);

      if (!initFinished) {
        this.log = SessionRollingLogger.get(sessionId, SessionLogPaths.featureLog("Movement"));
        this.log.setLevel(Level.INFO);
        logConfigOnce(context);
        this.log.info("AIPLAY_CFG modelTrainingDir=" + aiplay.runtime.config.SessionPaths.getModelTrainingDir());
        this.log.info("AIPLAY_INIT ok=true");
        initFinished = true;
      }

      GameState fromJson = readGameState(context);
      if (fromJson == null) {
        // UDP receiver is up but no frame yet (boot). Producer loop handles null.
        return null;
      }
      GameStateDto dto = trainingFeatureService.createGameStateDtoFromJsonSession(sessionId, fromJson);

      if (dto == null || dto.playerPawn == null) {
        // Player not found in webservice response (startup, reconnecting, spectating).
        // Do NOT overlay movement here — dto.playerPawn is null by design.
        logger(context).warning("AIPLAY_STATUS_INVALID reason=null_or_no_playerPawn");
        return null;
      }

      // session timing
      double elapsed = (dto.mapInfo != null) ? dto.mapInfo.elapsedTime : 0.0;
      long fetchWallClockMillis = dto.timestampMillis;
      boolean newSession = (elapsed < lastElapsedTime || elapsed < 0.1 || startWallClockMillis == -1L);

      if (newSession) {
        startWallClockMillis = fetchWallClockMillis;
        sessionStartFetchWallClockMillis = fetchWallClockMillis;
        logger(context).info("AIPLAY_NEW_SESSION elapsedTime=" + f3(elapsed));
      }

      // Build a smooth game-time axis from fetch wall-clock deltas, scaled by gameSpeed.
      // The UWeb ElapsedTime field is too coarse for high-frequency control and often only
      // changes in whole seconds, which causes visible "freeze then jump" behavior in the
      // resampled control window. Session reset detection still uses ElapsedTime above.
      double gameSpeed = aiplay.runtime.config.Ut99InstallResolver.getEffectiveGameSpeed();
      long wallDeltaMs = Math.max(0L, fetchWallClockMillis - sessionStartFetchWallClockMillis);
      long timestampMillis = startWallClockMillis + (long) Math.round(wallDeltaMs * gameSpeed);
      dto.timestampMillis = timestampMillis;
      lastElapsedTime = elapsed;

      // Override movement key fields with the bot's own key state from MovementIntentBus.
      // Safe: dto.playerPawn was already null-checked above, so we know the player was found.
      overlayMovementFromIntentBus(context, dto);

      return dto;

    } catch (Exception e) {
      logger(context).log(Level.SEVERE, "AIPLAY_STATUS_ERROR msg=" + safeMsg(e), e);
      return null;
    }
  }

  /**
   * Obtain the next game-state frame from the UDP receiver. Returns {@code null}
   * on bootstrap (receiver bound but no frame received yet). Throws when no
   * receiver is configured — UDP is the only supported transport.
   */
  private GameState readGameState(BehaviorTreeContext context) {
    if (context == null || context.getBlackboard() == null
        || !context.getBlackboard().has(UDP_STATE_RECEIVER)) {
      throw new IllegalStateException(
          "UDP state receiver not configured; set stateUdpListenPort > 0 in InstanceConfig");
    }
    StateFrameSource receiver = context.getBlackboard().get(UDP_STATE_RECEIVER);
    StateFrame frame = receiver.getLatestFrame();
    if (frame == null) {
      return null;
    }
    if (!udpFirstOkLogged) {
      long ageMs = (System.nanoTime() - frame.receivedAtNanos()) / 1_000_000L;
      System.out.println("[AiPlayService] AIPLAY_UDP_OK first frame converted (fid="
          + frame.frameId() + " ageMs=" + ageMs + " players=" + frame.players().size()
          + " flags=" + frame.flags().size() + " sessionId="
          + context.getBlackboard().get(SESSION_ID) + ")");
      udpFirstOkLogged = true;
    }
    if (frame.frameId() == lastConvertedFrameId
        && frame.receivedAtNanos() == lastConvertedFrameReceivedAtNanos
        && lastConvertedGameState != null) {
      lastConvertedGameState.timestampMillis = System.currentTimeMillis();
      return lastConvertedGameState;
    }

    GameState converted = StateFrameToGameStateConverter.convert(frame);
    lastConvertedFrameId = frame.frameId();
    lastConvertedFrameReceivedAtNanos = frame.receivedAtNanos();
    lastConvertedGameState = converted;
    return converted;
  }

  public void refreshPredictorsWithRetry(BehaviorTreeContext context, int maxTries, long sleepMillis) {
    final InferencePort predictor = context.getBlackboard().get(GENERIC_PREDICTOR);

    int tries = Math.max(1, maxTries);

    for (int attempt = 1; attempt <= tries; attempt++) {
      try {
        // RL policy model is refreshed by ModelWatcher daemon — no manual refresh needed.
        logger(context).info("AIPLAY_MODELS_REFRESH_OK attempt=" + attempt);
        return;

      } catch (Exception e) {
        logger(context).warning("AIPLAY_MODELS_REFRESH_FAIL attempt=" + attempt + " msg=" + safeMsg(e));

        if (attempt == tries) {
          logger(context).severe("AIPLAY_MODELS_REFRESH_GIVE_UP tries=" + tries + " -> keep old models");
          return;
        }

        try {
          Thread.sleep(Math.max(10L, sleepMillis));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          logger(context).warning("AIPLAY_MODELS_REFRESH_SLEEP_INTERRUPTED");
          return;
        }
      }
    }
  }

  private Logger logger(BehaviorTreeContext context) {
    final String sessionId = context.getBlackboard().get(SESSION_ID);

    Logger l = this.log;
    if (l != null) {
      return l;
    }

    // fallback (should never happen)
    Logger fallback = SessionRollingLogger.get(sessionId, SessionLogPaths.featureLog("Movement"));
    fallback.setLevel(Level.INFO);
    this.log = fallback;
    return fallback;
  }

  private void logConfigOnce(BehaviorTreeContext context) {
    final String sessionId = context.getBlackboard().get(SESSION_ID);

    String key = sessionId + "|AiPlayService";
    Boolean prev = CONFIG_LOGGED.putIfAbsent(key, Boolean.TRUE);
    if (prev != null) {
      return;
    }

    Logger l = logger(context);

    l.info("AIPLAY_CFG sessionsDir=" + aiplay.runtime.config.SessionPaths.getSessionDir());
    l.info("AIPLAY_CFG neuralNetUrl=" + aiplay.runtime.config.NeuralNetEndpointResolver.resolve());
    aiplay.runtime.role.ModelRoleRegistry reg = aiplay.runtime.role.ModelRoleRegistry.shared();
    l.info("AIPLAY_CFG pawn_policy=" + reg.getModelKey(aiplay.runtime.role.ModelRole.PAWN_POLICY));
    l.info("AIPLAY_CFG runtimeFps pawnPred="
        + reg.resolve(aiplay.runtime.role.ModelRole.PAWN_POLICY).runtime().predictionFps());
  }

  private static String safeMsg(Throwable t) {
    if (t == null) {
      return "-";
    }
    String m = t.getMessage();
    if (m == null || m.isBlank()) {
      return t.getClass().getSimpleName();
    }
    return m.replace('\n', ' ').replace('\r', ' ');
  }

  private static String f3(double v) {
    return String.format(java.util.Locale.ROOT, "%.3f", v);
  }

  /**
   * Override movement key fields in the DTO with the bot's own movement output
   * from MovementIntentBus.
   */
  private static void overlayMovementFromIntentBus(BehaviorTreeContext context, GameStateDto dto) {
    if (context == null || context.getBlackboard() == null) return;
    if (!context.getBlackboard().has(MOVEMENT_INTENT_BUS)) return;
    if (dto.playerPawn == null || dto.playerPawn.playerPawn == null) return;

    MovementIntentBus intentBus = context.getBlackboard().get(MOVEMENT_INTENT_BUS);
    MovementIntent intent = intentBus.latest();
    if (intent == null) return;

    PlayerPawnDto pp = dto.playerPawn.playerPawn;
    MovementPrimitive active = intent.locomotion;
    for (MovementPrimitive mp : MovementPrimitive.LOCOMOTION_VALUES) {
      mp.setMoveDtoOnPawn(pp, toKeyboardMoveDto(mp == active, mp.getMoveDtoFromPawn(pp)));
    }
    pp.bJump = toKeyboardMoveDto(intent.jump, pp.bJump);
    dto.playerPawn.bDuck = toKeyboardMoveDto(intent.duck, dto.playerPawn.bDuck);
  }

  private static KeyboardMoveDto toKeyboardMoveDto(boolean pressed, KeyboardMoveDto existing) {
    KeyboardMoveDto dto = (existing != null) ? existing : new KeyboardMoveDto();
    dto.value = pressed;
    dto.value_norm = pressed ? 1.0f : 0.0f;
    return dto;
  }
}
