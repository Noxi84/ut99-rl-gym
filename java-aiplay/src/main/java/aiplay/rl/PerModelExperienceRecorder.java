package aiplay.rl;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.rl.rewards.core.RewardBreakdown;
import aiplay.rl.rewards.core.RewardCategory;
import aiplay.rl.rewards.core.RewardSignal;
import aiplay.rl.targeting.JointTargetAttribution;
import java.util.logging.Logger;

/**
 * Records per-model experience transitions for SAC training.
 *
 * <p>Each model's executor calls {@link #onTick(float[][][], float[], GameStateDto)}
 * after every prediction. The recorder buffers one tick and records the transition on the next tick (when reward and next_state become available).</p>
 *
 * <p>The flat state vector is the entire input tensor flattened:
 * input[0] of shape [seqLen][nFeatures] → float[seqLen * nFeatures].</p>
 */
public class PerModelExperienceRecorder {

  private static final Logger LOG = Logger.getLogger(PerModelExperienceRecorder.class.getName());

  private final ExperienceCollector collector;
  private final RewardComputer rewardComputer;
  private final String modelKey;
  private final boolean breakdownEnabled;
  private final int breakdownWindowSize;
  private final int recordInterval;
  private final boolean killCreditBackpropEnabled;
  private final int killCreditWindowTicks;

  /** Ringbuffer van recente joint-mode transities (globalIdx + tick + enemy
   *  health snapshot) zodat we bij een kill de geraakte slot kunnen
   *  identificeren en retroactief labels kunnen update. Capaciteit = K + 1. */
  private final long[] jointBufferGlobalIdx;
  private final long[] jointBufferTick;
  private final String[][] jointBufferEnemyNames;
  private final int[][] jointBufferEnemyHealth;
  private int jointBufferHead = 0;
  private int jointBufferSize = 0;

  // Buffered from previous tick
  private float[] prevFlatState;
  private float[] prevAction;
  private GameStateDto prevGameState;
  private float prevActionLogProb = Float.NaN;
  /**
   * Phase 2d: target_index sampled by shooting model at prev tick. -1 sentinel for non-shooting models or pre-Phase-2 ONNX.
   */
  private int prevTargetIdx = -1;
  private float prevTargetLogProb = 0.0f;

  private long tickCount = 0;
  private long recordCount = 0;

  // Credit-assignment bookkeeping: remembers the transition in which the
  // most recent fire onset was recorded, so a later kill can be attributed
  // back to it rather than to the kill-frame itself.
  private long lastFireGlobalIdx = -1L;
  private long lastFireTick = -1L;
  private long killCreditApplied = 0;
  private long killCreditMissedTooOld = 0;
  private long killCreditMissedFlushed = 0;

  // Windowed reward totals for breakdown logging. Per-signaal accumulator
  // geïndexeerd op RewardSignal.ordinal(): itereert over de signaal-catalog i.p.v.
  // een hand-gesynchroniseerde veldenlijst (die dreef — 6 signalen ontbraken).
  private int windowCount = 0;
  private double windowTotal = 0.0;
  private final double[] windowSignals = new double[RewardSignal.COUNT];

  public PerModelExperienceRecorder(ExperienceCollector collector,
      RewardComputer rewardComputer,
      String modelKey,
      int recordInterval) {
    this.collector = collector;
    this.rewardComputer = rewardComputer;
    this.modelKey = modelKey;
    this.recordInterval = Math.max(1, recordInterval);
    this.breakdownEnabled = rewardComputer != null && rewardComputer.isBreakdownEnabled();
    this.breakdownWindowSize = (rewardComputer != null)
        ? rewardComputer.getBreakdownWindowSize() : 200;
    aiplay.rl.rewards.catalog.RewardCatalog catalog =
        rewardComputer != null ? rewardComputer.getCatalog() : null;
    this.killCreditBackpropEnabled =
        catalog != null && catalog.combatEvent().killCreditBackpropEnabled();
    this.killCreditWindowTicks =
        catalog != null ? catalog.combatEvent().killCreditWindowTicks() : 0;
    int cap = JointTargetAttribution.KILL_ATTRIBUTION_WINDOW_TICKS + 1;
    this.jointBufferGlobalIdx = new long[cap];
    this.jointBufferTick = new long[cap];
    this.jointBufferEnemyNames = new String[cap][JointTargetAttribution.MAX_ENEMY_SLOTS];
    this.jointBufferEnemyHealth = new int[cap][JointTargetAttribution.MAX_ENEMY_SLOTS];
  }

