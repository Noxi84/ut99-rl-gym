package aiplay.rl.rewards.catalog;

import aiplay.rl.rewards.aim.enemyspawnattention.EnemySpawnAttentionParams;
import aiplay.rl.rewards.aim.pitch.PitchParams;
import aiplay.rl.rewards.aim.viewalignment.ViewAlignmentParams;
import aiplay.rl.rewards.aim.viewsmoothness.ViewSmoothnessParams;
import aiplay.rl.rewards.combat.ammoconsumptionpenalty.AmmoConsumptionPenaltyParams;
import aiplay.rl.rewards.combat.combatevent.CombatEventParams;
import aiplay.rl.rewards.combat.damagedelta.DamageDeltaParams;
import aiplay.rl.rewards.combat.fireholdingpenalty.FireHoldingPenaltyParams;
import aiplay.rl.rewards.combat.primaryfireaim.PrimaryFireAimParams;
import aiplay.rl.rewards.combat.projectileaim.ProjectileAimParams;
import aiplay.rl.rewards.combat.shockcomboaim.ShockComboAimParams;
import aiplay.rl.rewards.combat.shockcomboclick.ShockComboClickParams;
import aiplay.rl.rewards.combat.shockcombocurriculum.ShockComboCurriculumParams;
import aiplay.rl.rewards.combat.shockcomboevent.ShockComboEventParams;
import aiplay.rl.rewards.movement.enemyspacing.EnemySpacingParams;
import aiplay.rl.rewards.movement.facing.FacingParams;
import aiplay.rl.rewards.movement.flakavoidance.FlakAvoidanceParams;
import aiplay.rl.rewards.movement.movementaction.MovementActionParams;
import aiplay.rl.rewards.movement.speed.SpeedParams;
import aiplay.rl.rewards.objective.flagcarrierkill.FlagCarrierKillParams;
import aiplay.rl.rewards.objective.flagevent.FlagEventParams;
import aiplay.rl.rewards.objective.objectiveprogress.ObjectiveProgressParams;
import aiplay.rl.rewards.objective.pickupevent.PickupEventParams;
import aiplay.rl.rewards.objective.scoregainrate.ScoreGainRateParams;
import aiplay.rl.rewards.team.coverescort.CoverEscortParams;
import aiplay.rl.rewards.team.defenderpresence.DefenderPresenceParams;
import aiplay.rl.rewards.team.teamassist.TeamAssistParams;
import java.util.stream.Stream;

/**
 * Resolved reward-set voor één bot, één rewardgroup-selectie. Per reward typed access — geen
 * generic god-object-getters meer.
 *
 * <p>Bouwt voort op {@code rewards.json}'s {@code rewardgroups}-merging (selectors + default), maar
 * exposeert het resultaat als typed {@code *Params}-records (één per {@link RewardId}). Iedere
 * {@link aiplay.rl.rewards.core.RewardComponent} ontvangt z'n eigen {@code Params} via constructor-
 * injection — geen lookup meer in {@code ctx.config().getRewardX()}.
 *
 * <p>{@link aiplay.rl.RewardComputer} gebruikt {@link #isEnabled(RewardId)} om alleen rewards te
 * instantiëren waarvan tenminste één weight non-zero is. {@link RewardBreakdown} wordt afgeleid uit
 * {@link RewardMetadata#kind()} per reward — geen hand-getypte sparse/dense lijst meer.
 */
public interface RewardCatalog {

  // --- Per-reward typed accessors ---

  FlagEventParams flagEvent();

  FlagCarrierKillParams flagCarrierKill();

  CombatEventParams combatEvent();

  ObjectiveProgressParams objectiveProgress();

  SpeedParams speed();

  FacingParams facing();

  ViewAlignmentParams viewAlignment();

  PitchParams pitch();

  EnemySpacingParams enemySpacing();

  ViewSmoothnessParams viewSmoothness();

  MovementActionParams movementAction();

  DamageDeltaParams damageDelta();

  ProjectileAimParams projectileAim();

  PrimaryFireAimParams primaryFireAim();

  FireHoldingPenaltyParams fireHoldingPenalty();

  AmmoConsumptionPenaltyParams ammoConsumptionPenalty();

  EnemySpawnAttentionParams enemySpawnAttention();

  ScoreGainRateParams scoreGainRate();

  FlakAvoidanceParams flakAvoidance();

  PickupEventParams pickupEvent();

  ShockComboEventParams shockComboEvent();

  ShockComboAimParams shockComboAim();

  ShockComboClickParams shockComboClick();

  ShockComboCurriculumParams shockComboCurriculum();

  DefenderPresenceParams defenderPresence();

  CoverEscortParams coverEscort();

  TeamAssistParams teamAssist();

  /**
   * Top-level (not per-rewardgroup) endgame ramp parameters. The same shape applies to every role;
   * only the per-role {@code team_assist.endgame_attack_bonus} weight differs between groups.
   */
  EndgameUrgencyParams endgameUrgency();

  // --- Generic metadata access ---

  /** Metadata (id, description, kind, owner) voor een reward — werkt voor alle 16. */
  RewardMetadata metadata(RewardId id);

  /** True wanneer minstens één weight binnen {@code id} non-zero is. */
  boolean isEnabled(RewardId id);

  /** Stream over alle 16 blocks — voor diagnostics, validation, en automatic breakdown. */
  Stream<RewardBlock> allBlocks();
}
