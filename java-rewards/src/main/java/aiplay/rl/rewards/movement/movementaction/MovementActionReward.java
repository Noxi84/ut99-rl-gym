package aiplay.rl.rewards.movement.movementaction;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CollisionsDto;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.rl.MovementPrimitive;
import aiplay.scanners.feature.CanonicalPerspectiveNormalizer;
import java.util.HashMap;

/**
 * Action-based rewards: collision penalty, stuck penalty, dodge reward. Only active when an action
 * vector is provided.
 */
public class MovementActionReward implements RewardComponent {

  private final MovementActionParams params;
  /** Index of the {@code bIdle} target in the action vector, or {@code -1} when the
   *  model has no idle output (legacy 5-target spec). Lets the reward mirror the
   *  executor's post-hysteresis idle decision so collision/stuck/floor-drop don't
   *  fire on a frame the bot deliberately stood still. */
  private final int idleActionIndex;

  // Area-stuck detection: ring buffer tracking position N ticks ago.
  private final double[] posXBuffer;
  private final double[] posYBuffer;
  private int bufferIndex;
  private boolean bufferFull;

  // Exploration bonus: tracks last visit tick per grid cell.
  private final HashMap<Long, Integer> cellLastVisit;
  private int explorationTick;

  /** First-visit novelty: 3D-voxels die deze bot ooit bezocht (proces-levensduur, geen reset —
   *  suicide/match-restart levert geen verse bonussen op). Null wanneer de bonus uit staat. */
  private final java.util.HashSet<Long> firstVisitVoxels;

  public MovementActionReward(MovementActionParams params, int idleActionIndex) {
    if (params == null) {
      throw new IllegalArgumentException(
          "MovementActionReward requires non-null MovementActionParams");
    }
    this.params = params;
    this.idleActionIndex = idleActionIndex;

    int windowTicks = params.areaStuckWindowTicks();
    if (windowTicks > 0 && params.areaStuckPenalty() != 0.0) {
      this.posXBuffer = new double[windowTicks];
      this.posYBuffer = new double[windowTicks];
    } else {
      this.posXBuffer = null;
      this.posYBuffer = null;
    }

    this.cellLastVisit = (params.explorationBonus() != 0.0) ? new HashMap<>() : null;
    this.firstVisitVoxels = (params.firstVisitBonus() != 0.0) ? new java.util.HashSet<>() : null;
  }