  /**
   * Called by the executor after each prediction tick.
   *
   * @param input     the model input tensor [1][seqLen][nFeatures]
   * @param action    the action vector (model output or decoded actions)
   * @param gameState the current game state (for reward computation)
   */
  public void onTick(float[][][] input, float[] action, GameStateDto gameState) {
    onTick(input, action, gameState, -1, 0.0f);
  }

  /**
   * Accepts the shooting model's sampled target_index and its log_prob so the
   * target_head supervision signal can be paired with the action. -1 sentinel
   * for non-shooting models.
   */
  public void onTick(float[][][] input, float[] action, GameStateDto gameState,
      int targetIdx, float targetLogProb) {
    onTick(input, action, gameState, Float.NaN, targetIdx, targetLogProb);
  }

  /**
   * Shooting overload — records the sampled binary policy action plus its
   * behavior-policy log_prob. The action is before weapon cooldown suppression
   * but after fire/alt-fire mutual exclusion, matching the Python policy
   * distribution.
   */
  public void onTick(float[][][] input, float[] action, GameStateDto gameState,
      float actionLogProb, int targetIdx, float targetLogProb) {
    tickCount++;
    if (recordInterval > 1 && tickCount % recordInterval != 0) {
      return;
    }
    processTick(flattenInput(input), action, gameState, actionLogProb, targetIdx, targetLogProb);
  }

  /**
   * Offline-replay entry — gebruikt door {@code GenerateExperienceFromRecordingsMain}
   * dat al pre-flattened state vectors uit {@code .rec.gz} files leest. Slaat
   * de input → flat conversie + subsampling over: bij replay verwerken we
   * elke tick uit de recording precies één keer.
   */
  public void onTickFromFlat(float[] flatState, float[] action, GameStateDto gameState,
      float actionLogProb, int targetIdx, float targetLogProb) {
    tickCount++;
    processTick(flatState, action, gameState, actionLogProb, targetIdx, targetLogProb);
  }

  /**
   * Reset de prev-state buffer en credit-assignment tracker zonder een
   * artificiële {@code done=true} transitie te schrijven. Gebruikt door de
   * replay tool tussen verschillende {@code runId}'s: een run-boundary is geen
   * episode-einde maar wel een transitie-discontinuïteit.
   */
  public void resetEpisode() {
    prevFlatState = null;
    prevAction = null;
    prevGameState = null;
    prevActionLogProb = Float.NaN;
    prevTargetIdx = -1;
    prevTargetLogProb = 0.0f;
    lastFireGlobalIdx = -1L;
    lastFireTick = -1L;
  }

