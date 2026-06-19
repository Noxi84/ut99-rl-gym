package aiplay.rl;


import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ring buffer of (state, action, reward, next_state, done) transitions for the
 * joint {@code rl_pawn} policy. Collects experience from executor ticks
 * and flushes batches to disk as .npz files for the Python SAC trainer to
 * consume.
 *
 * <p>Joint policy NPZ layout includes per-skill reward decomp arrays
 * (movement/view/pitch/fire/altFire) and aux target supervision
 * (label + confidence) alongside the standard transition tensors.</p>
 */
public class ExperienceCollector {

  private static final Logger LOG = Logger.getLogger(ExperienceCollector.class.getName());
  private static final ExecutorService FLUSH_WRITER = Executors.newFixedThreadPool(4,
      Thread.ofPlatform().daemon(true).name("ec-flush-", 0).factory());

  private final int stateSize;
  private final int actionSize;
  private final int maxTransitions;
  private final int flushEvery;

  // Ring buffer arrays
  private final float[][] states;
  private final float[][] actions;
  private final float[] rewards;
  private final float[][] nextStates;
  private final float[] dones;
  /** Behavior-policy log_prob of {@link #actions}; NaN for legacy batches
   *  where Python should recompute it from the active actor. */
  private final float[] actionLogProbs;
  /** Sampled target_index per transition. -1 sentinel means "no target_head". */
  private final float[] targetIndices;
  /** log_prob of the sampled target_index under the policy at sampling time. */
  private final float[] targetLogProbs;

  // Joint policy per-skill reward decomp + aux target supervision arrays.
  private final float[] rewardMovement;
  private final float[] rewardView;
  private final float[] rewardPitch;
  private final float[] rewardFire;
  private final float[] rewardAltFire;
  /** Fase 2.5 CTDE — 6e per-skill reward channel for team_assist. */
  private final float[] rewardTeamAssist;
  /** 7e per-skill channel: residual (death + damage_taken). Geen actor-skill is direct
   *  verantwoordelijk, maar als eigen critic-head krijgt de policy een survival/damage-taken
   *  gradient — anders zit death/dmgTaken gradient-loos in de scalar (niet in reward_decomp_keys). */
  private final float[] rewardResidual;
  private final long[]  targetLabels;
  private final float[] targetConfidences;
  /** Fase 2.5 CTDE — per-tick closest-2 teammate state slice
   *  ({@link TeammateStateExtractor#SLICE_SIZE} floats per row). Critic-only input. */
  private final float[][] teammateStates;
  /** Fase 2.5 CTDE — closest-2 teammate state at the {@code next} step, paired with
   *  {@link #nextStates}. Same shape as {@link #teammateStates}. */
  private final float[][] nextTeammateStates;

  private int head = 0;       // next write position
  private int count = 0;      // total items in buffer (up to maxTransitions)
  private int unflushed = 0;  // items since last flush

  // Global monotonic record ID — stable across flushes; used to target a specific
  // past transition (e.g. retroactive kill-credit assignment).
  private long totalRecorded = 0;
  // Ring-buffer position and global index of the first still-unflushed record.
  private int firstUnflushedPos = 0;
  private long firstUnflushedGlobalIdx = 0;

  private final AtomicLong flushCounter = new AtomicLong(0);
  private final ReplayBufferWriter writer = new ReplayBufferWriter();
  private final Path replayBufferDir;

  /**
   * Policy role under which transitions in this collector were sampled —
   * 0 = {@link aiplay.runtime.config.PolicyRole#CURRENT} (live trainable policy),
   * 1 = {@link aiplay.runtime.config.PolicyRole#CHAMPION} (frozen snapshot).
   * Encoded as float in the NPZ for uniform tensor schema. Python loaders
   * may filter out role=1 rows before feeding SAC depending on the
   * {@code sac.json} {@code champion_experience_enabled} flag (see
   * {@link #championExperienceEnabled}). One value per collector instance:
   * each BotRuntime owns a single role for a given model.
   */
  private final int policyRole;

  /**
   * Whether transitions sampled under {@link aiplay.runtime.config.PolicyRole#CHAMPION}
   * should be written to NPZ. {@code true} = write them (SAC trainer picks
   * them up); {@code false} = skip the flush entirely. Mirrored by the
   * Python ingest path which drops role=1 rows when the same flag is off.
   */
  private final boolean championExperienceEnabled;

  public ExperienceCollector(int stateSize, int actionSize, RLConfig config) {
    this(stateSize, actionSize, config,
        Path.of(aiplay.runtime.config.SessionPaths.getSessionDir(), "rl-replay-buffer"),
        0);
  }

