package aiplay.rl;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.ActiveMapContext;
import aiplay.rl.rewards.aim.enemyspawnattention.EnemySpawnAttentionReward;
import aiplay.rl.rewards.aim.pitch.PitchReward;
import aiplay.rl.rewards.aim.viewalignment.ViewAlignmentReward;
import aiplay.rl.rewards.aim.viewsmoothness.ViewSmoothnessReward;
import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardCatalog;
import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModules;
import aiplay.rl.rewards.catalog.json.JsonRewardCatalog;
import aiplay.rl.rewards.combat.ammoconsumptionpenalty.AmmoConsumptionPenaltyReward;
import aiplay.rl.rewards.combat.combatevent.CombatEventReward;
import aiplay.rl.rewards.combat.damagedelta.DamageDeltaReward;
import aiplay.rl.rewards.combat.fireholdingpenalty.FireHoldingPenaltyReward;
import aiplay.rl.rewards.combat.primaryfireaim.PrimaryFireAimReward;
import aiplay.rl.rewards.combat.projectileaim.ProjectileAimReward;
import aiplay.rl.rewards.combat.shockcomboaim.ShockComboAimReward;
import aiplay.rl.rewards.combat.shockcomboclick.ShockComboClickReward;
import aiplay.rl.rewards.combat.shockcombocurriculum.ShockComboCurriculumReward;
import aiplay.rl.rewards.combat.shockcomboevent.ShockComboEventReward;
import aiplay.rl.rewards.core.RewardBreakdown;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardSignal;
import aiplay.rl.rewards.core.RewardTuningConfig;
import aiplay.rl.rewards.movement.enemyspacing.EnemySpacingReward;
import aiplay.rl.rewards.movement.facing.FacingReward;
import aiplay.rl.rewards.movement.flakavoidance.FlakAvoidanceReward;
import aiplay.rl.rewards.movement.movementaction.MovementActionReward;
import aiplay.rl.rewards.movement.speed.SpeedReward;
import aiplay.rl.rewards.objective.flagcarrierkill.FlagCarrierKillReward;
import aiplay.rl.rewards.objective.flagevent.FlagEventReward;
import aiplay.rl.rewards.objective.objectiveprogress.ObjectiveProgressReward;
import aiplay.rl.rewards.objective.pickupevent.PickupEventReward;
import aiplay.rl.rewards.objective.scoregainrate.ScoreGainRateReward;
import aiplay.rl.rewards.team.coverescort.CoverEscortReward;
import aiplay.rl.rewards.team.defenderpresence.DefenderPresenceReward;
import aiplay.rl.rewards.team.teamassist.TeamAssistReward;
import java.util.EnumMap;
import java.util.Map;

/**
 * Orchestrates per-tick reward computation by delegating to individual
 * {@link RewardComponent} implementations. Handles null/death edge cases
 * at the top level, then dispatches to components for the normal path.
 *
 * <p>{@link #computeDecomposition(GameStateDto, GameStateDto, float[])}
 * levert ook een per-skill {@link RewardDecomposition} (view / pitch /
 * fire / altFire + residual) via {@link JointRewardDecompositionStrategy}
 * — geen verspreide model-key branches in {@link RewardComponent}
 * implementaties (CLAUDE.md preference).</p>
 */
public class RewardComputer {

  private final RLConfig config;
  /** Reward-tuning parameters (rewards.json) doorgegeven aan elke {@link RewardContext}. */
  private final RewardTuningConfig rewardTuning;
  /**
   * Resolved typed reward-catalog voor deze bot — direct uit {@code rewards.json} geparsed via
   * {@link JsonRewardCatalog}. Iedere {@link aiplay.rl.rewards.core.RewardComponent} ontvangt z'n eigen
   * typed {@code *Params} via constructor-injection.
   */
  private final RewardCatalog catalog;
  /** Joint reward decomp — mapping breakdown → per-skill kanalen. */
  private final JointRewardDecompositionStrategy decompositionStrategy;
  /** Phase 2: sessionId used to read this bot's target_index from
   *  {@link aiplay.shared.shooting.ShootingTargetIndexBus}. May be null in
   *  test contexts; readers fall back to engagement target via LeadAimUtils. */
  private String sessionId;

