package aiplay.rl.rewards.combat.combatevent;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.LeadAimUtils;
import aiplay.config.ModelConfigRepository;
import aiplay.dto.CollisionsDto;
import aiplay.dto.PlayerDto;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparse combat rewards: frag, death, fire penalties, shot accuracy, enemy killed by fire.
 *
 * <p>Shot-aim rewards (on/off target) zijn symmetrisch geëvalueerd voor zowel primary fire
 * ({@code fireActive}) als secondary fire ({@code altFireActive}): zonder die symmetrie ontsnapt
 * alt fire aan de off-target penalty en wordt grenade-spam de optimale strategie.
 * Een shot kan alleen on-target zijn wanneer het gekozen lead-target zichtbaar is en de voorwaartse
 * collision-rays niet direct een muur melden; geometrisch aimen op een enemy achter een muur telt
 * dus als off-target.
 */
public class CombatEventReward implements RewardComponent {

  private static final double UNSAFE_SHOT_COLLISION_THRESHOLD_NORM = 0.08;

  private static final Map<String, FireActionIndices> FIRE_ACTION_INDEX_CACHE =
      new ConcurrentHashMap<>();

  private final CombatEventParams params;

  public CombatEventReward(CombatEventParams params) {
    if (params == null) {
      throw new IllegalArgumentException("CombatEventReward requires non-null CombatEventParams");
    }
    this.params = params;
  }

