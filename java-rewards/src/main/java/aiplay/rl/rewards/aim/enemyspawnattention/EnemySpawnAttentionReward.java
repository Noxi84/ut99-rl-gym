package aiplay.rl.rewards.aim.enemyspawnattention;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.shared.view.EnemySpawnTargeting;

/**
 * Belont de view-policy voor het richten op een enemy-team spawnlocatie wanneer er geen levende
 * vijand is. Voorkomt het rondjes-draaien tijdens dual-respawn vensters: zonder dit signaal hebben
 * alle enemy-slots waarde 0 en heeft het LSTM geen indicatie waarheen te kijken.
 *
 * <p>De gekozen spawn wordt {@link EnemySpawnAttentionParams#holdTicks() holdTicks} ticks
 * vastgehouden ("sticky") zodat de "dichtstbijzijnde" spawn niet elke paar ticks flipt terwijl de
 * bot beweegt — wat anders als doelloos draaien overkomt. Bij respawn (≥1 enemy weer levend) wordt
 * de state gereset, zodat het volgende all-dead window opnieuw vers selecteert.
 *
 * <p>Mutually exclusive met {@link ViewAlignmentReward#computeViewAlignment}: die schakelt in zodra
 * er een attention-target is (levende enemy), en deze schakelt alleen in als alle enemies dood
 * zijn.
 */
public class EnemySpawnAttentionReward implements RewardComponent {

  private final EnemySpawnAttentionParams params;

  // Sticky spawn-state (per-bot — RewardComputer is per sessie) for fallback when the
  // feature-enricher has not annotated the frame yet.
  private final EnemySpawnTargeting.TargetState fallbackTargetState =
      new EnemySpawnTargeting.TargetState();

  public EnemySpawnAttentionReward(EnemySpawnAttentionParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "EnemySpawnAttentionReward requires non-null EnemySpawnAttentionParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    double bonus = params.bonus();
    if (bonus == 0.0) {
      return 0.0;
    }

    GameStateDto curr = ctx.curr();
    if (curr.playerPawn == null
        || curr.playerPawn.location == null
        || curr.playerPawn.viewRotation == null) {
      return 0.0;
    }

    CoordinatesDto target = curr.annotatedEnemySpawnTarget;
    if (target == null) {
      target = EnemySpawnTargeting.resolveAimPoint(curr, fallbackTargetState, params.holdTicks());
    } else if (!EnemySpawnTargeting.hasAllEnemiesDead(curr)) {
      fallbackTargetState.reset();
      return 0.0;
    }

    if (target == null) {
      return 0.0;
    }

    double dx = target.x - curr.playerPawn.location.x;
    double dy = target.y - curr.playerPawn.location.y;
    double dist = Math.sqrt(dx * dx + dy * dy);
    if (dist < 1.0) {
      return bonus;
    }
    dx /= dist;
    dy /= dist;

    double yawRad = (curr.playerPawn.viewRotation.x & 0xFFFF) * (2.0 * Math.PI / 65536.0);
    double viewDirX = Math.cos(yawRad);
    double viewDirY = Math.sin(yawRad);

    double dot = viewDirX * dx + viewDirY * dy;
    return bonus * dot * Math.abs(dot);
  }
}