  private void processTick(float[] flatState, float[] action, GameStateDto gameState,
      float actionLogProb, int targetIdx, float targetLogProb) {

    // Skip recording entirely during the post-match window (end-screen + ServerTravel
    // map reload). The first bGameEnded=true frame still flushes a done=true transition
    // via the branch below (which then resets prev-state to null); subsequent post-match
    // ticks short-circuit here so we don't buffer transitions against a frozen scoreboard.
    boolean ended = gameState != null && gameState.mapInfo != null && gameState.mapInfo.bGameEnded;
    if (ended && prevFlatState == null) {
      return;
    }

    if (prevFlatState != null && prevGameState != null && gameState != null) {
      // Joint recorders hebben altijd decomp nodig — die berekent ook de
      // breakdown intern, dus we hergebruiken voor logging.
      RewardBreakdown bd = rewardComputer.computeWithBreakdown(
          prevGameState, gameState, prevAction, prevTargetIdx);
      RewardDecomposition decomposition = rewardComputer.decomposeBreakdown(bd);
      double reward = decomposition.rewardScalar();
      double killCreditReward = bd.value(RewardSignal.ENEMY_KILLED_BY_FIRE);
      if (breakdownEnabled) {
        accumulateWindow(bd);
      }

      // When backprop is enabled, strip the kill reward from the kill-frame
      // (we'll re-attribute it to the fire-onset frame below). Requires
      // breakdown so we know the exact amount to subtract.
      if (killCreditBackpropEnabled && breakdownEnabled && killCreditReward != 0.0) {
        reward -= killCreditReward;
      }

      // Done if player died (deaths counter increased), playerPawn disappeared,
      // or the match just ended (UT99 bGameEnded — first post-match frame closes
      // the episode so SAC sees a clean terminal transition).
      boolean done = gameState.playerPawn == null
          || (prevGameState.playerPawn != null
          && gameState.playerPawn.deaths > prevGameState.playerPawn.deaths)
          || ended;

      // Provisional label: closest-visible heuristiek op het prev-frame, met
      // fire-edge confidence-bump. Retro-fill via frag-detectie hieronder
      // promoveert dit naar CONF_HIT zodra een kill bevestigd is.
      boolean fireEdge = JointTargetAttribution.isFireEdge(prevGameState, gameState);
      JointTargetAttribution.TargetLabel label =
          JointTargetAttribution.provisional(prevGameState, fireEdge);
      long provisionalLabel = (label.slot() >= 0) ? label.slot() : -1L;
      float provisionalConf = label.confidence();
      // Fase 2.5 CTDE — extract closest-2 teammate slice from prev game state (synchronous with
      // prevFlatState/prevAction) and curr game state (synchronous with flatState/nextState).
      // Critic-only input; never reaches the runtime actor. Both are needed because the Bellman
      // target evaluates Q_target on (s', a') and the CTDE critic concatenates teammate_state to
      // self_state at both ends of the transition.
      float[] teammateStateSlice = TeammateStateExtractor.extract(prevGameState);
      float[] nextTeammateStateSlice = TeammateStateExtractor.extract(gameState);
      long recordedIdx = collector.recordJoint(prevFlatState, prevAction, reward, flatState, done,
          prevActionLogProb, prevTargetIdx, prevTargetLogProb,
          decomposition, provisionalLabel, provisionalConf,
          teammateStateSlice, nextTeammateStateSlice);
      // Bewaar deze transitie in de K-tick ringbuffer zodat een latere kill
      // het label retroactief naar HIT (conf=1.0) kan promoveren.
      pushJointBuffer(recordedIdx, tickCount, prevGameState);
      recordCount++;

      // Fire onset in the transition we just recorded — mark it as the
      // current credit target. Use the prev→curr edge so the onset lines
      // up with the state/action that caused the shot.
      if (killCreditBackpropEnabled
          && prevGameState.playerPawn != null && gameState.playerPawn != null
          && !prevGameState.playerPawn.fireActive && gameState.playerPawn.fireActive) {
        lastFireGlobalIdx = recordedIdx;
        lastFireTick = tickCount;
      }

      // Kill detected → try to attribute the reward back to the most
      // recent fire onset within the credit window.
      if (killCreditBackpropEnabled && killCreditReward != 0.0) {
        if (lastFireGlobalIdx < 0
            || (killCreditWindowTicks > 0
            && (tickCount - lastFireTick) > killCreditWindowTicks)) {
          killCreditMissedTooOld++;
        } else if (collector.addRewardAt(lastFireGlobalIdx, killCreditReward)) {
          killCreditApplied++;
        } else {
          killCreditMissedFlushed++;
        }
        // One fire onset is credited for at most one kill.
        lastFireGlobalIdx = -1L;
        lastFireTick = -1L;
      }

      // Detecteer een frag-event en retro-fill target_label (confidence=1.0)
      // voor alle in-flight transities binnen het K-tick venster. Parallel
      // aan de kill-credit reward-backprop, maar voor de aux target
      // supervision (BC-anchor anneal pad).
      if (prevGameState.playerPawn != null && gameState.playerPawn != null
          && gameState.playerPawn.frags > prevGameState.playerPawn.frags) {
        int killedSlot = JointTargetAttribution.identifyKilledEnemySlot(prevGameState, gameState);
        if (killedSlot >= 0) {
          retroFillTargetLabel(killedSlot, tickCount);
        }
      }

      if (recordCount <= 3 || recordCount % 10000 == 0) {
        LOG.info("RL_EXP_" + modelKey + " records=" + recordCount
            + " ticks=" + tickCount + " reward=" + String.format("%.4f", reward)
            + " done=" + done
            + (killCreditBackpropEnabled
            ? " killCredit[applied=" + killCreditApplied
              + " missedAge=" + killCreditMissedTooOld
              + " missedFlush=" + killCreditMissedFlushed + "]"
            : ""));
      }

      if (done) {
        // Episode boundary — reset buffer (incl. credit tracker: no
        // cross-episode attribution after death).
        prevFlatState = null;
        prevAction = null;
        prevGameState = null;
        prevActionLogProb = Float.NaN;
        prevTargetIdx = -1;
        prevTargetLogProb = 0.0f;
        lastFireGlobalIdx = -1L;
        lastFireTick = -1L;
        return;
      }
    }

    // Buffer current tick for next transition
    prevFlatState = flatState;
    prevAction = action.clone();
    prevGameState = gameState;
    prevActionLogProb = actionLogProb;
    prevTargetIdx = targetIdx;
    prevTargetLogProb = targetLogProb;
  }