  // Concrete component references (for breakdown access)
  private final FlagEventReward flagEventReward;
  private final FlagCarrierKillReward flagCarrierKillReward;
  private final CombatEventReward combatEventReward;
  private final ObjectiveProgressReward objectiveProgressReward;
  private final SpeedReward speedReward;
  private final FacingReward facingReward;
  private final ViewAlignmentReward viewAlignmentReward;
  private final PitchReward pitchReward;
  private final EnemySpacingReward enemySpacingReward;
  private final ViewSmoothnessReward viewSmoothnessReward;
  private final MovementActionReward movementActionReward;
  private final DamageDeltaReward damageDeltaReward;
  private final ProjectileAimReward projectileAimReward;
  private final PrimaryFireAimReward primaryFireAimReward;
  private final FireHoldingPenaltyReward fireHoldingPenaltyReward;
  private final AmmoConsumptionPenaltyReward ammoConsumptionPenaltyReward;
  private final EnemySpawnAttentionReward enemySpawnAttentionReward;
  private final ScoreGainRateReward scoreGainRateReward;
  private final FlakAvoidanceReward flakAvoidanceReward;
  private final PickupEventReward pickupEventReward;
  private final ShockComboEventReward shockComboEventReward;
  private final ShockComboAimReward shockComboAimReward;
  private final ShockComboClickReward shockComboClickReward;
  private final ShockComboCurriculumReward shockComboCurriculumReward;
  private final DefenderPresenceReward defenderPresenceReward;
  private final CoverEscortReward coverEscortReward;
  private final TeamAssistReward teamAssistReward;

  // Registry-built components, keyed by id. EnumMap → RewardId-enumvolgorde, identiek aan de
  // vroegere handmatige allComponents-volgorde zodat de reward-sommatie deterministisch blijft.
  // Drijft de simpele compute()-loop; de typed velden hierboven voeden het breakdown-pad.
  private final Map<RewardId, RewardComponent> components;

  public RewardComputer(RLConfig config) {
    this.config = config;
    this.rewardTuning = RewardTuningConfig.fromModel(config.getModelKey());
    this.catalog = JsonRewardCatalog.from(config.getModelKey(), config.getRewardGroupSelector());

    RewardComponentContext componentContext =
        new RewardComponentContext(catalog, config.getModelKey());
    Map<RewardId, RewardComponent> built = new EnumMap<>(RewardId.class);
    for (RewardModule<?> module : RewardModules.all()) {
      built.put(module.id(), module.create(componentContext));
    }
    this.components = built;

    // Typed handles voor het breakdown-pad (computeWithBreakdown roept de component-specifieke
    // computeDetailed()-overloads aan). De casts zijn veilig door het module-id ↔ component-type
    // invariant dat RewardModules bij class-load valideert.
    this.flagEventReward = (FlagEventReward) built.get(RewardId.FLAG_EVENT);
    this.flagCarrierKillReward = (FlagCarrierKillReward) built.get(RewardId.FLAG_CARRIER_KILL);
    this.combatEventReward = (CombatEventReward) built.get(RewardId.COMBAT_EVENT);
    this.objectiveProgressReward =
        (ObjectiveProgressReward) built.get(RewardId.OBJECTIVE_PROGRESS);
    this.speedReward = (SpeedReward) built.get(RewardId.SPEED);
    this.facingReward = (FacingReward) built.get(RewardId.FACING);
    this.viewAlignmentReward = (ViewAlignmentReward) built.get(RewardId.VIEW_ALIGNMENT);
    this.pitchReward = (PitchReward) built.get(RewardId.PITCH);
    this.enemySpacingReward = (EnemySpacingReward) built.get(RewardId.ENEMY_SPACING);
    this.viewSmoothnessReward = (ViewSmoothnessReward) built.get(RewardId.VIEW_SMOOTHNESS);
    this.movementActionReward = (MovementActionReward) built.get(RewardId.MOVEMENT_ACTION);
    this.damageDeltaReward = (DamageDeltaReward) built.get(RewardId.DAMAGE_DELTA);
    this.projectileAimReward = (ProjectileAimReward) built.get(RewardId.PROJECTILE_AIM);
    this.primaryFireAimReward = (PrimaryFireAimReward) built.get(RewardId.PRIMARY_FIRE_AIM);
    this.fireHoldingPenaltyReward =
        (FireHoldingPenaltyReward) built.get(RewardId.FIRE_HOLDING_PENALTY);
    this.ammoConsumptionPenaltyReward =
        (AmmoConsumptionPenaltyReward) built.get(RewardId.AMMO_CONSUMPTION_PENALTY);
    this.enemySpawnAttentionReward =
        (EnemySpawnAttentionReward) built.get(RewardId.ENEMY_SPAWN_ATTENTION);
    this.scoreGainRateReward = (ScoreGainRateReward) built.get(RewardId.SCORE_GAIN_RATE);
    this.flakAvoidanceReward = (FlakAvoidanceReward) built.get(RewardId.FLAK_AVOIDANCE);
    this.pickupEventReward = (PickupEventReward) built.get(RewardId.PICKUP_EVENT);
    this.shockComboEventReward = (ShockComboEventReward) built.get(RewardId.SHOCK_COMBO_EVENT);
    this.shockComboAimReward = (ShockComboAimReward) built.get(RewardId.SHOCK_COMBO_AIM);
    this.shockComboClickReward = (ShockComboClickReward) built.get(RewardId.SHOCK_COMBO_CLICK);
    this.shockComboCurriculumReward =
        (ShockComboCurriculumReward) built.get(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING);
    this.defenderPresenceReward =
        (DefenderPresenceReward) built.get(RewardId.DEFENDER_PRESENCE);
    this.coverEscortReward = (CoverEscortReward) built.get(RewardId.COVER_ESCORT);
    this.teamAssistReward = (TeamAssistReward) built.get(RewardId.TEAM_ASSIST);

    this.decompositionStrategy =
        new JointRewardDecompositionStrategy(JointRewardWeights.forModel(config.getModelKey()));
  }


