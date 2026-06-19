package aiplay.rl;

import static aiplay.rl.rewards.core.RewardSignal.ALIVE_BONUS;
import static aiplay.rl.rewards.core.RewardSignal.AMMO_CONSUMPTION_PENALTY;
import static aiplay.rl.rewards.core.RewardSignal.AREA_STUCK;
import static aiplay.rl.rewards.core.RewardSignal.CARRIER_PROXIMITY;
import static aiplay.rl.rewards.core.RewardSignal.COLLISION;
import static aiplay.rl.rewards.core.RewardSignal.COVER_ESCORT;
import static aiplay.rl.rewards.core.RewardSignal.DAMAGE_DEALT;
import static aiplay.rl.rewards.core.RewardSignal.DAMAGE_TAKEN;
import static aiplay.rl.rewards.core.RewardSignal.DEATH;
import static aiplay.rl.rewards.core.RewardSignal.DEFENDER_PRESENCE;
import static aiplay.rl.rewards.core.RewardSignal.DODGE;
import static aiplay.rl.rewards.core.RewardSignal.ENEMY_KILLED_BY_FIRE;
import static aiplay.rl.rewards.core.RewardSignal.ENEMY_SPACING;
import static aiplay.rl.rewards.core.RewardSignal.EXPLORATION;
import static aiplay.rl.rewards.core.RewardSignal.EXPOSED_IDLE;
import static aiplay.rl.rewards.core.RewardSignal.FACING;
import static aiplay.rl.rewards.core.RewardSignal.FIRE_COOLDOWN_PENALTY;
import static aiplay.rl.rewards.core.RewardSignal.FIRE_HOLDING_PENALTY;
import static aiplay.rl.rewards.core.RewardSignal.FIRE_HOLDING_PENALTY_ALT;
import static aiplay.rl.rewards.core.RewardSignal.FIRE_PENALTY;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_CAPTURED;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_CARRIER_KILL;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_CARRIER_KILL_NEAR_BASE;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_DROPPED;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_ENEMY_CAPTURED;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_RETURNED;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_TAKEN;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_TEAM_CAPTURED;
import static aiplay.rl.rewards.core.RewardSignal.FLAG_TEAM_RETURNED;
import static aiplay.rl.rewards.core.RewardSignal.FLAK_AVOIDANCE_DELTA;
import static aiplay.rl.rewards.core.RewardSignal.FLAK_AVOIDANCE_INSTANT;
import static aiplay.rl.rewards.core.RewardSignal.FRAG;
import static aiplay.rl.rewards.core.RewardSignal.FRIENDLY_FIRE;
import static aiplay.rl.rewards.core.RewardSignal.HEADSHOT;
import static aiplay.rl.rewards.core.RewardSignal.IDLE_URGENCY;
import static aiplay.rl.rewards.core.RewardSignal.OBJECTIVE_PROGRESS;
import static aiplay.rl.rewards.core.RewardSignal.PITCH_ALIGNMENT;
import static aiplay.rl.rewards.core.RewardSignal.PICKUP_EVENT;
import static aiplay.rl.rewards.core.RewardSignal.PRIMARY_FIRE_AIM;
import static aiplay.rl.rewards.core.RewardSignal.PROJECTILE_AIM;
import static aiplay.rl.rewards.core.RewardSignal.SCORE_GAIN_RATE;
import static aiplay.rl.rewards.core.RewardSignal.SELF_DAMAGE;
import static aiplay.rl.rewards.core.RewardSignal.SHOCK_COMBO_AIM;
import static aiplay.rl.rewards.core.RewardSignal.SHOCK_COMBO_CLICK;
import static aiplay.rl.rewards.core.RewardSignal.SHOCK_COMBO_CURRICULUM_SHAPING;
import static aiplay.rl.rewards.core.RewardSignal.SHOCK_COMBO_EVENT;
import static aiplay.rl.rewards.core.RewardSignal.SHOT_OFF_TARGET;
import static aiplay.rl.rewards.core.RewardSignal.SHOT_OFF_TARGET_ALT;
import static aiplay.rl.rewards.core.RewardSignal.SHOT_ON_TARGET;
import static aiplay.rl.rewards.core.RewardSignal.SHOT_ON_TARGET_ALT;
import static aiplay.rl.rewards.core.RewardSignal.SPAWN_ATTENTION;
import static aiplay.rl.rewards.core.RewardSignal.SPEED;
import static aiplay.rl.rewards.core.RewardSignal.STUCK;
import static aiplay.rl.rewards.core.RewardSignal.TEAM_ASSIST;
import static aiplay.rl.rewards.core.RewardSignal.VIEW_ALIGNMENT;
import static aiplay.rl.rewards.core.RewardSignal.VIEW_ALIGNMENT_ACQUISITION;
import static aiplay.rl.rewards.core.RewardSignal.VIEW_SMOOTHNESS;
import static aiplay.rl.rewards.core.RewardSignal.VOID_AVOIDANCE;

