package aiplay.rl.rewards.core;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.logging.Logger;

/**
 * Reward-tuning configuratie voor het joint model: alle per-model parameters uit {@code rewards.json}
 * die de {@link RewardComponent} implementaties (en de {@code RewardComputer} orchestrator) tijdens
 * reward-berekening nodig hebben — match-timing, wapen-projectielsnelheden, het rocket-aimpunt, de
 * pitch-alignment curriculum-multiplier en de reward-breakdown diagnostics.
 *
 * <p>Bewust losgekoppeld van {@code aiplay.rl.RLConfig} (dat alleen nog SAC-algoritme-knobs uit
 * {@code sac.json} houdt) zodat de reward-laag (module {@code java-rewards}) niet terug-afhangt van de
 * RL-infrastructuur in {@code java-aiplay}. Doorgegeven aan elke {@link RewardComponent} via
 * {@link RewardContext#config()}; geladen via {@link #fromModel(String)}.
 *
 * <p>Geen silent defaults — elk veld is vereist in {@code rewards.json}; ontbrekende velden crashen
 * bij constructie (CLAUDE.md no-fallback rule).
 */
public final class RewardTuningConfig {

  private static final Logger LOG = Logger.getLogger(RewardTuningConfig.class.getName());

  private final String modelKey;
  private final double matchDuration;
  private final double projectileSpeedFlakPrimaryUu;
  private final double projectileSpeedFlakSecondaryUu;
  private final double rocketPrimaryAimTargetHeightUu;
  private final double sniperPrimaryAimTargetHeightUu;
  private final String pitchAlignmentCurriculumPhase;
  private final double pitchAlignmentCurriculumMultiplier;
  private final boolean rewardBreakdownEnabled;
  private final int rewardBreakdownWindowSize;

  private RewardTuningConfig(String modelKey, double matchDuration,
      double projectileSpeedFlakPrimaryUu, double projectileSpeedFlakSecondaryUu,
      double rocketPrimaryAimTargetHeightUu, double sniperPrimaryAimTargetHeightUu,
      String pitchAlignmentCurriculumPhase,
      double pitchAlignmentCurriculumMultiplier, boolean rewardBreakdownEnabled,
      int rewardBreakdownWindowSize) {
    this.modelKey = modelKey;
    this.matchDuration = matchDuration;
    this.projectileSpeedFlakPrimaryUu = projectileSpeedFlakPrimaryUu;
    this.projectileSpeedFlakSecondaryUu = projectileSpeedFlakSecondaryUu;
    this.rocketPrimaryAimTargetHeightUu = rocketPrimaryAimTargetHeightUu;
    this.sniperPrimaryAimTargetHeightUu = sniperPrimaryAimTargetHeightUu;
    this.pitchAlignmentCurriculumPhase = pitchAlignmentCurriculumPhase;
    this.pitchAlignmentCurriculumMultiplier = pitchAlignmentCurriculumMultiplier;
    this.rewardBreakdownEnabled = rewardBreakdownEnabled;
    this.rewardBreakdownWindowSize = rewardBreakdownWindowSize;
  }

  /**
   * Laadt de reward-tuning parameters uit {@code resources/models/<modelKey>/rewards.json}.
   * Crasht bij ontbrekende velden (no-fallback).
   */
  public static RewardTuningConfig fromModel(String modelKey) {
    JsonNode rewardsCfg = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/rewards");
    if (rewardsCfg == null) {
      throw new IllegalStateException("No rewards.json found for model: " + modelKey);
    }
    double matchDuration = requireDouble(rewardsCfg, "match_duration");
    double flakPrimary = requireDouble(rewardsCfg, "projectile_speed_flak_primary_uu");
    double flakSecondary = requireDouble(rewardsCfg, "projectile_speed_flak_secondary_uu");
    double rocketHeight = requireDouble(rewardsCfg, "rocket_primary_aim_target_height_uu");
    double sniperHeight = requireDouble(rewardsCfg, "sniper_primary_aim_target_height_uu");
    PitchAlignmentCurriculum curriculum = resolvePitchAlignmentCurriculum(rewardsCfg, modelKey);
    boolean breakdownEnabled = requireBoolean(rewardsCfg, "reward_breakdown_enabled");
    int breakdownWindow = requireInt(rewardsCfg, "reward_breakdown_window_size");
    return new RewardTuningConfig(modelKey, matchDuration, flakPrimary, flakSecondary,
        rocketHeight, sniperHeight, curriculum.phase(), curriculum.multiplier(),
        breakdownEnabled, breakdownWindow);
  }

  public String getModelKey() {
    return modelKey;
  }

  public double getMatchDuration() {
    return matchDuration;
  }

  /**
   * Flak primary projectiel-snelheid (UTChunk MaxSpeed=2700 UU/s in {@code Botpack/UTChunk.uc}).
   * Gebruikt door {@link LeadAimUtils} voor lead-correctie bij primary fire onsets.
   */
  public double getProjectileSpeedFlakPrimaryUu() {
    return projectileSpeedFlakPrimaryUu;
  }

  /**
   * Flak secondary projectiel-snelheid (FlakSlug speed=1200 UU/s in {@code Botpack/flakslug.uc}).
   * Gebruikt door {@link LeadAimUtils} voor lead-correctie bij alt-fire onsets.
   */
  public double getProjectileSpeedFlakSecondaryUu() {
    return projectileSpeedFlakSecondaryUu;
  }