  /** Phase 2: bind this RewardComputer to a bot's sessionId so reward
   *  components can read the model's target_index from the shared bus. */
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public boolean isBreakdownEnabled() {
    return rewardTuning.isRewardBreakdownEnabled();
  }

  public int getBreakdownWindowSize() {
    return rewardTuning.getRewardBreakdownWindowSize();
  }

  public RLConfig getConfig() {
    return config;
  }

  public RewardCatalog getCatalog() {
    return catalog;
  }

  /**
   * Joint reward computatie: berekent per-skill {@link RewardDecomposition}
   * naast de scalar reward, zodat de NPZ writer reward_view / reward_pitch /
   * reward_fire / reward_altFire arrays kan schrijven naar experience-batches
   * (commitment-3 multi-head critic pre-wiring).
   */
  public RewardDecomposition computeDecomposition(GameStateDto prev, GameStateDto curr,
      float[] action) {
    return computeDecomposition(prev, curr, action, -1);
  }

  public RewardDecomposition computeDecomposition(GameStateDto prev, GameStateDto curr,
      float[] action, int modelTargetIndex) {
    RewardBreakdown bd = computeWithBreakdown(prev, curr, action, modelTargetIndex);
    return decompositionStrategy.decompose(bd);
  }

  public RewardDecomposition decomposeBreakdown(RewardBreakdown bd) {
    return decompositionStrategy.decompose(bd);
  }

  /**
   * Compute reward for the transition from prev to curr state.
   */
  public double compute(GameStateDto prev, GameStateDto curr) {
    if (curr == null) {
      return 0.0;
    }
    if (curr.playerPawn == null) {
      if (prev != null && prev.playerPawn != null && prev.playerPawn.health > 0) {
        return catalog.combatEvent().death();
      }
      return 0.0;
    }
    if (prev == null || prev.playerPawn == null) {
      return catalog.objectiveProgress().aliveBonus();
    }

    RewardContext ctx = new RewardContext(prev, curr, null, rewardTuning,
        aiplay.shared.shooting.ShootingTargetIndexBus.latest(sessionId));
    String mapName = resolveMapName(curr);
    double reward = 0.0;
    for (Map.Entry<RewardId, RewardComponent> entry : components.entrySet()) {
      if (!catalog.metadata(entry.getKey()).isActiveForMap(mapName)) continue;
      reward += entry.getValue().compute(ctx);
    }
    return reward;
  }

  /**
   * Compute reward including action-based components (collision, stuck, dodge).
   */
  public double compute(GameStateDto prev, GameStateDto curr, float[] action) {
    return compute(prev, curr, action, -1);
  }

  public double compute(GameStateDto prev, GameStateDto curr, float[] action, int modelTargetIndex) {
    if (curr == null) {
      return 0.0;
    }
    if (curr.playerPawn == null) {
      if (prev != null && prev.playerPawn != null && prev.playerPawn.health > 0) {
        return catalog.combatEvent().death();
      }
      return 0.0;
    }
    if (prev == null || prev.playerPawn == null) {
      return catalog.objectiveProgress().aliveBonus();
    }

    RewardContext ctx = new RewardContext(prev, curr, action, rewardTuning,
        resolveModelTargetIndex(modelTargetIndex));
    String mapName = resolveMapName(curr);
    double reward = 0.0;
    for (Map.Entry<RewardId, RewardComponent> entry : components.entrySet()) {
      if (!catalog.metadata(entry.getKey()).isActiveForMap(mapName)) continue;
      reward += entry.getValue().compute(ctx);
    }
    return reward;
  }