import aiplay.rl.rewards.core.RewardBreakdown;
import aiplay.rl.rewards.core.RewardSignal;
import java.util.logging.Logger;

/**
 * Joint-mode decomp voor het {@code rl_pawn} model: routeert elk
 * {@link RewardSignal} naar de zes {@link RewardChannel}-critic-heads (movement /
 * view / pitch / fire / altFire / team_assist) plus een residual, met de
 * per-component {@link JointRewardWeights}-multiplier uit het {@code weights}-blok
 * van {@code rewards.json}.
 *
 * <p>De routing is data: één {@link #ROUTING}-tabel (signaal → kanaal + gewicht-
 * sleutel + factor) i.p.v. 54 hardcoded optellingen. Een nieuw signaal voegt
 * één regel toe; een vergeten signaal crasht via de volledigheidsguard in
 * {@link #buildRouting()} i.p.v. stil uit de scalar te vallen. De enige
 * niet-triviale route is {@code VIEW_SMOOTHNESS}: 50/50 over view + pitch, omdat
 * {@link aiplay.rl.rewards.aim.viewsmoothness.ViewSmoothnessReward} de penalty
 * bouwt als {@code -w × (yawNorm + pitchNorm)} — gelijke gewichten geven gelijke
 * attributie.</p>
 *
 * <table>
 *   <caption>Routing per signaal → kanaal (gewicht-sleutel)</caption>
 *   <tr><th>Kanaal</th><th>Signalen</th><th>Gewicht</th></tr>
 *   <tr><td>movement</td><td>flag* (7)</td><td>flag_event_weight</td></tr>
 *   <tr><td>movement</td><td>pickupEvent</td><td>pickup_event_weight</td></tr>
 *   <tr><td>movement</td><td>aliveBonus, objectiveProgress, speed, facing, enemySpacing, collision, stuck, areaStuck, exploration, dodge, idleUrgency, exposedIdle, scoreGainRate, flakAvoidance*, defenderPresence, coverEscort, carrierProximity</td><td>1.0 (ongewogen)</td></tr>
 *   <tr><td>view</td><td>viewAlignment, viewAlignmentAcquisition, spawnAttention</td><td>view_alignment_weight</td></tr>
 *   <tr><td>view + pitch (½/½)</td><td>viewSmoothness</td><td>view_smoothness_weight</td></tr>
 *   <tr><td>pitch</td><td>pitchAlignment</td><td>pitch_alignment_weight</td></tr>
 *   <tr><td>fire</td><td>shotOn/Off, enemyKilledByFire, primaryFireAim, projectileAim, shockComboEvent</td><td>shot_event_weight</td></tr>
 *   <tr><td>fire</td><td>frag, flagCarrierKill, flagCarrierKillNearBase</td><td>frag_weight</td></tr>
 *   <tr><td>fire</td><td>fireHoldingPenalty, firePenalty, fireCooldownPenalty</td><td>combat_event_weight</td></tr>
 *   <tr><td>fire</td><td>damageDealt, selfDamage, friendlyFire, headshot</td><td>damage_delta_weight</td></tr>
 *   <tr><td>fire</td><td>ammoConsumptionPenalty</td><td>ammo_consumption_weight</td></tr>
 *   <tr><td>altFire</td><td>shotOnTargetAlt, shotOffTargetAlt</td><td>shot_event_weight</td></tr>
 *   <tr><td>altFire</td><td>fireHoldingPenaltyAlt</td><td>combat_event_weight</td></tr>
 *   <tr><td>team_assist</td><td>teamAssist</td><td>1.0 (ongewogen)</td></tr>
 *   <tr><td>residual</td><td>death</td><td>combat_event_weight</td></tr>
 *   <tr><td>residual</td><td>damageTaken</td><td>damage_delta_weight</td></tr>
 * </table>
 *
 * <p><b>Invariant:</b> {@code scalar = movement + view + pitch + fire + altFire + team_assist + residual}.
 *
 * <p><b>Mode-ambiguïteit waarschuwingen:</b> reward-events die GEEN mode-tag
 * dragen (damageDealt, selfDamage, ammoConsumption, firePenalty / fireCooldown,
 * enemyKilledByFire, flagCarrierKill) worden default naar {@code reward_fire}
 * geattribueerd; de eerste niet-nul instantie per logger-instantie genereert een
 * waarschuwingsline {@code RL_JOINT_DECOMP_AMBIGUOUS}.</p>
 */
