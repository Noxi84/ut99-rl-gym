package aiplay.rl.rewards.movement.movementaction;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Action-conditional rewards (vereisen het action-vector in {@code RewardContext}):
 *
 * <ul>
 *   <li>{@code collisionPenalty}: straf richting-specifiek zodra een directionele collision-norm
 *       onder {@code collisionNearThresholdNorm} zakt en de action in die richting wijst.</li>
 *   <li>{@code floorDropPenalty}: straf wanneer de gekozen locomotion-primitive over een gevaarlijke
 *       drop heen wijst (drop-diepte in uu boven {@code floorDropDangerThresholdUu}). De floor-
 *       elevation is signed; alleen de drop-zijde (negatief) telt, step-ups niet.</li>
 *   <li>{@code stuckPenalty}: straf wanneer de bot < {@code stuckDistanceThresholdUnits} bewogen is
 *       in een tick maar wel een non-idle action-intent emit, én de action-richting blocked is door
 *       collision.</li>
 *   <li>{@code dodgeInitiate}: bonus op de transitie {@code dodgeState NONE → directional}
 *       (LEFT/RIGHT/FORWARD/BACK). Server-validated event — vuurt alleen wanneer UT99 de
 *       dodge daadwerkelijk start. Densere signaal dan {@code dodge} (ACTIVE→DONE) want
 *       NONE→directional gebeurt bij ELKE succesvol gestarte dodge.</li>
 *   <li>{@code dodge}: bonus op de transitie {@code dodgeState ACTIVE → DONE}. Vuurt aan het
 *       einde van een voltooide dodge — confirmatie-signaal.</li>
 *   <li>{@code idleUrgencyPenalty}: per-tick straf wanneer de policy IDLE kiest terwijl de
 *       carrier-/return-rol vereist dat er bewogen wordt (bot draagt enemy flag, of own flag is
 *       dropped en we hebben recover-priority). Ander idle-gedrag (defensief wachten, item-camp,
 *       aiming pause) blijft onaangetast.</li>
 *   <li>{@code exposedIdlePenalty}: per-tick straf wanneer bot horizontaal stilstaat én een
 *       visible enemy zich binnen {@code exposedIdleEnemyDistanceThresholdNorm} bevindt.
 *       Doelt op "bot blijft stilstaan voor een enemy die hem ziet" — easy-target-gedrag.
 *       Stilstand-detectie: {@code |velocityX_norm| + |velocityY_norm| < exposedIdleVelocityThresholdNorm}
 *       (norm-units, want PlayerDto velocity is genormaliseerd op 1000 UU/s).
 *       Onafhankelijk van actie-intent: ook bewegen-tegen-muur telt als stilstand.</li>
 *   <li>{@code firstVisitBonus}: eenmalige novelty-bonus bij het ALLEREERSTE bezoek van een
 *       3D-voxel ({@code firstVisitVoxelUu}) binnen de levensduur van het bot-proces. Anders dan
 *       de cooldown-gebaseerde {@code explorationBonus} (2D, komt terug na
 *       {@code explorationCooldownTicks}) dooft deze definitief uit: onbekend gebied is strikt
 *       waardevoller dan bekend gebied — count-based exploratie-druk voor grote/nieuwe maps
 *       (tempel-corridors, verdiepingen). Geen reset bij dood/match-restart (suicide levert dus
 *       niets op). Telt mee in het {@code EXPLORATION} breakdown-signaal.</li>
 * </ul>
 */