  /**
   * Compute reward with full per-component breakdown (state-only, no action).
   */
  public RewardBreakdown computeWithBreakdown(GameStateDto prev, GameStateDto curr) {
    return computeWithBreakdown(prev, curr, null);
  }

  /**
   * Compute reward with full per-component breakdown including action components.
   */
  public RewardBreakdown computeWithBreakdown(GameStateDto prev, GameStateDto curr, float[] action) {
    return computeWithBreakdown(prev, curr, action, -1);
  }

  public RewardBreakdown computeWithBreakdown(
      GameStateDto prev, GameStateDto curr, float[] action, int modelTargetIndex) {
    if (curr == null) {
      return RewardBreakdown.zero(0.0);
    }
    if (curr.playerPawn == null) {
      if (prev != null && prev.playerPawn != null && prev.playerPawn.health > 0) {
        double d = catalog.combatEvent().death();
        return RewardBreakdown.zero(d);
      }
      return RewardBreakdown.zero(0.0);
    }
    if (prev == null || prev.playerPawn == null) {
      double alive = catalog.objectiveProgress().aliveBonus();
      return RewardBreakdown.builder().set(RewardSignal.ALIVE_BONUS, alive).build(alive);
    }

    RewardContext ctx = new RewardContext(prev, curr, action, rewardTuning,
        resolveModelTargetIndex(modelTargetIndex));
    String mapName = resolveMapName(curr);

    FlagEventReward.Result flagR = mapActive(catalog.flagEvent(), mapName)
        ? flagEventReward.computeDetailed(ctx)
        : new FlagEventReward.Result(0, 0, 0, 0, 0, 0, 0);
    FlagCarrierKillReward.Result carrierR = mapActive(catalog.flagCarrierKill(), mapName)
        ? flagCarrierKillReward.computeDetailed(ctx)
        : new FlagCarrierKillReward.Result(0, 0);
    CombatEventReward.Result combatR = mapActive(catalog.combatEvent(), mapName)
        ? combatEventReward.computeDetailed(ctx)
        : new CombatEventReward.Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    ObjectiveProgressReward.Result progressR = mapActive(catalog.objectiveProgress(), mapName)
        ? objectiveProgressReward.computeDetailed(ctx)
        : new ObjectiveProgressReward.Result(0, 0, 0);
    double speedR = mapActive(catalog.speed(), mapName)
        ? speedReward.compute(ctx) : 0.0;
    double facingR = mapActive(catalog.facing(), mapName)
        ? facingReward.compute(ctx) : 0.0;
    ViewAlignmentReward.Result viewR = mapActive(catalog.viewAlignment(), mapName)
        ? viewAlignmentReward.computeDetailed(ctx)
        : new ViewAlignmentReward.Result(0, 0);
    double pitchR = mapActive(catalog.pitch(), mapName)
        ? pitchReward.compute(ctx) : 0.0;
    double spacingR = mapActive(catalog.enemySpacing(), mapName)
        ? enemySpacingReward.compute(ctx) : 0.0;
    double smoothR = mapActive(catalog.viewSmoothness(), mapName)
        ? viewSmoothnessReward.compute(ctx) : 0.0;
    MovementActionReward.Result actionR = mapActive(catalog.movementAction(), mapName)
        ? movementActionReward.computeDetailed(ctx)
        : new MovementActionReward.Result(0, 0, 0, 0, 0, 0, 0, 0);
    DamageDeltaReward.Result damageR = mapActive(catalog.damageDelta(), mapName)
        ? damageDeltaReward.computeDetailed(ctx)
        : new DamageDeltaReward.Result(0, 0, 0, 0, 0);
    double projectileAimR = mapActive(catalog.projectileAim(), mapName)
        ? projectileAimReward.compute(ctx) : 0.0;
    double primaryFireAimR = mapActive(catalog.primaryFireAim(), mapName)
        ? primaryFireAimReward.compute(ctx) : 0.0;
    FireHoldingPenaltyReward.Result fireHoldingR = mapActive(catalog.fireHoldingPenalty(), mapName)
        ? fireHoldingPenaltyReward.computeDetailed(ctx)
        : new FireHoldingPenaltyReward.Result(0, 0);
    double ammoConsumptionR = mapActive(catalog.ammoConsumptionPenalty(), mapName)
        ? ammoConsumptionPenaltyReward.compute(ctx) : 0.0;
    double spawnAttentionR = mapActive(catalog.enemySpawnAttention(), mapName)
        ? enemySpawnAttentionReward.compute(ctx) : 0.0;
    double scoreGainRateR = mapActive(catalog.scoreGainRate(), mapName)
        ? scoreGainRateReward.compute(ctx) : 0.0;
    FlakAvoidanceReward.Result flakR = mapActive(catalog.flakAvoidance(), mapName)
        ? flakAvoidanceReward.computeDetailed(ctx)
        : new FlakAvoidanceReward.Result(0, 0);
    PickupEventReward.Result pickupR = mapActive(catalog.pickupEvent(), mapName)
        ? pickupEventReward.computeDetailed(ctx)
        : new PickupEventReward.Result(0, 0, 0, 0, 0, 0, 0, 0, 0);
    ShockComboEventReward.Result shockComboR = mapActive(catalog.shockComboEvent(), mapName)
        ? shockComboEventReward.computeDetailed(ctx)
        : new ShockComboEventReward.Result(0, 0);
    double shockComboAimR = mapActive(catalog.shockComboAim(), mapName)
        ? shockComboAimReward.compute(ctx) : 0.0;
    double shockComboCurriculumR = mapActive(catalog.shockComboCurriculum(), mapName)
        ? shockComboCurriculumReward.compute(ctx) : 0.0;
    double shockComboClickR = mapActive(catalog.shockComboClick(), mapName)
        ? shockComboClickReward.compute(ctx) : 0.0;
    double defenderPresenceR = mapActive(catalog.defenderPresence(), mapName)
        ? defenderPresenceReward.compute(ctx) : 0.0;
    double coverEscortR = mapActive(catalog.coverEscort(), mapName)
        ? coverEscortReward.compute(ctx) : 0.0;
    TeamAssistReward.Result teamAssistR = mapActive(catalog.teamAssist(), mapName)
        ? teamAssistReward.computeDetailed(ctx)
        : new TeamAssistReward.Result(0, 0, 0, 0, 0);

    double total = flagR.total() + carrierR.total() + combatR.total() + progressR.total()
        + speedR + facingR + viewR.total() + pitchR + spacingR + smoothR
        + actionR.total() + damageR.total() + projectileAimR + primaryFireAimR + fireHoldingR.total()
        + ammoConsumptionR
        + spawnAttentionR + scoreGainRateR + flakR.total() + pickupR.total()
        + shockComboR.total() + shockComboAimR + shockComboCurriculumR + shockComboClickR
        + defenderPresenceR + coverEscortR
        + teamAssistR.total();

    return RewardBreakdown.builder()
        .set(RewardSignal.FLAG_TAKEN, flagR.flagTaken())
        .set(RewardSignal.FLAG_DROPPED, flagR.flagDropped())
        .set(RewardSignal.FLAG_CAPTURED, flagR.flagCaptured())
        .set(RewardSignal.FLAG_TEAM_CAPTURED, flagR.flagTeamCaptured())
        .set(RewardSignal.FLAG_ENEMY_CAPTURED, flagR.flagEnemyCaptured())
        .set(RewardSignal.FRAG, combatR.frag())
        .set(RewardSignal.DEATH, combatR.death())
        .set(RewardSignal.FLAG_RETURNED, flagR.flagReturned())
        .set(RewardSignal.FLAG_TEAM_RETURNED, flagR.flagTeamReturned())
        .set(RewardSignal.FIRE_PENALTY, combatR.firePenalty() + combatR.missedOpportunity())
        .set(RewardSignal.FIRE_COOLDOWN_PENALTY, combatR.fireCooldownPenalty())
        .set(RewardSignal.ALIVE_BONUS, progressR.aliveBonus())
        .set(RewardSignal.OBJECTIVE_PROGRESS, progressR.objectiveProgress())
        .set(RewardSignal.SPEED, speedR)
        .set(RewardSignal.FACING, facingR)
        .set(RewardSignal.VIEW_ALIGNMENT, viewR.viewAlignment())
        .set(RewardSignal.VIEW_ALIGNMENT_ACQUISITION, viewR.viewAlignmentAcquisition())
        .set(RewardSignal.PITCH_ALIGNMENT, pitchR)
        .set(RewardSignal.ENEMY_SPACING, spacingR)
        .set(RewardSignal.VIEW_SMOOTHNESS, smoothR)
        .set(RewardSignal.SHOT_ON_TARGET, combatR.shotOnTarget())
        .set(RewardSignal.SHOT_OFF_TARGET, combatR.shotOffTarget())
        .set(RewardSignal.SHOT_ON_TARGET_ALT, combatR.shotOnTargetAlt())
        .set(RewardSignal.SHOT_OFF_TARGET_ALT, combatR.shotOffTargetAlt())
        .set(RewardSignal.ENEMY_KILLED_BY_FIRE, combatR.enemyKilledByFire())
        .set(RewardSignal.FIRE_HOLDING_PENALTY, fireHoldingR.primary())
        .set(RewardSignal.FIRE_HOLDING_PENALTY_ALT, fireHoldingR.alt())
        .set(RewardSignal.AMMO_CONSUMPTION_PENALTY, ammoConsumptionR)
        .set(RewardSignal.FLAG_CARRIER_KILL, carrierR.carrierKill())
        .set(RewardSignal.FLAG_CARRIER_KILL_NEAR_BASE, carrierR.carrierKillNearBase())
        .set(RewardSignal.COLLISION, actionR.collision())
        .set(RewardSignal.STUCK, actionR.stuck())
        .set(RewardSignal.AREA_STUCK, actionR.areaStuck())
        .set(RewardSignal.EXPLORATION, actionR.exploration())
        .set(RewardSignal.DODGE, actionR.dodge())
        .set(RewardSignal.IDLE_URGENCY, actionR.idleUrgency())
        .set(RewardSignal.EXPOSED_IDLE, actionR.exposedIdle())
        .set(RewardSignal.VOID_AVOIDANCE, actionR.voidAvoidance())
        .set(RewardSignal.DAMAGE_DEALT, damageR.damageDealt())
        .set(RewardSignal.DAMAGE_TAKEN, damageR.damageTaken())
        .set(RewardSignal.SELF_DAMAGE, damageR.selfDamage())
        .set(RewardSignal.FRIENDLY_FIRE, damageR.friendlyFire())
        .set(RewardSignal.HEADSHOT, damageR.headshot())
        .set(RewardSignal.PROJECTILE_AIM, projectileAimR)
        .set(RewardSignal.PRIMARY_FIRE_AIM, primaryFireAimR)
        .set(RewardSignal.SPAWN_ATTENTION, spawnAttentionR)
        .set(RewardSignal.SCORE_GAIN_RATE, scoreGainRateR)
        .set(RewardSignal.FLAK_AVOIDANCE_INSTANT, flakR.instant())
        .set(RewardSignal.FLAK_AVOIDANCE_DELTA, flakR.delta())
        .set(RewardSignal.PICKUP_EVENT, pickupR.total())
        .set(RewardSignal.SHOCK_COMBO_EVENT, shockComboR.total())
        .set(RewardSignal.SHOCK_COMBO_AIM, shockComboAimR)
        .set(RewardSignal.SHOCK_COMBO_CURRICULUM_SHAPING, shockComboCurriculumR)
        .set(RewardSignal.SHOCK_COMBO_CLICK, shockComboClickR)
        .set(RewardSignal.DEFENDER_PRESENCE, defenderPresenceR)
        .set(RewardSignal.COVER_ESCORT, coverEscortR)
        .set(RewardSignal.TEAM_ASSIST, teamAssistR.total())
        .set(RewardSignal.CARRIER_PROXIMITY, progressR.carrierProximity())
        .build(total);
  }

  private int resolveModelTargetIndex(int explicitTargetIndex) {
    if (explicitTargetIndex >= 0) {
      return explicitTargetIndex;
    }
    return aiplay.shared.shooting.ShootingTargetIndexBus.latest(sessionId);
  }

  private static String resolveMapName(GameStateDto state) {
    if (state != null && state.mapInfo != null
        && state.mapInfo.mapName != null && !state.mapInfo.mapName.isEmpty()) {
      return state.mapInfo.mapName;
    }
    return ActiveMapContext.normalize(
        GlobalConfigRepository.shared().gameplay().mapName());
  }

  private static boolean mapActive(RewardBlock block, String mapName) {
    return block.metadata().isActiveForMap(mapName);
  }
}
