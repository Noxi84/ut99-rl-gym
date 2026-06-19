package aiplay.rl.rewards.combat.shockcombocurriculum;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.shared.view.FireModeAimTargeting;

/**
 * Dense PBRS-curriculum voor de shock-combo — zie {@link ShockComboCurriculumParams}.
 *
 * <p>Potential Φ(s) = max over eigen live {@code Botpack.ShockProj} van
 * {@code exp(-beamMiss/beamSigma) · exp(-ballEnemyDist/enemySigma)}: hoog wanneer de primary-beam van
 * de bot bijna een eigen bal raakt die bijna een enemy raakt — de combo-readiness. De reward is
 * {@code weight · clip(Φ(curr)-Φ(prev))} en wordt alleen gegeven wanneer BEIDE frames een
 * combo-relevante bal hebben (continuïteits-guard). Zo:
 * <ul>
 *   <li>beloont het de geleidelijke verbetering van de beam-op-bal-precisie — de ontbrekende stap,
 *       i.t.t. de grove {@code aimCos≥0.95}-drempel van
 *       {@link aiplay.rl.rewards.combat.shockcomboaim.ShockComboAimReward};</li>
 *   <li>straft het de voltooiing NIET (bal verdwijnt → Φ valt → de guard onderdrukt de negatieve delta);</li>
 *   <li>kan een bal-spawn/-wissel geen spurious sprong farmen (guard + clip).</li>
 * </ul>
 * Op het continue deel policy-invariant (PBRS, Ng et al. 1999); de guard/clip breken die invariantie
 * minimaal maar voorkomen de niet-stationaire farm die eerdere combo-shaping trof.
 *
 * <p>Enemy-resolutie volgt exact {@link ShockComboAimReward}: closest-visible → closest met
 * {@link RewardUtils#findClosestEnemy} dat naar {@code state.player1} terugvalt wanneer
 * {@code state.enemies} leeg is (anders zou Φ structureel 0 blijven).
 */
public class ShockComboCurriculumReward implements RewardComponent {

  private static final String SHOCK_PROJ_CLASS = "Botpack.ShockProj";

  private final ShockComboCurriculumParams params;

  public ShockComboCurriculumReward(ShockComboCurriculumParams params) {
    if (params == null) {
      throw new IllegalArgumentException("ShockComboCurriculumReward requires non-null params");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (params.weight() == 0.0) return 0.0;
    double phiCurr = computePotential(ctx.curr());
    double phiPrev = computePotential(ctx.prev());
    // Continuïteits-guard: alleen shaping als beide frames een combo-relevante bal hebben.
    // Onderdrukt spurious sprongen bij spawn (0→hoog), detonatie (hoog→0) en bal-wissel.
    if (phiCurr <= 0.0 || phiPrev <= 0.0) return 0.0;
    double delta = phiCurr - phiPrev;
    double clip = params.maxDeltaPerFrame();
    if (delta > clip) delta = clip;
    else if (delta < -clip) delta = -clip;
    return params.weight() * delta;
  }

  /**
   * Φ(s): maximale combo-readiness over alle eigen live ShockProj-ballen. 0 wanneer er geen
   * combo-relevante bal is (geen eigen bal, geen levende enemy, of bal te ver van de enemy).
   */
  private double computePotential(GameStateDto state) {
    if (state == null) return 0.0;
    PlayerDto self = state.playerPawn;
    if (self == null || self.name == null || self.location == null) return 0.0;
    if (state.projectiles == null) return 0.0;

    // Enemy-resolutie identiek aan ShockComboAimReward (met player1-fallback in findClosestEnemy):
    // state.enemies is in deze context vaak leeg → vijand zit in player1.
    PlayerDto enemy = RewardUtils.findClosestVisibleEnemy(state);
    if (enemy == null) enemy = RewardUtils.findClosestEnemy(state);
    if (enemy == null || enemy.location == null) return 0.0;

    double beamSigma = params.beamSigmaUu();
    double enemySigma = params.enemySigmaUu();
    double maxRangeSq = params.maxBallEnemyRangeUu() * params.maxBallEnemyRangeUu();

    double best = 0.0;
    for (ProjectileDto p : state.projectiles) {
      if (p == null || p.location == null || p.projectileClass == null) continue;
      if (!SHOCK_PROJ_CLASS.equalsIgnoreCase(p.projectileClass)) continue;
      if (!self.name.equals(p.instigatorName)) continue;

      double ex = enemy.location.x - p.location.x;
      double ey = enemy.location.y - p.location.y;
      double ez = enemy.location.z - p.location.z;
      double ballEnemySq = ex * ex + ey * ey + ez * ez;
      if (ballEnemySq > maxRangeSq) continue; // bal niet combo-relevant

      double enemyProx = Math.exp(-Math.sqrt(ballEnemySq) / enemySigma);
      double beamMiss = FireModeAimTargeting.beamMissDistanceUu(state, p.location);
      double beamProx = Math.exp(-beamMiss / beamSigma); // beamMiss=+inf (bal achter bot) → 0
      double phi = beamProx * enemyProx;
      if (phi > best) best = phi;
    }
    return best;
  }
}