public final class JointRewardDecompositionStrategy {

  private static final Logger LOG = Logger.getLogger(JointRewardDecompositionStrategy.class.getName());

  /** Welke {@link JointRewardWeights}-multiplier op een route. {@code NONE} = ongewogen (×1.0). */
  private enum JointWeightKey {
    VIEW_ALIGNMENT,
    PITCH_ALIGNMENT,
    VIEW_SMOOTHNESS,
    SHOT_EVENT,
    DAMAGE_DELTA,
    AMMO_CONSUMPTION,
    COMBAT_EVENT,
    FRAG,
    FLAG_EVENT,
    PICKUP_EVENT,
    NONE;

    double of(JointRewardWeights w) {
      return switch (this) {
        case VIEW_ALIGNMENT -> w.viewAlignment;
        case PITCH_ALIGNMENT -> w.pitchAlignment;
        case VIEW_SMOOTHNESS -> w.viewSmoothness;
        case SHOT_EVENT -> w.shotEvent;
        case DAMAGE_DELTA -> w.damageDelta;
        case AMMO_CONSUMPTION -> w.ammoConsumption;
        case COMBAT_EVENT -> w.combatEvent;
        case FRAG -> w.frag;
        case FLAG_EVENT -> w.flagEvent;
        case PICKUP_EVENT -> w.pickupEvent;
        case NONE -> 1.0;
      };
    }
  }

  /** Eén routering: {@code channelSum[channel] += weightKey.of(weights) × factor × signalValue}. */
  private record Route(RewardChannel channel, JointWeightKey weightKey, double factor) {}

  /** Routing-tabel geïndexeerd op {@link RewardSignal#ordinal()}. */
  private static final Route[][] ROUTING = buildRouting();