  /**
   * Flush remaining experience to disk. Call on shutdown.
   */
  public void flush() {
    collector.flush();
  }

  public long getRecordCount() {
    return recordCount;
  }

  private void accumulateWindow(RewardBreakdown bd) {
    windowCount++;
    windowTotal += bd.total();
    for (RewardSignal s : RewardSignal.values()) {
      windowSignals[s.ordinal()] += bd.value(s);
    }
    if (windowCount >= breakdownWindowSize) {
      LOG.info(formatWindow());
      resetWindow();
    }
  }

  /**
   * Bouwt de {@code RL_REWARD}-window log dynamisch over de signaal-catalog. Toont
   * elk {@link RewardSignal} (geen hand-gesynchroniseerde format-string meer, die
   * dreef en 6 signalen miste). Kolomnamen blijven de {@code fieldName()}'s, dus
   * bestaande log-greps op bv. {@code flagTaken=} blijven werken.
   */
  private String formatWindow() {
    StringBuilder sb = new StringBuilder(640);
    sb.append(String.format(
        "RL_REWARD_%s window=%d | sparse=%.3f dense=%.3f action=%.3f total=%.3f |",
        modelKey, windowCount,
        categorySum(RewardCategory.SPARSE), categorySum(RewardCategory.DENSE),
        categorySum(RewardCategory.ACTION), windowTotal));
    for (RewardSignal s : RewardSignal.values()) {
      sb.append(' ').append(s.fieldName()).append('=')
          .append(String.format("%.3f", windowSignals[s.ordinal()]));
    }
    return sb.toString();
  }

  private double categorySum(RewardCategory category) {
    double sum = 0.0;
    for (RewardSignal s : RewardSignal.values()) {
      if (s.category() == category) {
        sum += windowSignals[s.ordinal()];
      }
    }
    return sum;
  }

  private void resetWindow() {
    windowCount = 0;
    windowTotal = 0.0;
    java.util.Arrays.fill(windowSignals, 0.0);
  }

  /**
   * Flatten input[0] from [seqLen][nFeatures] to [seqLen * nFeatures].
   */
  private static float[] flattenInput(float[][][] input) {
    float[][] matrix = input[0];
    int seqLen = matrix.length;
    int nFeatures = matrix[0].length;
    float[] flat = new float[seqLen * nFeatures];
    for (int t = 0; t < seqLen; t++) {
      System.arraycopy(matrix[t], 0, flat, t * nFeatures, nFeatures);
    }
    return flat;
  }

  // =============================================================================
  // Joint VR+shooting helpers — alleen actief wanneer jointModeEnabled == true.
  // =============================================================================

