package aiplay.rl.rewards.combat.projectileaim;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;

/**
 * Per-tick continuous shaping reward voor eigen projectielen (vooral flak grenades en chunks): hoe
 * dichter een projectiel bij een vijand komt, hoe hoger de reward.
 *
 * <p>Per projectile van eigen team:
 * {@code w · exp(-min_dist_to_any_enemy / sigma) · range_factor(bot_to_enemy_dist)}
 *
 * <p>Som over alle eigen projectielen, gemiddeld als er &gt;0 zijn.
 *
 * <p>De {@code range_factor = 1 - exp(-bot_to_enemy_dist / sigma_short)} dempt de reward bij korte
 * afstand tussen bot en het getargete enemy: dichtbij is primary fire het juiste gereedschap, en
 * alt-fire grenades dichtbij brengen self-damage risico mee. Asymptotisch nadert de factor 1 op
 * grote afstand, dus grenades naar verre vijanden krijgen de volle reward.
 *
 * <p>Attribution (MVP): filter op {@code instigatorTeam == self.team}. In 1v1 training is dat
 * automatisch "mijn projectile"; in multi-bot geeft dit credit aan het hele team (aanvaardbaar
 * MVP).
 */
public class ProjectileAimReward implements RewardComponent {

  private final ProjectileAimParams params;

  public ProjectileAimReward(ProjectileAimParams params) {
    if (params == null) {
      throw new IllegalArgumentException("ProjectileAimReward requires non-null ProjectileAimParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    double weight = params.weight();
    if (weight == 0.0) return 0.0;

    GameStateDto curr = ctx.curr();
    PlayerDto self = curr.playerPawn;
    if (self == null || self.location == null) return 0.0;
    if (curr.projectiles == null || curr.projectiles.isEmpty()) return 0.0;
    if (curr.enemies == null) return 0.0;

    double sigma = Math.max(1.0, params.sigmaUt());
    double sigmaShort = Math.max(1.0, params.closeRangeDampingSigmaUu());

    double total = 0.0;
    int count = 0;
    for (ProjectileDto p : curr.projectiles) {
      if (p == null || p.location == null) continue;
      if (p.instigatorTeam != self.team) continue;

      double minDist = Double.POSITIVE_INFINITY;
      double botDistAtMin = Double.POSITIVE_INFINITY;
      for (PlayerDto e : curr.enemies) {
        if (e == null || e.health <= 0 || e.location == null) continue;
        double dx = e.location.x - p.location.x;
        double dy = e.location.y - p.location.y;
        double dz = e.location.z - p.location.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < minDist) {
          minDist = d;
          double bdx = e.location.x - self.location.x;
          double bdy = e.location.y - self.location.y;
          double bdz = e.location.z - self.location.z;
          botDistAtMin = Math.sqrt(bdx * bdx + bdy * bdy + bdz * bdz);
        }
      }
      if (!Double.isFinite(minDist)) continue;

      double rangeFactor = 1.0 - Math.exp(-botDistAtMin / sigmaShort);
      total += Math.exp(-minDist / sigma) * rangeFactor;
      count++;
    }
    if (count == 0) return 0.0;
    return weight * (total / count);
  }
}