  private static Route[][] buildRouting() {
    Route[][] t = new Route[RewardSignal.COUNT][];

    assign(t, RewardChannel.MOVEMENT, JointWeightKey.FLAG_EVENT,
        FLAG_TAKEN, FLAG_DROPPED, FLAG_CAPTURED, FLAG_TEAM_CAPTURED, FLAG_ENEMY_CAPTURED,
        FLAG_RETURNED, FLAG_TEAM_RETURNED);
    assign(t, RewardChannel.MOVEMENT, JointWeightKey.PICKUP_EVENT, PICKUP_EVENT);
    assign(t, RewardChannel.MOVEMENT, JointWeightKey.NONE,
        ALIVE_BONUS, OBJECTIVE_PROGRESS, SPEED, FACING, ENEMY_SPACING,
        COLLISION, STUCK, AREA_STUCK, EXPLORATION, DODGE, IDLE_URGENCY, EXPOSED_IDLE,
        VOID_AVOIDANCE,
        SCORE_GAIN_RATE, FLAK_AVOIDANCE_INSTANT, FLAK_AVOIDANCE_DELTA,
        DEFENDER_PRESENCE, COVER_ESCORT, CARRIER_PROXIMITY);

    assign(t, RewardChannel.VIEW, JointWeightKey.VIEW_ALIGNMENT,
        VIEW_ALIGNMENT, VIEW_ALIGNMENT_ACQUISITION, SPAWN_ATTENTION);

    assign(t, RewardChannel.PITCH, JointWeightKey.PITCH_ALIGNMENT, PITCH_ALIGNMENT);

    assign(t, RewardChannel.FIRE, JointWeightKey.SHOT_EVENT,
        SHOT_ON_TARGET, SHOT_OFF_TARGET, ENEMY_KILLED_BY_FIRE,
        PRIMARY_FIRE_AIM, PROJECTILE_AIM, SHOCK_COMBO_EVENT, SHOCK_COMBO_AIM,
        SHOCK_COMBO_CURRICULUM_SHAPING, SHOCK_COMBO_CLICK);
    assign(t, RewardChannel.FIRE, JointWeightKey.FRAG,
        FRAG, FLAG_CARRIER_KILL, FLAG_CARRIER_KILL_NEAR_BASE);
    assign(t, RewardChannel.FIRE, JointWeightKey.COMBAT_EVENT,
        FIRE_HOLDING_PENALTY, FIRE_PENALTY, FIRE_COOLDOWN_PENALTY);
    assign(t, RewardChannel.FIRE, JointWeightKey.DAMAGE_DELTA,
        DAMAGE_DEALT, SELF_DAMAGE, FRIENDLY_FIRE, HEADSHOT);
    assign(t, RewardChannel.FIRE, JointWeightKey.AMMO_CONSUMPTION, AMMO_CONSUMPTION_PENALTY);

    assign(t, RewardChannel.ALT_FIRE, JointWeightKey.SHOT_EVENT,
        SHOT_ON_TARGET_ALT, SHOT_OFF_TARGET_ALT);
    assign(t, RewardChannel.ALT_FIRE, JointWeightKey.COMBAT_EVENT, FIRE_HOLDING_PENALTY_ALT);

    assign(t, RewardChannel.TEAM_ASSIST, JointWeightKey.NONE, TEAM_ASSIST);

    assign(t, RewardChannel.RESIDUAL, JointWeightKey.COMBAT_EVENT, DEATH);
    assign(t, RewardChannel.RESIDUAL, JointWeightKey.DAMAGE_DELTA, DAMAGE_TAKEN);

    // viewSmoothness draagt 50/50 bij aan view + pitch (yaw- en pitch-deel van de
    // smoothness-penalty). Enige signaal met meerdere routes.
    t[VIEW_SMOOTHNESS.ordinal()] = new Route[] {
        new Route(RewardChannel.VIEW, JointWeightKey.VIEW_SMOOTHNESS, 0.5),
        new Route(RewardChannel.PITCH, JointWeightKey.VIEW_SMOOTHNESS, 0.5)
    };

    // Volledigheidsguard: elk signaal moet een routing hebben, anders valt het
    // stil uit de scalar (trainingsbug). Crash bij class-load, net als RewardModules.
    for (RewardSignal s : RewardSignal.values()) {
      if (t[s.ordinal()] == null || t[s.ordinal()].length == 0) {
        throw new IllegalStateException(
            "JointRewardDecompositionStrategy: RewardSignal " + s
                + " heeft geen kanaal-routing — voeg toe aan buildRouting().");
      }
    }
    return t;
  }

