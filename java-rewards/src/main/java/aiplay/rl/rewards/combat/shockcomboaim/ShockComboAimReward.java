package aiplay.rl.rewards.combat.shockcomboaim;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.shared.view.FireModeAimTargeting;

/**
 * Dense per-tick ontdekkings-shaping voor de shock-combo — zie {@link ShockComboAimParams}.
 *
 * <p>Per tick: als de bot primary vuurt en minstens één eigen live {@code Botpack.ShockProj} bezit,
 * zoek de bal met de hoogste reward-bijdrage en geef
 * {@code weight · normAlign · enemyFactor}:
 * <ul>
 *   <li>{@code normAlign}: aim-cosine bot→bal herschaald boven {@code minAimCos} naar [0,1].</li>
 *   <li>{@code enemyFactor = exp(-balAfstandTotEnemy / ballEnemySigmaUu)}: weegt naar ballen die de
 *       vijand zullen vangen (een echte combo), zodat de gradient niet naar nutteloze muur-combo's
 *       wijst.</li>
 * </ul>
 * Gated op een enemy binnen {@code enemyContextRangeUu} en een bal op bruikbare mid-range.
 */
public class ShockComboAimReward implements RewardComponent {

  private static final String SHOCK_PROJ_CLASS = "Botpack.ShockProj";

  private final ShockComboAimParams params;

  public ShockComboAimReward(ShockComboAimParams params) {
    if (params == null) {
      throw new IllegalArgumentException("ShockComboAimReward requires non-null params");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    double weight = params.weight();
    if (weight == 0.0) return 0.0;

    GameStateDto curr = ctx.curr();
    if (curr == null) return 0.0;
    PlayerDto self = curr.playerPawn;
    if (self == null || self.location == null || self.name == null) return 0.0;
    // Alleen tijdens het beamen: de reward bekrachtigt de primary-fire-actie die de bal detoneert.
    if (!self.fireActive) return 0.0;
    if (curr.projectiles == null) return 0.0;

    // Combat-context (anti-farm): geen combo-shaping zonder een nabije vijand.
    PlayerDto enemy = RewardUtils.findClosestVisibleEnemy(curr);
    if (enemy == null) enemy = RewardUtils.findClosestEnemy(curr);
    if (enemy == null || enemy.location == null) return 0.0;
    double edx = enemy.location.x - self.location.x;
    double edy = enemy.location.y - self.location.y;
    double edz = enemy.location.z - self.location.z;
    if (edx * edx + edy * edy + edz * edz
        > params.enemyContextRangeUu() * params.enemyContextRangeUu()) {
      return 0.0;
    }

    double minCos = params.minAimCos();
    double minBallSqr = params.minBallDistUu() * params.minBallDistUu();
    double maxBallSqr = params.maxBallDistUu() * params.maxBallDistUu();
    double sigma = params.ballEnemySigmaUu();

    double best = 0.0;
    for (ProjectileDto p : curr.projectiles) {
      if (p == null || p.location == null || p.projectileClass == null) continue;
      if (!SHOCK_PROJ_CLASS.equalsIgnoreCase(p.projectileClass)) continue;
      if (!self.name.equals(p.instigatorName)) continue;

      double bdx = p.location.x - self.location.x;
      double bdy = p.location.y - self.location.y;
      double bdz = p.location.z - self.location.z;
      double ballSqr = bdx * bdx + bdy * bdy + bdz * bdz;
      if (ballSqr < minBallSqr || ballSqr > maxBallSqr) continue;

      double aimCos = FireModeAimTargeting.aimCosineToPoint(curr, p.location);
      if (aimCos < minCos) continue;
      double normAlign = (aimCos - minCos) / (1.0 - minCos);

      double bex = enemy.location.x - p.location.x;
      double bey = enemy.location.y - p.location.y;
      double bez = enemy.location.z - p.location.z;
      double ballEnemyDist = Math.sqrt(bex * bex + bey * bey + bez * bez);
      double enemyFactor = Math.exp(-ballEnemyDist / sigma);

      double r = weight * normAlign * enemyFactor;
      if (r > best) best = r;
    }
    return best;
  }
}