  public record Result(
      double collision, double stuck, double areaStuck, double exploration,
      double dodge, double idleUrgency, double exposedIdle, double voidAvoidance) {
    public double total() {
      return collision + stuck + areaStuck + exploration + dodge + idleUrgency + exposedIdle
          + voidAvoidance;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    if (ctx.action() == null || ctx.prev().playerPawn == null || ctx.curr().playerPawn == null) {
      return new Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    MovementPrimitive primitive = locomotionFromAction(ctx);
    double collision =
        computeDirectionalCollisionPenalty(ctx, primitive)
            + computeFloorDropPenalty(ctx, primitive);
    double stuck = computeStuckActionPenalty(ctx, primitive);
    double areaStuck = computeAreaStuckPenalty(ctx);
    double exploration = computeExplorationBonus(ctx);

    double dodge = 0.0;
    // Initiate bonus: NONE → directional (LEFT/RIGHT/FORWARD/BACK). Vuurt bij ELKE
    // server-accepted dodge start — dense signaal dat de policy aanmoedigt om dodge
    // te attempten zonder te wachten op de ACTIVE→DONE confirmatie. Voorkomt
    // exploration deadlock op een rare actie waarvan de "succes"-reward pas later
    // beschikbaar is.
    DodgeState prevDodge = ctx.prev().playerPawn.dodgeState;
    DodgeState currDodge = ctx.curr().playerPawn.dodgeState;
    if (params.dodgeInitiate() != 0.0
        && prevDodge == DodgeState.NONE
        && currDodge != null
        && currDodge != DodgeState.NONE
        && currDodge != DodgeState.ACTIVE
        && currDodge != DodgeState.DONE) {
      dodge += params.dodgeInitiate();
    }
    // Success bonus: ACTIVE → DONE (voltooide dodge).
    if (prevDodge == DodgeState.ACTIVE && currDodge == DodgeState.DONE) {
      dodge += params.dodge();
    }
    // Dodge-naar-rand penalty (2026-06-15): een dodge START richting een fall-grade rand → straf.
    // Root cause CTF-Face vals = combat-geinduceerd (data: fall-windows 2,5× damageTaken, r=0,55): de
    // bot dodge't tijdens shock-fights van smalle bruggen af. Immediate pre-fall signaal — de bot
    // perceiveert de floor-delta's al — dat void_fall's vertraagde terminale straf aanvult. De
    // dodge-richting is yaw-relatief en matcht direct de yaw-relatieve floor-delta-fan. GEEN
    // proximity-freeze: vuurt enkel op het MOMENT van een dodge-naar-een-rand, niet op nabijheid.
    if (params.dodgeToEdgePenalty() != 0.0
        && prevDodge == DodgeState.NONE
        && currDodge != null && currDodge != DodgeState.NONE
        && currDodge != DodgeState.ACTIVE && currDodge != DodgeState.DONE) {
      var c = ctx.curr().playerPawn.collisions;
      if (c != null) {
        double drop = switch (currDodge) {
          case FORWARD -> -c.fwdFloorDelta;
          case BACK -> -c.backFloorDelta;
          case LEFT -> -c.leftFloorDelta;
          case RIGHT -> -c.rightFloorDelta;
          default -> 0.0;
        };
        if (drop > params.floorDropDangerThresholdUu()) {
          dodge += params.dodgeToEdgePenalty();
        }
      }
    }

    double idleUrgency = computeIdleUrgencyPenalty(ctx, primitive);
    double exposedIdle = computeExposedIdlePenalty(ctx);
    double voidAvoidance = computeVoidAvoidancePbrs(ctx);

    return new Result(
        collision, stuck, areaStuck, exploration, dodge, idleUrgency, exposedIdle, voidAvoidance);
  }

  /**
   * Per-tick penalty wanneer bot horizontaal stilstaat én een visible enemy in duel-range.
   * Onafhankelijk van actie-intent (ook tegen-een-muur-lopen telt als stilstand). Doelt
   * op "easy target"-gedrag in actieve gevechten.
   */
  private double computeExposedIdlePenalty(RewardContext ctx) {
    double penalty = params.exposedIdlePenalty();
    if (penalty == 0.0) {
      return 0.0;
    }
    PlayerDto self = ctx.curr().playerPawn;
    double speedNorm = Math.hypot(self.velocityX_norm, self.velocityY_norm);
    if (speedNorm >= params.exposedIdleVelocityThresholdNorm()) {
      return 0.0;
    }
    PlayerDto[] enemies = ctx.curr().enemies;
    if (enemies == null) {
      return 0.0;
    }
    double distThreshold = params.exposedIdleEnemyDistanceThresholdNorm();
    if (distThreshold <= 0.0) {
      return 0.0;
    }
    CoordinatesDto selfLoc = self.location;
    if (selfLoc == null) {
      return 0.0;
    }
    for (PlayerDto enemy : enemies) {
      if (enemy == null || enemy.location == null) continue;
      if (enemy.health <= 0) continue;
      if (!enemy.enemyVisible) continue;
      double dx = enemy.location.x - selfLoc.x;
      double dy = enemy.location.y - selfLoc.y;
      double dz = enemy.location.z - selfLoc.z;
      double distNorm =
          aiplay.util.NormalizationUtils.normalizeDistance3D(Math.sqrt(dx * dx + dy * dy + dz * dz));
      if (distNorm <= distThreshold) {
        return penalty;
      }
    }
    return 0.0;
  }

  /**
   * Per-tick penalty wanneer de policy IDLE kiest terwijl de tactische rol bewegen verplicht:
   * carrier (bot draagt enemy flag) of recover (own flag dropped, priority 1/2). Andere idle-
   * contexten (defensief wachten, item-camp, aiming pause) krijgen geen straf.
   *
   * <p>Doel: SAC krijgt een gradient om idle te onderdrukken in mission-critical states,
   * terwijl bewuste rust in andere fases blijft toegelaten.</p>
   */
  private double computeIdleUrgencyPenalty(RewardContext ctx, MovementPrimitive primitive) {
    double penalty = params.idleUrgencyPenalty();
    if (penalty == 0.0 || !primitive.isIdle()) {
      return 0.0;
    }
    GameStateDto state = ctx.curr();
    if (state.playerPawn == null) {
      return 0.0;
    }
    boolean carrier = state.playerPawn.hasFlag;
    boolean ownFlagReturn = RewardUtils.isOwnFlagReturnPriority(state);
    if (!carrier && !ownFlagReturn) {
      return 0.0;
    }
    return penalty;
  }

  private double computeDirectionalCollisionPenalty(RewardContext ctx, MovementPrimitive primitive) {
    double penaltyScale = params.collisionPenalty();
    if (penaltyScale == 0.0) {
      return 0.0;
    }

    CollisionsDto collisions = ctx.curr().playerPawn.collisions;
    if (collisions == null) {
      return 0.0;
    }

    double threshold = Math.max(1e-6, params.collisionNearThresholdNorm());
    double penalty = 0.0;
    if (primitive.isForwardIntent()) {
      penalty += directionalPenalty(collisions.fwdCollision_norm, threshold, penaltyScale);
    }
    if (primitive.isBackIntent()) {
      penalty += directionalPenalty(collisions.backCollision_norm, threshold, penaltyScale);
    }
    if (primitive.isLeftIntent()) {
      penalty += directionalPenalty(collisions.leftCollision_norm, threshold, penaltyScale);
    }
    if (primitive.isRightIntent()) {
      penalty += directionalPenalty(collisions.rightCollision_norm, threshold, penaltyScale);
    }
    return penalty;
  }

  private double computeFloorDropPenalty(RewardContext ctx, MovementPrimitive primitive) {
    double penaltyScale = params.floorDropPenalty();
    if (penaltyScale == 0.0 || primitive.isIdle()) {
      return 0.0;
    }

    CollisionsDto collisions = ctx.curr().playerPawn.collisions;
    if (collisions == null) {
      return 0.0;
    }

    // Signed floor-elevation per sector (raw uu): negative = drop, positive = step-up. Only
    // the drop magnitude is a fall hazard; step-ups (jumpable thresholds) carry no penalty.
    double deltaUu = switch (primitive.getLocomotionComponent()) {
      case FORWARD -> collisions.fwdFloorDelta;
      case FORWARD_RIGHT -> collisions.fwdRightFloorDelta;
      case STRAFE_RIGHT -> collisions.rightFloorDelta;
      case BACK_RIGHT -> collisions.backRightFloorDelta;
      case BACK -> collisions.backFloorDelta;
      case BACK_LEFT -> collisions.backLeftFloorDelta;
      case STRAFE_LEFT -> collisions.leftFloorDelta;
      case FORWARD_LEFT -> collisions.fwdLeftFloorDelta;
      default -> 0;
    };

    double dropUu = Math.max(0.0, -deltaUu);
    double thresholdUu = Math.max(0.0, params.floorDropDangerThresholdUu());
    if (dropUu <= thresholdUu) {
      return 0.0;
    }
    // Ramp from threshold to 2× threshold (full penalty), so deeper drops hurt more.
    double danger = Math.min(1.0, (dropUu - thresholdUu) / Math.max(1.0, thresholdUu));
    return penaltyScale * danger;
  }

  /**
   * PBRS edge-exposure potential — anticipatieve val-vermijding (lateraal én vooruit).
   *
   * <p>De 8 floor-delta-sectoren (probe ~160uu) meten of er in elke richting een val-grade drop is.
   * {@code exposure(s)} = fractie sectoren met een drop &gt; {@code floorDropDangerThresholdUu}
   * (lineair tot {@code voidAvoidanceFullDropUu}). De potentiaal is {@code Φ(s) = -scale·exposure(s)};
   * de reward is de PBRS-delta {@code F = Φ(curr) − Φ(prev)} (γ=1, conform de quasi-PBRS
   * {@code objective_progress}). Naderen van een afgrond (exposure↑) straft VÓÓR de val; terugkeren
   * naar veilige vloer (exposure↓) beloont. Anders dan {@code floor_drop_penalty} — die snapt de
   * movement-heading naar één van 8 sectoren en leest enkel díe sector, waardoor een
   * 'mostly-forward, slightly-toward-rail'-heading de zijwaartse drift mist — dekt dit ALLE
   * richtingen. Symmetrisch (PBRS) ⇒ geen permanente freeze. Map-onafhankelijk: op void-vrije maps
   * is exposure overal 0 ⇒ {@code F=0} (nul effect op bestaande getrainde maps/wapens).
   *
   * <p>Respawn-guard: {@code Φ} telescopeert niet over een dood→respawn-discontinuïteit. Een respawn
   * uit de void geeft een enorme {@code +Δz}; een combat-death-respawn verhoogt {@code health}. In
   * beide gevallen levert de delta nul (anders zou dood+respawn netto belonen).
   */
  private double computeVoidAvoidancePbrs(RewardContext ctx) {
    double scale = params.voidAvoidanceScale();
    if (scale == 0.0) {
      return 0.0;
    }
    PlayerDto prev = ctx.prev().playerPawn;
    PlayerDto curr = ctx.curr().playerPawn;
    CollisionsDto prevC = prev.collisions;
    CollisionsDto currC = curr.collisions;
    if (prevC == null || currC == null || prev.location == null || curr.location == null) {
      return 0.0;
    }
    if (Math.abs(curr.location.z - prev.location.z) > params.voidAvoidanceZJumpThresholdUu()) {
      return 0.0; // respawn/teleport uit de void (Φ telescopeert niet)
    }
    if (curr.health > prev.health) {
      return 0.0; // combat-death-respawn of health-pickup
    }
    double phiPrev = -scale * edgeExposure(prevC);
    double phiCurr = -scale * edgeExposure(currC);
    double f = phiCurr - phiPrev;
    double clamp = params.voidAvoidanceClampPerTick();
    return Math.max(-clamp, Math.min(clamp, f));
  }

  /** Fractie [0,1] van de 8 floor-delta-sectoren met een val-grade drop binnen de probe. */
  private double edgeExposure(CollisionsDto c) {
    double threshold = params.floorDropDangerThresholdUu();
    double full = params.voidAvoidanceFullDropUu();
    double sum =
        sectorVoidness(c.fwdFloorDelta, threshold, full)
            + sectorVoidness(c.fwdRightFloorDelta, threshold, full)
            + sectorVoidness(c.rightFloorDelta, threshold, full)
            + sectorVoidness(c.backRightFloorDelta, threshold, full)
            + sectorVoidness(c.backFloorDelta, threshold, full)
            + sectorVoidness(c.backLeftFloorDelta, threshold, full)
            + sectorVoidness(c.leftFloorDelta, threshold, full)
            + sectorVoidness(c.fwdLeftFloorDelta, threshold, full);
    return sum / 8.0;
  }

  /** Voidness van één sector: 0 bij drop ≤ threshold (step/loopbaar), lineair tot 1 bij full drop. */
  private static double sectorVoidness(double floorDelta, double threshold, double full) {
    double drop = -floorDelta; // negatief floorDelta = drop; positief = step-up (geen hazard)
    if (drop <= threshold) {
      return 0.0;
    }
    double span = full - threshold;
    if (span <= 0.0) {
      return 1.0;
    }
    double v = (drop - threshold) / span;
    return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
  }

  private double computeExplorationBonus(RewardContext ctx) {
    CoordinatesDto loc = ctx.curr().playerPawn.location;
    if (loc == null) {
      return 0.0;
    }
    // Grounded-gate: tel GEEN exploration wanneer de bot mid-air boven een drop/void hangt.
    // Anders farmt vallen de first_visit-novelty: een val passeert tientallen nooit-bezochte
    // void-voxels (3D-packing) → +novelty per voxel, een perverse prikkel die void_avoidance in
    // DEZELFDE movement-head tegenwerkt (gemeten 2026-06-13: val-windows exploration 6.70 vs 4.61
    // zonder val). floorBelow: 0=grounded, satureert (~2048) zodra de voeten over de void hangen,
    // dus de gate vangt de val onmiddellijk bij de edge-step-off; normale sprongen (floorBelow laag)
    // blijven tellen. Map-onafhankelijk/structureel: elke void/lava/klif-map gedekt.
    CollisionsDto collisions = ctx.curr().playerPawn.collisions;
    if (collisions != null
        && collisions.floorBelow > params.explorationGroundedFloorBelowMaxUu()) {
      return 0.0;
    }
    return computeCooldownExplorationBonus(loc) + computeFirstVisitBonus(loc);
  }

  /** Cooldown-variant (2D): bonus wanneer een cel langer dan de cooldown niet bezocht is.
   *  Werkt als "blijf in beweging"-prior; komt periodiek terug op bekende cellen. */
  private double computeCooldownExplorationBonus(CoordinatesDto loc) {
    if (cellLastVisit == null) {
      return 0.0;
    }
    double cellSize = params.explorationCellSizeUu();
    int cx = (int) Math.floor(loc.x / cellSize);
    int cy = (int) Math.floor(loc.y / cellSize);
    long cellKey = ((long) cx << 32) | (cy & 0xFFFFFFFFL);

    explorationTick++;
    Integer lastVisit = cellLastVisit.get(cellKey);
    cellLastVisit.put(cellKey, explorationTick);

    if (lastVisit == null || (explorationTick - lastVisit) > params.explorationCooldownTicks()) {
      return params.explorationBonus();
    }
    return 0.0;
  }

  /** First-visit novelty (3D, dooft definitief uit): eenmalige bonus per nooit eerder bezocht
   *  voxel. 3D omdat verticaliteit (toren-verdiepingen, corridors omhoog) anders op het
   *  grondvlak projecteert en geen novelty meer oplevert. Zelfde voxel-packing als het
   *  geodesische veld ({@link aiplay.runtime.geo.GeodesicField#packVoxel}). */
  private double computeFirstVisitBonus(CoordinatesDto loc) {
    if (firstVisitVoxels == null) {
      return 0.0;
    }
    double voxel = params.firstVisitVoxelUu();
    long key = aiplay.runtime.geo.GeodesicField.packVoxel(
        (int) Math.floor(loc.x / voxel),
        (int) Math.floor(loc.y / voxel),
        (int) Math.floor(loc.z / voxel));
    return firstVisitVoxels.add(key) ? params.firstVisitBonus() : 0.0;
  }

  private double computeAreaStuckPenalty(RewardContext ctx) {
    if (posXBuffer == null) {
      return 0.0;
    }
    CoordinatesDto currLoc = ctx.curr().playerPawn.location;
    if (currLoc == null) {
      return 0.0;
    }

    double penalty = 0.0;
    if (bufferFull) {
      int oldIndex = bufferIndex;
      double dist = Math.hypot(currLoc.x - posXBuffer[oldIndex], currLoc.y - posYBuffer[oldIndex]);
      if (dist < params.areaStuckRadiusUu()) {
        penalty = params.areaStuckPenalty();
      }
    }

    posXBuffer[bufferIndex] = currLoc.x;
    posYBuffer[bufferIndex] = currLoc.y;
    bufferIndex = (bufferIndex + 1) % posXBuffer.length;
    if (bufferIndex == 0 && !bufferFull) {
      bufferFull = true;
    }

    return penalty;
  }

  private double computeStuckActionPenalty(RewardContext ctx, MovementPrimitive primitive) {
    double stuckPenalty = params.stuckPenalty();
    if (stuckPenalty == 0.0 || primitive.isIdle()) {
      return 0.0;
    }

    CoordinatesDto prevLoc = ctx.prev().playerPawn.location;
    CoordinatesDto currLoc = ctx.curr().playerPawn.location;
    if (prevLoc == null || currLoc == null) {
      return 0.0;
    }

    double moved2d = Math.hypot(currLoc.x - prevLoc.x, currLoc.y - prevLoc.y);
    if (moved2d > params.stuckDistanceThresholdUnits()) {
      return 0.0;
    }

    CollisionsDto collisions = ctx.curr().playerPawn.collisions;
    if (collisions == null) {
      return 0.0;
    }

    double threshold = Math.max(1e-6, params.collisionNearThresholdNorm());
    boolean blockedForward =
        primitive.isForwardIntent() && collisions.fwdCollision_norm < threshold;
    boolean blockedBack = primitive.isBackIntent() && collisions.backCollision_norm < threshold;
    boolean blockedLeft = primitive.isLeftIntent() && collisions.leftCollision_norm < threshold;
    boolean blockedRight = primitive.isRightIntent() && collisions.rightCollision_norm < threshold;
    if (!(blockedForward || blockedBack || blockedLeft || blockedRight)) {
      return 0.0;
    }

    return stuckPenalty;
  }

  private static double directionalPenalty(double collisionNorm, double threshold, double penaltyScale) {
    if (collisionNorm >= threshold) {
      return 0.0;
    }
    double closeness = (threshold - collisionNorm) / threshold;
    return penaltyScale * closeness;
  }

  private MovementPrimitive locomotionFromAction(RewardContext ctx) {
    float[] action = ctx.action();
    if (action == null || action.length == 0) {
      return MovementPrimitive.IDLE;
    }
    // Mirror the executor's post-hysteresis idle decision (MovementActionDecoder writes
    // 1.0 / 0.0 to actions[idleIndex]) so collision/stuck/floor-drop don't fire on frames
    // where the bot deliberately stood still — the model's sin/cos can still point in a
    // direction without that counting as a movement intent.
    if (idleActionIndex >= 0
        && idleActionIndex < action.length
        && action[idleActionIndex] >= 0.5f) {
      return MovementPrimitive.IDLE;
    }
    if (action.length >= 2
        && action.length != MovementPrimitive.COUNT
        && ctx.curr() != null
        && ctx.curr().playerPawn != null) {
      return continuousMoveDirToPrimitive(action, ctx.curr());
    }
    int bestIdx = 0;
    float bestVal = action[0];
    int limit = Math.min(MovementPrimitive.COUNT, action.length);
    for (int i = 1; i < limit; i++) {
      if (action[i] > bestVal) {
        bestVal = action[i];
        bestIdx = i;
      }
    }
    return MovementPrimitive.values()[bestIdx];
  }

  private static MovementPrimitive continuousMoveDirToPrimitive(float[] action, GameStateDto curr) {
    double sin = Math.tanh(action[0]);
    double cos = Math.tanh(action[1]);
    if (Math.hypot(sin, cos) < 0.15) {
      return MovementPrimitive.IDLE;
    }

    double worldAngleRad = Math.atan2(sin, cos);
    if (worldAngleRad < 0) {
      worldAngleRad += 2.0 * Math.PI;
    }
    int worldAngleUt =
        (int) Math.round(worldAngleRad / (2.0 * Math.PI) * 65536.0) & 0xFFFF;
    int viewYawUt =
        (curr.playerPawn.viewRotation != null) ? curr.playerPawn.viewRotation.x : 0;
    if (CanonicalPerspectiveNormalizer.needsNormalization(curr.playerPawn.team)) {
      viewYawUt = (viewYawUt + 32768) & 0xFFFF;
    }
    int relativeUt = ((worldAngleUt - viewYawUt + 32768) & 0xFFFF) - 32768;
    return relativeUtToMovementPrimitive(relativeUt);
  }

  private static MovementPrimitive relativeUtToMovementPrimitive(int relativeUt) {
    int[] centers = {0, -8192, -16384, -24576, 32768, 24576, 16384, 8192};
    MovementPrimitive[] primitives = {
      MovementPrimitive.FORWARD,
      MovementPrimitive.FORWARD_RIGHT,
      MovementPrimitive.STRAFE_RIGHT,
      MovementPrimitive.BACK_RIGHT,
      MovementPrimitive.BACK,
      MovementPrimitive.BACK_LEFT,
      MovementPrimitive.STRAFE_LEFT,
      MovementPrimitive.FORWARD_LEFT
    };
    int bestIdx = 0;
    int bestDist = Integer.MAX_VALUE;
    for (int i = 0; i < centers.length; i++) {
      int dist = Math.abs(((relativeUt - centers[i] + 32768) & 0xFFFF) - 32768);
      if (dist < bestDist) {
        bestDist = dist;
        bestIdx = i;
      }
    }
    return primitives[bestIdx];
  }
}
