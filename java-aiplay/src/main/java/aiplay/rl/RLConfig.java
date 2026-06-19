package aiplay.rl;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-model SAC-algoritme configuratie + model/role-identiteit.
 *
 * <p>Reward-parameters (match-timing, wapen-snelheden, rocket-aimpunt, pitch-curriculum,
 * reward-breakdown) leven NIET hier maar in {@link aiplay.rl.rewards.core.RewardTuningConfig}
 * (module {@code java-rewards}), geladen uit {@code rewards.json}. Reward-weights worden via
 * {@link aiplay.rl.rewards.catalog.json.JsonRewardCatalog} als typed {@code *Params} naar elke
 * {@link aiplay.rl.rewards.core.RewardComponent} geïnjecteerd. {@code RLConfig} houdt alleen de
 * SAC-algoritme-knobs uit {@code sac.json} plus de model/role-identiteit.
 *
 * <p>Geen silent defaults — alle properties zijn vereist in {@code sac.json}; ontbrekende velden
 * crashen bij constructie (CLAUDE.md no-fallback rule).
 */
public class RLConfig {

  private final String modelKey;
  private final String rewardGroupSelector;

  // Algoritme
  private final boolean experienceSyncEnabled;
  private final boolean championExperienceEnabled;
  private final boolean deterministicInference;
  private final int replayBufferMaxTransitions;
  private final int flushEveryTransitions;
  private final int experienceRecordInterval;
  private final double movementExplorationStd;
  private final double movementExplorationEdgeDropThreshold;
  private final double movementExplorationEdgeScale;

  public RLConfig(String modelKey, String rewardGroupSelector) {
    if (rewardGroupSelector == null || rewardGroupSelector.isBlank()) {
      throw new IllegalArgumentException(
          "RLConfig requires a non-empty rewardgroup selector (role).");
    }
    this.modelKey = modelKey;
    this.rewardGroupSelector = rewardGroupSelector.trim();

    JsonNode sac = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/sac");
    if (sac == null) {
      throw new IllegalStateException("No sac.json found for model: " + modelKey);
    }
    this.experienceSyncEnabled = requireBoolean(sac, "experience_sync_enabled", "sac");
    this.championExperienceEnabled = requireBoolean(sac, "champion_experience_enabled", "sac");
    this.deterministicInference = requireBoolean(sac, "deterministic_inference", "sac");
    this.replayBufferMaxTransitions = requireInt(sac, "replay_buffer_max_transitions", "sac");
    this.flushEveryTransitions = requireInt(sac, "flush_every_transitions", "sac");
    this.experienceRecordInterval = requireInt(sac, "experience_record_interval", "sac");
    this.movementExplorationStd = requireDouble(sac, "movement_exploration_std", "sac");
    this.movementExplorationEdgeDropThreshold =
        requireDouble(sac, "movement_exploration_edge_drop_threshold", "sac");
    this.movementExplorationEdgeScale =
        requireDouble(sac, "movement_exploration_edge_scale", "sac");
  }

  public String getModelKey() {
    return modelKey;
  }

  public String getRewardGroupSelector() {
    return rewardGroupSelector;
  }

  public boolean isExperienceSyncEnabled() {
    return experienceSyncEnabled;
  }

  /**
   * Schakelt experience-collection van champion-bots (frozen snapshots).
   *
   * <p>{@code true}: champion-bot transities worden net als current-bot
   * transities naar NPZ geschreven en in de SAC replay buffer opgenomen.
   * <p>{@code false}: legacy gedrag — Java skipt de flush
   * ({@link ExperienceCollector#flush()} logt {@code RL_FLUSH_SKIP_CHAMPION})
   * en de Python trainer dropt {@code role=1} rijen aan ingestie-zijde.
   *
   * <p>Filter is een PPO-erfenis (PPO is strikt on-policy); SAC is off-policy
   * en kan principieel leren van transities van een andere policy mits de
   * rewards transitie-getrouw zijn (wat geldt — {@code RewardComputer}
   * berekent rewards uit events/features, niet uit de behavior policy).
   */
  public boolean isChampionExperienceEnabled() {
    return championExperienceEnabled;
  }

  public boolean isDeterministicInference() {
    return deterministicInference;
  }

  /**
   * Gaussiaanse exploratie-ruis (pre-tanh std) op de continue moveDir_sin/cos tijdens
   * experience-collectie (alleen wanneer !deterministic_inference). Zonder dit is de looprichting
   * deterministisch → geen sector-variatie in de buffer → de SAC-critic kan Q(s, andere-sector) niet
   * leren en de movement-policy zit vast. 0 = uit.
   */
  public double getMovementExplorationStd() {
    return movementExplorationStd;
  }

  public double getMovementExplorationEdgeDropThreshold() {
    return movementExplorationEdgeDropThreshold;
  }

  public double getMovementExplorationEdgeScale() {
    return movementExplorationEdgeScale;
  }

  public int getReplayBufferMaxTransitions() {
    return replayBufferMaxTransitions;
  }

  public int getFlushEveryTransitions() {
    return flushEveryTransitions;
  }

  public int getExperienceRecordInterval() {
    return experienceRecordInterval;
  }

  // ---- JSON helpers (strict — no silent defaults) ----

  private static int requireInt(JsonNode parent, String field, String section) {
    JsonNode n = parent.path(field);
    if (!n.isNumber()) {
      throw new IllegalStateException(
          section + ".json: missing required integer field '" + field + "'");
    }
    return n.asInt();
  }

  private static boolean requireBoolean(JsonNode parent, String field, String section) {
    JsonNode n = parent.path(field);
    if (!n.isBoolean()) {
      throw new IllegalStateException(
          section + ".json: missing required boolean field '" + field + "'");
    }
    return n.asBoolean();
  }

  private static double requireDouble(JsonNode parent, String field, String section) {
    JsonNode n = parent.path(field);
    if (!n.isNumber()) {
      throw new IllegalStateException(
          section + ".json: missing required number field '" + field + "'");
    }
    return n.asDouble();
  }
}