  public ExperienceCollector(int stateSize, int actionSize, RLConfig config, Path replayBufferDir) {
    this(stateSize, actionSize, config, replayBufferDir, 0);
  }

  public ExperienceCollector(int stateSize, int actionSize, RLConfig config,
      Path replayBufferDir, int policyRole) {
    this(stateSize, actionSize,
        config.getReplayBufferMaxTransitions(),
        config.getFlushEveryTransitions(),
        replayBufferDir, policyRole,
        config.isChampionExperienceEnabled());
  }

  /**
   * Test-vriendelijke constructor — neemt {@code maxTransitions} en
   * {@code flushEvery} direct als ints zodat unit tests geen volledige
   * {@link RLConfig} (en daarmee geen rewards.json / sac.json
   * config-tree) hoeven op te tuigen.
   */
  ExperienceCollector(int stateSize, int actionSize,
      int configMaxTransitions, int flushEvery,
      Path replayBufferDir, int policyRole,
      boolean championExperienceEnabled) {
    if (configMaxTransitions <= 0 || flushEvery <= 0) {
      throw new IllegalArgumentException("ExperienceCollector requires positive buffer sizing: "
          + "maxTransitions=" + configMaxTransitions + " flushEvery=" + flushEvery);
    }
    this.stateSize = stateSize;
    this.actionSize = actionSize;
    this.policyRole = policyRole;
    this.championExperienceEnabled = championExperienceEnabled;
    this.flushEvery = flushEvery;
    // Only unflushed rows can still receive retroactive reward/target updates.
    // Keeping a second flush window around doubled the large state tensors for
    // every live bot without adding usable history.
    this.maxTransitions = this.flushEvery;

    this.states = new float[maxTransitions][stateSize];
    this.actions = new float[maxTransitions][actionSize];
    this.rewards = new float[maxTransitions];
    this.nextStates = new float[maxTransitions][stateSize];
    this.dones = new float[maxTransitions];
    this.actionLogProbs = new float[maxTransitions];
    this.targetIndices = new float[maxTransitions];
    this.targetLogProbs = new float[maxTransitions];
    for (int i = 0; i < maxTransitions; i++) {
      this.actionLogProbs[i] = Float.NaN;
      this.targetIndices[i] = -1.0f;
    }

    this.rewardMovement = new float[maxTransitions];
    this.rewardView = new float[maxTransitions];
    this.rewardPitch = new float[maxTransitions];
    this.rewardFire = new float[maxTransitions];
    this.rewardAltFire = new float[maxTransitions];
    this.rewardTeamAssist = new float[maxTransitions];
    this.rewardResidual = new float[maxTransitions];
    this.targetLabels = new long[maxTransitions];
    this.targetConfidences = new float[maxTransitions];
    this.teammateStates = new float[maxTransitions][TeammateStateExtractor.SLICE_SIZE];
    this.nextTeammateStates = new float[maxTransitions][TeammateStateExtractor.SLICE_SIZE];
    // -1 sentinel = "geen event / masked" voor target_label, matched aan de
    // Python ReplayBuffer sentinel in train/rl/shared/sac_core/replay_buffer.py.
    java.util.Arrays.fill(this.targetLabels, -1L);

    this.replayBufferDir = replayBufferDir;
    // Ensure the replay buffer directory exists
    replayBufferDir.toFile().mkdirs();
  }

  /**
   * Record a single transition without per-skill decomp.
   *
   * <p>Joint-aware callers should use {@link #recordJoint} so the NPZ batch
   * carries the reward-decomp + target supervision columns the Python
   * trainer expects. This simple overload fills the decomp slots with zero
   * and {@code -1} sentinels — used by tests and offline replays where
   * decomp is computed elsewhere or not needed.</p>
   *
   * @param state     current state feature vector [stateSize]
   * @param action    action taken [actionSize]
   * @param reward    scalar reward
   * @param nextState next state feature vector [stateSize]
   * @param done      true if episode ended
   */
  public synchronized long record(float[] state, float[] action, double reward,
      float[] nextState, boolean done) {
    return record(state, action, reward, nextState, done, -1, 0.0f);
  }

  /**
   * Overload that records a sampled target_index + its log_prob alongside the
   * standard transition. Pass {@code targetIdx=-1} when the target_head was
   * not sampled.
   */
  public synchronized long record(float[] state, float[] action, double reward,
      float[] nextState, boolean done,
      int targetIdx, float targetLogProb) {
    return record(state, action, reward, nextState, done, Float.NaN, targetIdx, targetLogProb);
  }