public record MovementActionParams(
    RewardMetadata metadata,
    double collisionPenalty,
    double floorDropPenalty,
    double floorDropDangerThresholdUu,
    double stuckPenalty,
    double dodgeInitiate,
    double dodge,
    double collisionNearThresholdNorm,
    double stuckDistanceThresholdUnits,
    double idleUrgencyPenalty,
    double exposedIdlePenalty,
    double exposedIdleVelocityThresholdNorm,
    double exposedIdleEnemyDistanceThresholdNorm,
    double areaStuckPenalty,
    int areaStuckWindowTicks,
    double areaStuckRadiusUu,
    double explorationBonus,
    double explorationCellSizeUu,
    int explorationCooldownTicks,
    double firstVisitBonus,
    double firstVisitVoxelUu,
    double voidAvoidanceScale,
    double voidAvoidanceFullDropUu,
    double voidAvoidanceClampPerTick,
    double voidAvoidanceZJumpThresholdUu,
    double explorationGroundedFloorBelowMaxUu,
    double dodgeToEdgePenalty)
    implements RewardBlock {

  public MovementActionParams {
    if (metadata == null) {
      throw new IllegalArgumentException("MovementActionParams.metadata required");
    }
    if (exposedIdleVelocityThresholdNorm < 0.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.exposedIdleVelocityThresholdNorm must be >= 0, was "
              + exposedIdleVelocityThresholdNorm);
    }
    if (exposedIdleEnemyDistanceThresholdNorm < 0.0
        || exposedIdleEnemyDistanceThresholdNorm > 1.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.exposedIdleEnemyDistanceThresholdNorm must be in [0,1], was "
              + exposedIdleEnemyDistanceThresholdNorm);
    }
    if (areaStuckWindowTicks < 0) {
      throw new IllegalArgumentException(
          "MovementActionParams.areaStuckWindowTicks must be >= 0, was " + areaStuckWindowTicks);
    }
    if (areaStuckRadiusUu < 0.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.areaStuckRadiusUu must be >= 0, was " + areaStuckRadiusUu);
    }
    if (explorationCellSizeUu <= 0.0 && explorationBonus != 0.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.explorationCellSizeUu must be > 0 when explorationBonus != 0, was "
              + explorationCellSizeUu);
    }
    if (explorationCooldownTicks < 0) {
      throw new IllegalArgumentException(
          "MovementActionParams.explorationCooldownTicks must be >= 0, was "
              + explorationCooldownTicks);
    }
    if (firstVisitVoxelUu <= 0.0 && firstVisitBonus != 0.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.firstVisitVoxelUu must be > 0 when firstVisitBonus != 0, was "
              + firstVisitVoxelUu);
    }
    if ((explorationBonus != 0.0 || firstVisitBonus != 0.0)
        && explorationGroundedFloorBelowMaxUu <= 0.0) {
      throw new IllegalArgumentException(
          "MovementActionParams.explorationGroundedFloorBelowMaxUu must be > 0 when exploration is "
              + "active, was " + explorationGroundedFloorBelowMaxUu);
    }
    if (voidAvoidanceScale != 0.0) {
      if (voidAvoidanceFullDropUu <= floorDropDangerThresholdUu) {
        throw new IllegalArgumentException(
            "MovementActionParams.voidAvoidanceFullDropUu (" + voidAvoidanceFullDropUu
                + ") must be > floorDropDangerThresholdUu (" + floorDropDangerThresholdUu
                + ") when voidAvoidanceScale != 0");
      }
      if (voidAvoidanceClampPerTick < 0.0) {
        throw new IllegalArgumentException(
            "MovementActionParams.voidAvoidanceClampPerTick must be >= 0, was "
                + voidAvoidanceClampPerTick);
      }
      if (voidAvoidanceZJumpThresholdUu <= 0.0) {
        throw new IllegalArgumentException(
            "MovementActionParams.voidAvoidanceZJumpThresholdUu must be > 0, was "
                + voidAvoidanceZJumpThresholdUu);
      }
    }
  }

  @Override
  public boolean enabled() {
    return collisionPenalty != 0.0 || floorDropPenalty != 0.0 || stuckPenalty != 0.0
        || dodgeInitiate != 0.0 || dodge != 0.0
        || idleUrgencyPenalty != 0.0 || exposedIdlePenalty != 0.0
        || areaStuckPenalty != 0.0
        || explorationBonus != 0.0
        || firstVisitBonus != 0.0
        || voidAvoidanceScale != 0.0
        || dodgeToEdgePenalty != 0.0;
  }
}
