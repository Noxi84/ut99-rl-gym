package aiplay.rl.rewards.combat.primaryfireaim;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.rl.rewards.core.LeadAimUtils;
import aiplay.dto.PlayerDto;

/**
 * Per-tick continuous shaping reward voor primary fire on-target. Symmetrische tegenhanger van
 * {@link ProjectileAimReward} (die continuous gradient geeft voor alt-fire grenades): zonder deze
 * component krijgt primary fire alleen sparse onset-rewards en collapseert het beleid naar alt-fire
 * spam.
 *
 * <p>Actief wanneer {@code fireActive=true}: reward = w · aim_score · close_factor. De
 * {@code close_factor = exp(-dist / sigma_long)} schaalt de reward hoger dichtbij (waar primary het
 * juiste gereedschap is) en dempt op grote afstand (waar alt fire / grenades de betere keuze
 * worden).
 *
 * <p>Een minimum {@code aim_score}-threshold voorkomt farming: aim onder die threshold geeft 0
 * reward (model moet daadwerkelijk op target zitten).
 *
 * <p>Aanvullend op de event-getriggerde {@link CombatEventReward}: die geeft één puls op fire-edge,
 * deze geeft sustained pressure zolang de bot vuurt en mikt — vergelijkbaar met de gradient density
 * die alt-fire al krijgt via grenade-flight.
 */
public class PrimaryFireAimReward implements RewardComponent {

  private final PrimaryFireAimParams params;

  public PrimaryFireAimReward(PrimaryFireAimParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "PrimaryFireAimReward requires non-null PrimaryFireAimParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    double weight = params.weight();
    if (weight == 0.0) return 0.0;

    PlayerDto curr = ctx.curr().playerPawn;
    if (curr == null || !curr.fireActive) return 0.0;
    if (curr.location == null) return 0.0;

    // Primary-fire-only reward — gebruik primary projectile speed (chunks 2700 UU/s).
    double aimScore = LeadAimUtils.computeAimScore3D(
        ctx.curr(), ctx.modelTargetIndex(),
        ctx.config().getProjectileSpeedFlakPrimaryUu(),
        ctx.config().getProjectileSpeedFlakSecondaryUu(),
        ctx.config().getRocketPrimaryAimTargetHeightUu(),
        ctx.config().getSniperPrimaryAimTargetHeightUu(),
        false);
    double minScore = params.minScore();
    if (aimScore < minScore) return 0.0;

    PlayerDto enemy = LeadAimUtils.resolveLeadEnemy(ctx.curr(), ctx.modelTargetIndex());
    if (enemy == null) {
      enemy = RewardUtils.findClosestVisibleEnemy(ctx.curr());
    }
    if (enemy == null || enemy.location == null) return 0.0;

    double dx = enemy.location.x - curr.location.x;
    double dy = enemy.location.y - curr.location.y;
    double dz = enemy.location.z - curr.location.z;
    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

    double sigmaLong = Math.max(1.0, params.longRangeDampingSigmaUu());
    double rangeFactor = Math.exp(-dist / sigmaLong);

    return weight * aimScore * rangeFactor;
  }
}