  /**
   * Records a transition with an optional behavior-policy action log_prob.
   * Pass {@link Float#NaN} when not available; the Python loader preserves the
   * sentinel and SAC recomputes the log_prob from the current actor.
   */
  public synchronized long record(float[] state, float[] action, double reward,
      float[] nextState, boolean done,
      float actionLogProb, int targetIdx, float targetLogProb) {
    long globalIdx = totalRecorded;
    System.arraycopy(state, 0, states[head], 0, stateSize);
    System.arraycopy(action, 0, actions[head], 0, actionSize);
    rewards[head] = (float) reward;
    System.arraycopy(nextState, 0, nextStates[head], 0, stateSize);
    dones[head] = done ? 1.0f : 0.0f;
    actionLogProbs[head] = actionLogProb;
    targetIndices[head] = (float) targetIdx;
    targetLogProbs[head] = targetLogProb;

    rewardMovement[head] = 0f;
    rewardView[head] = 0f;
    rewardPitch[head] = 0f;
    rewardFire[head] = 0f;
    rewardAltFire[head] = 0f;
    rewardTeamAssist[head] = 0f;
    rewardResidual[head] = 0f;
    targetLabels[head] = -1L;
    targetConfidences[head] = 0f;
    java.util.Arrays.fill(teammateStates[head], 0f);
    java.util.Arrays.fill(nextTeammateStates[head], 0f);

    head = (head + 1) % maxTransitions;
    if (count < maxTransitions) {
      count++;
    }
    unflushed++;
    totalRecorded++;

    if (unflushed >= flushEvery) {
      flush();
    }
    return globalIdx;
  }

  /**
   * Records a transition with the per-skill reward decomp and provisional
   * target_label/confidence. The caller is responsible for post-hoc updating
   * the label via {@link #setTargetLabelAt(long, long, float)} when
   * kill-attribution within the K-tick window confirms a hit.
   *
   * <p>The {@code teammateState} aux slice (Fase 2.5 CTDE,
   * {@link TeammateStateExtractor#SLICE_SIZE} floats) is critic-only — never fed to the runtime
   * actor. Must be non-null with exactly {@link TeammateStateExtractor#SLICE_SIZE} entries.
   */
  public synchronized long recordJoint(float[] state, float[] action, double reward,
      float[] nextState, boolean done,
      float actionLogProb, int targetIdx, float targetLogProb,
      RewardDecomposition decomposition,
      long provisionalTargetLabel, float provisionalTargetConfidence,
      float[] teammateState, float[] nextTeammateState) {
    if (decomposition == null) {
      throw new IllegalArgumentException(
          "ExperienceCollector.recordJoint requires non-null RewardDecomposition");
    }
    if (teammateState == null || teammateState.length != TeammateStateExtractor.SLICE_SIZE) {
      throw new IllegalArgumentException(
          "ExperienceCollector.recordJoint requires teammateState length "
              + TeammateStateExtractor.SLICE_SIZE + ", got "
              + (teammateState == null ? "null" : Integer.toString(teammateState.length)));
    }
    if (nextTeammateState == null
        || nextTeammateState.length != TeammateStateExtractor.SLICE_SIZE) {
      throw new IllegalArgumentException(
          "ExperienceCollector.recordJoint requires nextTeammateState length "
              + TeammateStateExtractor.SLICE_SIZE + ", got "
              + (nextTeammateState == null ? "null"
                  : Integer.toString(nextTeammateState.length)));
    }
    long globalIdx = totalRecorded;
    System.arraycopy(state, 0, states[head], 0, stateSize);
    System.arraycopy(action, 0, actions[head], 0, actionSize);
    rewards[head] = (float) reward;
    System.arraycopy(nextState, 0, nextStates[head], 0, stateSize);
    dones[head] = done ? 1.0f : 0.0f;
    actionLogProbs[head] = actionLogProb;
    targetIndices[head] = (float) targetIdx;
    targetLogProbs[head] = targetLogProb;

    rewardMovement[head] = (float) decomposition.rewardMovement();
    rewardView[head] = (float) decomposition.rewardView();
    rewardPitch[head] = (float) decomposition.rewardPitch();
    rewardFire[head] = (float) decomposition.rewardFire();
    rewardAltFire[head] = (float) decomposition.rewardAltFire();
    rewardTeamAssist[head] = (float) decomposition.rewardTeamAssist();
    rewardResidual[head] = (float) decomposition.rewardResidual();
    targetLabels[head] = provisionalTargetLabel;
    targetConfidences[head] = provisionalTargetConfidence;
    System.arraycopy(teammateState, 0, teammateStates[head], 0,
        TeammateStateExtractor.SLICE_SIZE);
    System.arraycopy(nextTeammateState, 0, nextTeammateStates[head], 0,
        TeammateStateExtractor.SLICE_SIZE);

    head = (head + 1) % maxTransitions;
    if (count < maxTransitions) {
      count++;
    }
    unflushed++;
    totalRecorded++;

    if (unflushed >= flushEvery) {
      flush();
    }
    return globalIdx;
  }