  private static void assign(Route[][] t, RewardChannel channel, JointWeightKey weightKey,
      RewardSignal... signals) {
    for (RewardSignal s : signals) {
      if (t[s.ordinal()] != null) {
        throw new IllegalStateException("Dubbele routing voor RewardSignal " + s);
      }
      t[s.ordinal()] = new Route[] {new Route(channel, weightKey, 1.0)};
    }
  }

  private final JointRewardWeights weights;
  /** Eenmalige waarschuwing zodra een mode-ambiguous reward niet-nul observeert. */
  private boolean warnedAmbiguousFire = false;
  /** Eenmalige waarschuwing zodra friendly-fire optreedt en naar fire wordt gemapped. */
  private boolean warnedFriendlyFire = false;

  public JointRewardDecompositionStrategy(JointRewardWeights weights) {
    if (weights == null) {
      throw new IllegalArgumentException(
          "JointRewardDecompositionStrategy requires non-null JointRewardWeights");
    }
    this.weights = weights;
  }

  public RewardDecomposition decompose(RewardBreakdown bd) {
    double[] channelSum = new double[RewardChannel.COUNT];
    for (RewardSignal sig : RewardSignal.values()) {
      double raw = bd.value(sig);
      for (Route r : ROUTING[sig.ordinal()]) {
        channelSum[r.channel().ordinal()] += r.weightKey().of(weights) * r.factor() * raw;
      }
    }

    warnOnAmbiguousAttribution(bd);

    double rewardMovement = channelSum[RewardChannel.MOVEMENT.ordinal()];
    double rewardView = channelSum[RewardChannel.VIEW.ordinal()];
    double rewardPitch = channelSum[RewardChannel.PITCH.ordinal()];
    double rewardFire = channelSum[RewardChannel.FIRE.ordinal()];
    double rewardAltFire = channelSum[RewardChannel.ALT_FIRE.ordinal()];
    double rewardTeamAssist = channelSum[RewardChannel.TEAM_ASSIST.ordinal()];
    double residual = channelSum[RewardChannel.RESIDUAL.ordinal()];

    double scalar = rewardMovement + rewardView + rewardPitch + rewardFire + rewardAltFire
        + rewardTeamAssist + residual;
    return new RewardDecomposition(rewardMovement, rewardView, rewardPitch, rewardFire, rewardAltFire,
        rewardTeamAssist, residual, scalar);
  }

  /** Eenmalige diagnostiek voor mode-ambiguous events die default naar reward_fire gaan. */
  private void warnOnAmbiguousAttribution(RewardBreakdown bd) {
    if (!warnedAmbiguousFire
        && (bd.value(DAMAGE_DEALT) != 0.0
            || bd.value(SELF_DAMAGE) != 0.0
            || bd.value(AMMO_CONSUMPTION_PENALTY) != 0.0
            || bd.value(FIRE_PENALTY) != 0.0
            || bd.value(FIRE_COOLDOWN_PENALTY) != 0.0
            || bd.value(ENEMY_KILLED_BY_FIRE) != 0.0
            || bd.value(FLAG_CARRIER_KILL) != 0.0
            || bd.value(FLAG_CARRIER_KILL_NEAR_BASE) != 0.0)) {
      LOG.info(
          "RL_JOINT_DECOMP_AMBIGUOUS first non-zero mode-ambiguous reward observed (damageDealt/"
              + "selfDamage/ammo/firePenalty/cooldown/killedByFire/carrierKill) — attributed naar reward_fire by default. "
              + "Single-warning per process; see JointRewardDecompositionStrategy javadoc for rationale.");
      warnedAmbiguousFire = true;
    }
    if (!warnedFriendlyFire && bd.value(FRIENDLY_FIRE) != 0.0) {
      LOG.info(
          "RL_JOINT_DECOMP_FRIENDLY_FIRE first non-zero friendlyFire observed — attributed naar reward_fire "
              + "(zelfde mode-default als damageDealt). Single-warning per process.");
      warnedFriendlyFire = true;
    }
  }
}