  /**
   * Voeg een nieuwe transitie toe aan de K-tick ringbuffer. We bewaren genoeg
   * info (enemy names + health snapshot) om bij een latere kill de geraakte
   * slot terug te zoeken via name-matching.
   */
  private void pushJointBuffer(long globalIdx, long tick, GameStateDto state) {
    int cap = jointBufferGlobalIdx.length;
    jointBufferGlobalIdx[jointBufferHead] = globalIdx;
    jointBufferTick[jointBufferHead] = tick;
    PlayerDto[] enemies = (state != null) ? state.enemies : null;
    int n = (enemies != null) ? Math.min(enemies.length, JointTargetAttribution.MAX_ENEMY_SLOTS) : 0;
    for (int s = 0; s < JointTargetAttribution.MAX_ENEMY_SLOTS; s++) {
      if (s < n && enemies[s] != null) {
        jointBufferEnemyNames[jointBufferHead][s] = enemies[s].name;
        jointBufferEnemyHealth[jointBufferHead][s] = enemies[s].health;
      } else {
        jointBufferEnemyNames[jointBufferHead][s] = null;
        jointBufferEnemyHealth[jointBufferHead][s] = 0;
      }
    }
    jointBufferHead = (jointBufferHead + 1) % cap;
    if (jointBufferSize < cap) jointBufferSize++;
  }

  /**
   * Retro-fill target_label voor alle K-tick venster transities. Loopt door
   * de ringbuffer en update labels voor transities binnen
   * {@link JointTargetAttribution#KILL_ATTRIBUTION_WINDOW_TICKS} ticks vanaf
   * de kill-tick. Onder de grens worden transities overgeslagen (te oud);
   * boven de grens bestaan ze niet (toekomst).
   */
  private void retroFillTargetLabel(int killedSlot, long killTick) {
    int cap = jointBufferGlobalIdx.length;
    int updated = 0;
    for (int i = 0; i < jointBufferSize; i++) {
      // Walk de buffer van oudste naar nieuwste.
      int pos = (jointBufferHead - jointBufferSize + i + cap) % cap;
      long tick = jointBufferTick[pos];
      if (tick > killTick) continue;
      if (killTick - tick > JointTargetAttribution.KILL_ATTRIBUTION_WINDOW_TICKS) continue;
      // Alleen update als de geraakte slot in deze transitie ook leefde
      // (anders was de bot niet aan het mikken op hem).
      int hpAtTransition = jointBufferEnemyHealth[pos][killedSlot];
      if (hpAtTransition <= 0) continue;
      if (collector.setTargetLabelAt(jointBufferGlobalIdx[pos], killedSlot,
              JointTargetAttribution.CONF_HIT)) {
        updated++;
      }
    }
    if (updated > 0 && (recordCount < 50 || recordCount % 5000 == 0)) {
      int updatedSnapshot = updated;
      LOG.fine(() -> "RL_JOINT_TARGET_RETRO_FILL model=" + modelKey
          + " killedSlot=" + killedSlot + " transitions_updated=" + updatedSnapshot);
    }
  }

  /** Test-only: bezoek de inhoud van de K-tick ringbuffer (lengte = aantal
   *  actieve entries). Gebruikt door {@code ExperienceWriterDecompTest.testTargetLabelRetroFill}
   *  om te verifiëren dat retro-fill alle in-window transities raakt. */
  long[] testJointBufferTicks() {
    long[] out = new long[jointBufferSize];
    int cap = jointBufferGlobalIdx.length;
    for (int i = 0; i < jointBufferSize; i++) {
      int pos = (jointBufferHead - jointBufferSize + i + cap) % cap;
      out[i] = jointBufferTick[pos];
    }
    return out;
  }

  /** Test-only: idem als {@link #testJointBufferTicks()} maar voor globalIdx. */
  long[] testJointBufferGlobalIdx() {
    long[] out = new long[jointBufferSize];
    int cap = jointBufferGlobalIdx.length;
    for (int i = 0; i < jointBufferSize; i++) {
      int pos = (jointBufferHead - jointBufferSize + i + cap) % cap;
      out[i] = jointBufferGlobalIdx[pos];
    }
    return out;
  }
}