  /**
   * Retroactief bijwerken van het target_label voor een eerder geregistreerde
   * transitie. Symmetrische tegenhanger van {@link #addRewardAt(long, double)}
   * voor de aux target supervision — gebruikt door
   * {@link PerModelExperienceRecorder} wanneer kill-attribution binnen het
   * K-tick venster een definitief slot bevestigt (confidence=1.0) voor recente
   * transities die provisioneel met closest-visible label geregistreerd zijn.
   *
   * @return true als de transitie nog in de unflushed buffer zit; false als
   *         ze al naar disk zijn geflushed (te laat) of nog niet bestaan.
   */
  public synchronized boolean setTargetLabelAt(long globalIdx, long label, float confidence) {
    if (globalIdx < firstUnflushedGlobalIdx) {
      return false; // already flushed
    }
    if (globalIdx >= totalRecorded) {
      return false; // not yet written
    }
    int offset = (int) (globalIdx - firstUnflushedGlobalIdx);
    int pos = (firstUnflushedPos + offset) % maxTransitions;
    targetLabels[pos] = label;
    targetConfidences[pos] = confidence;
    return true;
  }

  /**
   * Retroactively add a reward delta to a previously-recorded transition. Used for credit-assignment across a temporal gap (e.g. projectile kills: attribute the kill reward back to the frame where the fire onset occurred).
   *
   * @param globalIdx the globalIdx returned by {@link #record} when the target transition was written
   * @param delta     reward to add (positive or negative)
   * @return true if the target transition was still unflushed and was modified; false if it was already flushed to disk (too late) or never existed
   */
  public synchronized boolean addRewardAt(long globalIdx, double delta) {
      if (globalIdx < firstUnflushedGlobalIdx) {
          return false; // already flushed
      }
      if (globalIdx >= totalRecorded) {
          return false;          // not yet written
      }
    int offset = (int) (globalIdx - firstUnflushedGlobalIdx);
    int pos = (firstUnflushedPos + offset) % maxTransitions;
    rewards[pos] += (float) delta;
    return true;
  }

