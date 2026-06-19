package aiplay.rl.rewards.movement.flakavoidance;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerEnrichmentDto;
import aiplay.dto.ProjectileRelationDto;

/**
 * Threat-aware shaping op inbound flak-projectielen (chunks + grenades). Zie
 * {@link FlakAvoidanceParams} voor de wiskundige decompositie.
 *
 * <p>Stateless: prev en curr threat-metric worden beide gederiveerd uit de respectievelijke
 * {@code enemyProjectileRels} enrichment-arrays in {@code ctx.prev()} / {@code ctx.curr()}.
 * Identity-matching tussen ticks is niet nodig — het delta-signaal werkt op de geaggregeerde
 * {@code max} threat over alle inbound flak, geclamped per tick om spawn/disappear-spikes te
 * dempen.
 */
public class FlakAvoidanceReward implements RewardComponent {

  private final FlakAvoidanceParams params;

  public FlakAvoidanceReward(FlakAvoidanceParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "FlakAvoidanceReward requires non-null FlakAvoidanceParams");
    }
    this.params = params;
  }

  /**
   * Per-tick uitsplitsing voor breakdown logging.
   *
   * @param instant continue safety-shaping op huidige miss-margin
   * @param delta event-achtige shaping op threat-afname
   */
  public record Result(double instant, double delta) {
    public double total() {
      return instant + delta;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    if (!params.enabled()) {
      return new Result(0.0, 0.0);
    }
    Aggregate curr = aggregate(ctx.curr());
    if (curr == null) {
      return new Result(0.0, 0.0);
    }

    double instant = params.instantWeight() * curr.safetySum;

    double delta = 0.0;
    if (params.deltaWeight() != 0.0) {
      Aggregate prev = aggregate(ctx.prev());
      if (prev != null) {
        double rawDelta = curr.maxThreat - prev.maxThreat;
        double clamp = params.deltaClampPerTick();
        if (clamp > 0.0) {
          rawDelta = Math.max(-clamp, Math.min(clamp, rawDelta));
        }
        // Threat afname (rawDelta < 0) → positieve reward.
        delta = -params.deltaWeight() * rawDelta;
      }
    }

    return new Result(instant, delta);
  }

  /**
   * Aggregeer alle inbound flak-projectielen op één frame: {@code safetySum} (instant
   * shaping basis) + {@code maxThreat} (delta shaping basis).
   */
  private Aggregate aggregate(GameStateDto state) {
    if (state == null) {
      return null;
    }
    PlayerDto self = state.playerPawn;
    if (self == null) {
      return null;
    }
    PlayerEnrichmentDto enr = self.enrichments;
    if (enr == null || enr.enemyProjectileRels == null) {
      return new Aggregate(0.0, 0.0);
    }

    double safetySum = 0.0;
    double maxThreat = 0.0;
    double urgencyDenom = Math.max(1e-6, params.urgencyThresholdNorm());
    for (ProjectileRelationDto[] perEnemy : enr.enemyProjectileRels) {
      if (perEnemy == null) continue;
      for (ProjectileRelationDto rel : perEnemy) {
        if (rel == null || rel.present < 0.5f) continue;
        if (rel.isChunk < 0.5f && rel.isGrenade < 0.5f) continue;

        double tti = rel.timeToImpact_norm;
        double urgency = Math.max(0.0, 1.0 - tti / urgencyDenom);
        if (urgency <= 0.0) continue;

        double cad = Math.max(0.0, Math.min(1.0, rel.closestApproachDistance_norm));
        safetySum += cad * urgency;
        double threat = (1.0 - cad) * urgency;
        if (threat > maxThreat) maxThreat = threat;
      }
    }
    return new Aggregate(safetySum, maxThreat);
  }

  private record Aggregate(double safetySum, double maxThreat) {}
}
