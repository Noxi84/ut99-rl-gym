package aiplay.rl.rewards.combat.shockcomboclick;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.shared.view.FireModeAimTargeting;

/**
 * Rising-edge klik-reward voor de shock-combo — zie {@link ShockComboClickParams}.
 *
 * <p>Triggert uitsluitend op de 0→1-flank van {@code fireActive} met de shock rifle vast
 * (bij shock perst de fire-state-machine continu-willen al om in ~100ms-pulsen, dus de flank
 * ≈ het daadwerkelijke schot-moment). De score weegt het schot naar beam-op-bal-precisie
 * (loodrechte miss-afstand, afstandsonafhankelijk) × bal-bij-enemy-nabijheid.
 */
public class ShockComboClickReward implements RewardComponent {

  private static final String SHOCK_PROJ_CLASS = "Botpack.ShockProj";

  private final ShockComboClickParams params;

  public ShockComboClickReward(ShockComboClickParams params) {
    if (params == null) {
      throw new IllegalArgumentException("ShockComboClickReward requires non-null params");
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
    if (!FireModeAimTargeting.isShockRifleClass(self.weaponClass)) return 0.0;

    // Rising edge: dit frame vuurt de primary, het vorige niet (= het klik-/schot-moment).
    GameStateDto prev = ctx.prev();
    boolean prevFire = prev != null && prev.playerPawn != null && prev.playerPawn.fireActive;
    if (!self.fireActive || prevFire) return 0.0;
    if (curr.projectiles == null) return 0.0;

    // Enemy-resolutie met player1-fallback (state.enemies is in deze context vaak leeg).
    PlayerDto enemy = RewardUtils.findClosestVisibleEnemy(curr);
    if (enemy == null) enemy = RewardUtils.findClosestEnemy(curr);
    if (enemy == null || enemy.location == null) return 0.0;

    double minBallSqr = params.minBallDistUu() * params.minBallDistUu();
    boolean hasBall = false;
    double best = 0.0;
    for (ProjectileDto p : curr.projectiles) {
      if (p == null || p.location == null || p.projectileClass == null) continue;
      if (!SHOCK_PROJ_CLASS.equalsIgnoreCase(p.projectileClass)) continue;
      if (!self.name.equals(p.instigatorName)) continue;

      double bdx = p.location.x - self.location.x;
      double bdy = p.location.y - self.location.y;
      double bdz = p.location.z - self.location.z;
      if (bdx * bdx + bdy * bdy + bdz * bdz < minBallSqr) continue;
      hasBall = true;

      double beamMiss = FireModeAimTargeting.beamMissDistanceUu(curr, p.location);
      if (!Double.isFinite(beamMiss)) continue; // bal achter de bot

      double bex = enemy.location.x - p.location.x;
      double bey = enemy.location.y - p.location.y;
      double bez = enemy.location.z - p.location.z;
      double ballEnemyDist = Math.sqrt(bex * bex + bey * bey + bez * bez);

      double r = Math.exp(-beamMiss / params.beamSigmaUu())
          * Math.exp(-ballEnemyDist / params.enemySigmaUu());
      if (r > best) best = r;
    }
    if (!hasBall) {
      return 0.0; // gewone hitscan-combat zonder eigen bal: klik blijft gratis
    }
    // Opportunity-cost: een klik die een lopende combo-kans verspilt (score < offset) is licht
    // negatief; een goed getimede klik sterk positief. Creëert de uitstel-samples waaruit
    // "wachten tot de bal bij de enemy is" leerbaar wordt.
    return weight * (best - params.baselineOffset());
  }
}