  public record Result(
      double frag,
      double death,
      double firePenalty,
      double fireCooldownPenalty,
      double shotOnTarget,
      double shotOffTarget,
      double shotOnTargetAlt,
      double shotOffTargetAlt,
      double missedOpportunity,
      double enemyKilledByFire) {

    public double total() {
      return frag
          + death
          + firePenalty
          + fireCooldownPenalty
          + shotOnTarget
          + shotOffTarget
          + shotOnTargetAlt
          + shotOffTargetAlt
          + missedOpportunity
          + enemyKilledByFire;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    PlayerDto prevPawn = ctx.prev().playerPawn;
    PlayerDto currPawn = ctx.curr().playerPawn;

    // Frag-detectie via eigen frags-counter (gevuld door RLCTFGame.ScoreKill →
    // RLUdpStateSender.RecordKill, suicides geëxcludeerd). Was eerder
    // PRI.Score-delta + !hasFlag clause om caps eruit te filteren; die heuristiek
    // breekt zodra een score-mutator (bv. SmartCTF) returns/covers/seals/assists
    // bonusssen aan PRI.Score toevoegt. Eigen counter is monotoon, mutator-immune.
    double frag =
        (currPawn.frags > prevPawn.frags) ? params.frag() : 0.0;

    double death = (currPawn.deaths > prevPawn.deaths) ? params.death() : 0.0;

    boolean primaryOnset = !prevPawn.fireActive && currPawn.fireActive;
    boolean altOnset = !prevPawn.altFireActive && currPawn.altFireActive;

    // fire_penalty: per fire-edge (één penalty per shot, ongeacht mode). Bij beide
    // tegelijk (zou via decoder mutex niet gebeuren) worden ze opgeteld.
    double firePenaltyPerShot = params.firePenalty();
    double firePenalty = 0.0;
    if (firePenaltyPerShot != 0.0) {
      if (primaryOnset) {
        firePenalty += firePenaltyPerShot;
      }
      if (altOnset) {
        firePenalty += firePenaltyPerShot;
      }
    }

    double fireCooldownPenalty =
        (params.fireDuringCooldownPenalty() != 0.0 && currPawn.fireWantedDuringCooldown)
            ? params.fireDuringCooldownPenalty()
            : 0.0;

    // Shot on/off-target — primary + secondary fire-edge. Aim_score wordt apart
    // berekend per mode want de projectielsnelheden verschillen substantieel
    // (flak chunks 2700 UU/s, slug 1200 UU/s) → andere lead-correctie en dus
    // andere on-target classificatie. Pre-fix gebruikte één gedeelde score met
    // primary speed voor beide modes — alt aim_score systematisch overschat,
    // bot ontwikkelde alt-spam-bias door false-positive on-target rewards.
    double shotOnTarget = 0.0;
    double shotOffTarget = 0.0;
    double shotOnTargetAlt = 0.0;
    double shotOffTargetAlt = 0.0;
    boolean profileAllowsShotAim =
        !params.shotOnTargetInstagibOnly() || isInstagibFrame(currPawn);
    boolean shotAimEnabled =
        params.shotOnTargetBonusPrimary() != 0.0
            || params.shotOnTargetBonusAlt() != 0.0
            || params.shotOffTargetPenaltyPrimary() != 0.0
            || params.shotOffTargetPenaltyAlt() != 0.0
            || params.missedOpportunityPenalty() != 0.0
            || params.shotPerfectionBonus() != 0.0;
    if (profileAllowsShotAim && shotAimEnabled) {
      if (primaryOnset) {
        double primaryAim = hasSafeShotLine(ctx) ? computeAimScore(ctx, false) : 0.0;
        ShotResult sr = evaluateShotAim(
            primaryAim,
            params.shotOnTargetBonusPrimary(),
            params.shotOffTargetPenaltyPrimary());
        shotOnTarget = sr.onTarget;
        shotOffTarget = sr.offTarget;
      }
      if (altOnset) {
        double altAim = hasSafeShotLine(ctx) ? computeAimScore(ctx, true) : 0.0;
        ShotResult sr = evaluateShotAim(
            altAim,
            params.shotOnTargetBonusAlt(),
            params.shotOffTargetPenaltyAlt());
        shotOnTargetAlt = sr.onTarget;
        shotOffTargetAlt = sr.offTarget;
      }
    }

    double missedOpportunity = 0.0;
    if (profileAllowsShotAim && params.missedOpportunityPenalty() != 0.0
        && hasFireAction(ctx)
        && hasVisibleLeadTarget(ctx)
        && currPawn.weaponCanFire
        && !policyWantsFire(ctx)) {
      double primaryAim = computeAimScore(ctx, false);
      double altAim = computeAimScore(ctx, true);
      double aimScore = Math.max(primaryAim, altAim);
      if (aimScore >= params.shotMinAimScore()) {
        double exponent = params.shotPrecisionExponent();
        double shapedScore = (exponent == 1.0) ? aimScore : Math.pow(aimScore, exponent);
        missedOpportunity = params.missedOpportunityPenalty() * shapedScore;
      }
    }

    // Kill detection is NOT gated on fireActive: projectile weapons can kill
    // many frames after the fire button was released (flight time). The caller
    // (PerModelExperienceRecorder) is responsible for attributing this reward
    // to the actual fire-onset frame via retroactive credit assignment.
    // Detectie via eigen frags-counter (zie comment bij `frag` hierboven).
    double enemyKilledByFire =
        (params.enemyKilledByFireReward() != 0.0
                && currPawn.frags > prevPawn.frags)
            ? params.enemyKilledByFireReward()
            : 0.0;

    return new Result(
        frag,
        death,
        firePenalty,
        fireCooldownPenalty,
        shotOnTarget,
        shotOffTarget,
        shotOnTargetAlt,
        shotOffTargetAlt,
        missedOpportunity,
        enemyKilledByFire);
  }

  private record ShotResult(double onTarget, double offTarget) {}

  private ShotResult evaluateShotAim(
      double aimScore, double onTargetBonus, double offTargetPenalty) {
    if (aimScore >= params.shotMinAimScore()) {
      double exponent = params.shotPrecisionExponent();
      double shapedScore = (exponent == 1.0) ? aimScore : Math.pow(aimScore, exponent);
      double on = onTargetBonus * shapedScore;
      double perfectionBonus = params.shotPerfectionBonus();
      if (perfectionBonus != 0.0 && aimScore >= params.shotPerfectionThreshold()) {
        on += perfectionBonus;
      }
      return new ShotResult(on, 0.0);
    }
    return new ShotResult(0.0, offTargetPenalty);
  }

  /** Detecteer instagib via weapon class — voorkomt shot_on_target_bonus bij flak. */
  private static boolean isInstagibFrame(PlayerDto pawn) {
    return pawn != null && "Botpack.SuperShockRifle".equals(pawn.weaponClass);
  }

  private double computeAimScore(RewardContext ctx, boolean altFire) {
    return LeadAimUtils.computeAimScore3D(
        ctx.curr(), ctx.modelTargetIndex(),
        ctx.config().getProjectileSpeedFlakPrimaryUu(),
        ctx.config().getProjectileSpeedFlakSecondaryUu(),
        ctx.config().getRocketPrimaryAimTargetHeightUu(),
        ctx.config().getSniperPrimaryAimTargetHeightUu(),
        altFire);
  }

  private boolean policyWantsFire(RewardContext ctx) {
    float[] action = ctx.action();
    if (action == null) {
      return false;
    }
    FireActionIndices indices = fireActionIndices(ctx);
    return (indices.fire >= 0 && indices.fire < action.length && action[indices.fire] > 0.0f)
        || (indices.altFire >= 0 && indices.altFire < action.length && action[indices.altFire] > 0.0f);
  }

  private boolean hasFireAction(RewardContext ctx) {
    float[] action = ctx.action();
    if (action == null) {
      return false;
    }
    FireActionIndices indices = fireActionIndices(ctx);
    return (indices.fire >= 0 && indices.fire < action.length)
        || (indices.altFire >= 0 && indices.altFire < action.length);
  }

  private static FireActionIndices fireActionIndices(RewardContext ctx) {
    String modelKey = ctx.config().getModelKey();
    return FIRE_ACTION_INDEX_CACHE.computeIfAbsent(modelKey, CombatEventReward::resolveFireActionIndices);
  }

  private static FireActionIndices resolveFireActionIndices(String modelKey) {
    List<String> targetFeatures = ModelConfigRepository.shared().get(modelKey).features().targetFeatures();
    return new FireActionIndices(targetFeatures.indexOf("bFire"), targetFeatures.indexOf("bAltFire"));
  }

  private record FireActionIndices(int fire, int altFire) {}

  private boolean hasVisibleLeadTarget(RewardContext ctx) {
    PlayerDto target = LeadAimUtils.resolveLeadEnemy(ctx.curr(), ctx.modelTargetIndex());
    return target != null && target.enemyVisible;
  }

  private boolean hasSafeShotLine(RewardContext ctx) {
    if (!hasVisibleLeadTarget(ctx)) {
      return false;
    }
    PlayerDto self = ctx.curr().playerPawn;
    if (self == null) {
      return false;
    }
    CollisionsDto collisions = self.collisions;
    if (collisions == null) {
      return true;
    }
    return collisions.fwdCollision_norm >= UNSAFE_SHOT_COLLISION_THRESHOLD_NORM
        && collisions.fwdRight30Collision_norm >= UNSAFE_SHOT_COLLISION_THRESHOLD_NORM
        && collisions.fwdLeft30Collision_norm >= UNSAFE_SHOT_COLLISION_THRESHOLD_NORM;
  }
}