  /**
   * Verticale offset (UU) bovenop {@code enemy.location.z} waar rocket primary (UT_Eightball /
   * RLEightball) op mikt. 0.0 = enemy-foot/origin, ideaal voor splash damage op gelijke hoogte.
   * Gebruikt door {@link aiplay.shared.view.FireModeAimTargeting} en propageert zo naar de
   * {@code primaryAimPitchError} feature, de pitch-rewards en {@code computeAimScore3D}.
   */
  public double getRocketPrimaryAimTargetHeightUu() {
    return rocketPrimaryAimTargetHeightUu;
  }

  /**
   * Verticale offset (UU) bovenop {@code enemy.location.z} waar Sniper primary (Botpack.SniperRifle)
   * op mikt. {@code location.z} is het collision-cylinder-CENTRUM (CollisionHeight=39 voor
   * TournamentPlayer); de head-zone (waar UT99 {@code Decapitated}, 100 HP toepast) begint op
   * {@code +0.62·CollisionHeight ≈ +24 UU}. Default ~31 = head-center (ruim in de decap-zone, onder
   * de cilinder-top +39), zodat de pitch-/view-rewards de aim recht op het hoofd trekken i.p.v. de
   * standaard eye-height (+27, net aan de onderrand). Gebruikt door
   * {@link aiplay.shared.view.FireModeAimTargeting} en propageert zo naar de {@code primaryAimPitchError}
   * feature, de pitch-rewards en {@code computeAimScore3D}. Alleen primary fire; dispatcht op de
   * gedragen wapenclass, dus dead voor elk niet-sniper wapen (die blijven op eye-height).
   */
  public double getSniperPrimaryAimTargetHeightUu() {
    return sniperPrimaryAimTargetHeightUu;
  }

  /**
   * Naam van de actieve pitch_alignment curriculum-fase (warmup / decay1 / decay2 / outcome) zoals
   * geconfigureerd in {@code rewards.json#pitch_alignment_curriculum.active_phase}. Handmatig
   * geswitcht tussen training-cycles — geen auto-progression (CLAUDE.md no-auto-orchestration).
   */
  public String getPitchAlignmentCurriculumPhase() {
    return pitchAlignmentCurriculumPhase;
  }

  /**
   * Multiplier toegepast op {@code weights.pitch_alignment_weight} bij de heuristische
   * alignment-cost in {@link aiplay.rl.rewards.aim.PitchReward}. PBRS-acquisition en extreme-pitch-penalty worden NIET
   * geschaald. Komt uit de fase geselecteerd door {@link #getPitchAlignmentCurriculumPhase()}.
   */
  public double getPitchAlignmentCurriculumMultiplier() {
    return pitchAlignmentCurriculumMultiplier;
  }

  public boolean isRewardBreakdownEnabled() {
    return rewardBreakdownEnabled;
  }

  public int getRewardBreakdownWindowSize() {
    return rewardBreakdownWindowSize;
  }

  private record PitchAlignmentCurriculum(String phase, double multiplier) {}

  private static PitchAlignmentCurriculum resolvePitchAlignmentCurriculum(JsonNode rewardsCfg,
                                                                          String modelKey) {
    JsonNode curriculum = rewardsCfg.path("pitch_alignment_curriculum");
    if (!curriculum.isObject()) {
      throw new IllegalStateException(
          "rewards.json: missing required object 'pitch_alignment_curriculum'");
    }
    JsonNode activePhaseNode = curriculum.path("active_phase");
    if (!activePhaseNode.isTextual() || activePhaseNode.asText().isBlank()) {
      throw new IllegalStateException(
          "rewards.json: pitch_alignment_curriculum.active_phase missing or blank");
    }
    String activePhase = activePhaseNode.asText().trim();
    JsonNode phases = curriculum.path("phases");
    if (!phases.isObject()) {
      throw new IllegalStateException(
          "rewards.json: pitch_alignment_curriculum.phases missing or not an object");
    }
    JsonNode selectedPhase = phases.path(activePhase);
    if (!selectedPhase.isObject()) {
      throw new IllegalStateException(
          "rewards.json: pitch_alignment_curriculum.phases['" + activePhase + "'] missing or not an object");
    }
    double multiplier = requireDouble(selectedPhase, "weight_multiplier");
    LOG.info(String.format(
        "[RewardTuningConfig %s] pitch_alignment_curriculum: active_phase='%s' weight_multiplier=%.3f",
        modelKey, activePhase, multiplier));
    return new PitchAlignmentCurriculum(activePhase, multiplier);
  }

  // ---- JSON helpers (strict — no silent defaults) ----

  private static double requireDouble(JsonNode parent, String field) {
    JsonNode n = parent.path(field);
    if (!n.isNumber()) {
      throw new IllegalStateException("rewards.json: missing required numeric field '" + field + "'");
    }
    return n.asDouble();
  }

  private static int requireInt(JsonNode parent, String field) {
    JsonNode n = parent.path(field);
    if (!n.isNumber()) {
      throw new IllegalStateException("rewards.json: missing required integer field '" + field + "'");
    }
    return n.asInt();
  }

  private static boolean requireBoolean(JsonNode parent, String field) {
    JsonNode n = parent.path(field);
    if (!n.isBoolean()) {
      throw new IllegalStateException("rewards.json: missing required boolean field '" + field + "'");
    }
    return n.asBoolean();
  }
}
