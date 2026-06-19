package aiplay.rl;

import aiplay.config.PropertyReaderUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Top-level per-component multipliers uit {@code rl_pawn/rewards.json}'s
 * {@code "weights"} blok (vr-shooting-sac-merge.md sectie 7.2). Deze gewichten
 * worden door {@link JointRewardDecompositionStrategy} toegepast bij het
 * aggregeren van {@link aiplay.rl.rewards.core.RewardBreakdown}-velden naar
 * skill-kanalen — bovenop de bestaande per-component params binnen elke
 * {@link aiplay.rl.rewards.core.RewardComponent}.
 *
 * <p>Geen fallback waarden — alle gewichten moeten in {@code rewards.json}
 * staan, anders crash bij init (CLAUDE.md preference). Tests construeren
 * {@link JointRewardWeights} via de package-zichtbare constructor zodat geen
 * echte config-bestanden nodig zijn.</p>
 */
public final class JointRewardWeights {

  public final double viewAlignment;
  public final double pitchAlignment;
  public final double viewSmoothness;
  public final double shotEvent;
  public final double damageDelta;
  public final double ammoConsumption;
  public final double combatEvent;
  public final double frag;
  public final double flagEvent;
  public final double pickupEvent;

  /**
   * Package-zichtbare constructor — direct invoeren van gewichten zonder
   * config-lookup. Wordt gebruikt door tests én door
   * {@link #forModel(String)} na het inlezen van {@code rewards.json}.
   */
  JointRewardWeights(
      double viewAlignment,
      double pitchAlignment,
      double viewSmoothness,
      double shotEvent,
      double damageDelta,
      double ammoConsumption,
      double combatEvent,
      double frag,
      double flagEvent,
      double pickupEvent) {
    this.viewAlignment = viewAlignment;
    this.pitchAlignment = pitchAlignment;
    this.viewSmoothness = viewSmoothness;
    this.shotEvent = shotEvent;
    this.damageDelta = damageDelta;
    this.ammoConsumption = ammoConsumption;
    this.combatEvent = combatEvent;
    this.frag = frag;
    this.flagEvent = flagEvent;
    this.pickupEvent = pickupEvent;
  }

  /**
   * Laadt joint-mode gewichten uit {@code /models/<modelKey>/rewards}.
   * Verwacht een {@code "weights"} object met negen verplichte velden. Geen
   * defaults — missing key crasht direct met expliciete context.
   */
  public static JointRewardWeights forModel(String modelKey) {
    JsonNode rewards = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/rewards");
    if (rewards == null || !rewards.isObject()) {
      throw new IllegalStateException(
          "JointRewardWeights: rewards.json missing voor model " + modelKey);
    }
    JsonNode weights = rewards.get("weights");
    if (weights == null || !weights.isObject()) {
      throw new IllegalStateException(
          "JointRewardWeights: rewards.json voor " + modelKey
              + " mist verplicht 'weights' object (joint decomp aggregatie). "
              + "Geen silent default — CLAUDE.md no-fallback preference.");
    }
    return new JointRewardWeights(
        require(weights, modelKey, "view_alignment_weight"),
        require(weights, modelKey, "pitch_alignment_weight"),
        require(weights, modelKey, "view_smoothness_weight"),
        require(weights, modelKey, "shot_event_weight"),
        require(weights, modelKey, "damage_delta_weight"),
        require(weights, modelKey, "ammo_consumption_weight"),
        require(weights, modelKey, "combat_event_weight"),
        require(weights, modelKey, "frag_weight"),
        require(weights, modelKey, "flag_event_weight"),
        require(weights, modelKey, "pickup_event_weight"));
  }

  private static double require(JsonNode weights, String modelKey, String key) {
    JsonNode v = weights.get(key);
    if (v == null || !v.isNumber()) {
      throw new IllegalStateException(
          "JointRewardWeights: rewards.json (" + modelKey
              + ") mist verplicht weights." + key);
    }
    return v.asDouble();
  }
}