  /**
   * Flush unflushed transitions to disk as an .npz file.
   *
   * <p>Champion-bot collectors short-circuit only when
   * {@code sac.json:champion_experience_enabled=false}: in dat geval
   * worden rijen van een frozen-snapshot collector aan trainer-zijde toch
   * gedropt, dus schrijven is pure verspilling (disk I/O op de bot host,
   * network sync naar de trainer, decompress + filter op de trainer).
   *
   * <p>Met {@code champion_experience_enabled=true} schrijven champion-bot
   * collectors net als current-bot collectors: SAC is off-policy en kan
   * van die transities leren; de {@code policy_role} tag blijft in de NPZ
   * voor downstream analyse en eventueel per-role weging.
   */
  public synchronized void flush() {
      if (unflushed == 0) {
          return;
      }

    if (policyRole != 0 && !championExperienceEnabled) {
      // Champion bot, filter aan — discard unflushed rows without writing.
      // Reset bookkeeping the same way a successful flush would, so the
      // ring buffer is ready for the next batch.
      long flushId = flushCounter.incrementAndGet();
      LOG.info("RL_FLUSH_SKIP_CHAMPION batch=" + flushId
          + " transitions=" + unflushed + " policy_role=" + policyRole);
      unflushed = 0;
      firstUnflushedPos = head;
      firstUnflushedGlobalIdx = totalRecorded;
      return;
    }

    int batchSize = unflushed;
    int startIdx = (head - batchSize + maxTransitions) % maxTransitions;

    float[][] batchStates = new float[batchSize][];
    float[][] batchActions = new float[batchSize][];
    float[] batchRewards = new float[batchSize];
    float[][] batchNextStates = new float[batchSize][];
    float[] batchDones = new float[batchSize];
    float[] batchActionLogProbs = new float[batchSize];
    float[] batchTargetIndices = new float[batchSize];
    float[] batchTargetLogProbs = new float[batchSize];
    boolean anyActionLogProbSet = false;
    boolean anyTargetSet = false;

    float[] batchRewardMovement = new float[batchSize];
    float[] batchRewardView = new float[batchSize];
    float[] batchRewardPitch = new float[batchSize];
    float[] batchRewardFire = new float[batchSize];
    float[] batchRewardAltFire = new float[batchSize];
    float[] batchRewardTeamAssist = new float[batchSize];
    float[] batchRewardResidual = new float[batchSize];
    long[]  batchTargetLabel = new long[batchSize];
    float[] batchTargetConfidence = new float[batchSize];
    float[][] batchTeammateState = new float[batchSize][];
    float[][] batchNextTeammateState = new float[batchSize][];

    for (int i = 0; i < batchSize; i++) {
      int idx = (startIdx + i) % maxTransitions;
      batchStates[i] = states[idx].clone();
      batchActions[i] = actions[idx].clone();
      batchRewards[i] = rewards[idx];
      batchNextStates[i] = nextStates[idx].clone();
      batchDones[i] = dones[idx];
      batchActionLogProbs[i] = actionLogProbs[idx];
      batchTargetIndices[i] = targetIndices[idx];
      batchTargetLogProbs[i] = targetLogProbs[idx];
      if (Float.isFinite(actionLogProbs[idx])) {
        anyActionLogProbSet = true;
      }
      if (targetIndices[idx] >= 0.0f) {
        anyTargetSet = true;
      }
      batchRewardMovement[i] = rewardMovement[idx];
      batchRewardView[i] = rewardView[idx];
      batchRewardPitch[i] = rewardPitch[idx];
      batchRewardFire[i] = rewardFire[idx];
      batchRewardAltFire[i] = rewardAltFire[idx];
      batchRewardTeamAssist[i] = rewardTeamAssist[idx];
      batchRewardResidual[i] = rewardResidual[idx];
      batchTargetLabel[i] = targetLabels[idx];
      batchTargetConfidence[i] = targetConfidences[idx];
      batchTeammateState[i] = teammateStates[idx].clone();
      batchNextTeammateState[i] = nextTeammateStates[idx].clone();
    }

    // Reset bookkeeping BEFORE async write — ring buffer is free for reuse.
    unflushed = 0;
    firstUnflushedPos = head;
    firstUnflushedGlobalIdx = totalRecorded;

    long flushId = flushCounter.incrementAndGet();
    Path outputPath = replayBufferDir.resolve(
        String.format("batch_%s_%06d_%d.npz", getMachineId(), flushId, System.currentTimeMillis()));

    float[] batchPolicyRole = new float[batchSize];
    if (policyRole != 0) {
      java.util.Arrays.fill(batchPolicyRole, (float) policyRole);
    }

    boolean logProbPresent = anyActionLogProbSet;
    boolean targetPresent = anyTargetSet;

    FLUSH_WRITER.execute(() -> {
      ReplayBufferWriter.JointExperienceExtras jointExtras =
          new ReplayBufferWriter.JointExperienceExtras(
              batchRewardMovement, batchRewardView, batchRewardPitch, batchRewardFire, batchRewardAltFire,
              batchRewardTeamAssist, batchRewardResidual,
              batchTargetLabel, batchTargetConfidence,
              batchTeammateState, batchNextTeammateState);
      try {
        long t0 = System.nanoTime();
        writer.write(outputPath, batchStates, batchActions, batchRewards,
            batchNextStates, batchDones,
            logProbPresent ? batchActionLogProbs : null,
            targetPresent ? batchTargetIndices : null,
            targetPresent ? batchTargetLogProbs : null,
            batchPolicyRole,
            jointExtras);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info("RL_FLUSH batch=" + flushId + " transitions=" + batchSize
            + " policy_role=" + policyRole
            + " write_ms=" + ms
            + " path=" + outputPath);
      } catch (IOException e) {
        LOG.log(Level.SEVERE, "RL_FLUSH_ERROR batch=" + flushId, e);
      }
    });
  }

  private static String getMachineId() {
    String env = System.getenv("UT99_MACHINE_ID");
      if (env != null && !env.isBlank()) {
          return env.trim();
      }
    String sys = System.getProperty("UT99_MACHINE_ID");
      if (sys != null && !sys.isBlank()) {
          return sys.trim();
      }
    try {
      String hostname = java.net.InetAddress.getLocalHost().getHostName();
      return hostname.length() > 8 ? hostname.substring(0, 8) : hostname;
    } catch (Exception e) {
      return "unknown";
    }
  }
}
