package aiplay.rl.rewards.combat.ammoconsumptionpenalty;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.InventoryItemDto;
import aiplay.dto.PlayerDto;
import java.util.HashMap;
import java.util.Map;

/**
 * Resource-cost reward: straft elke verbruikte ammo unit tussen prev en curr frame.
 *
 * <p>Doel: vervang binaire fire/niet-fire policy door efficiency-policy. Per-tick fire-holding
 * straf leerde "knop niet indrukken" als lokaal optimum (bullet-flight tijd verbergt damage-feedback).
 * Per-ammo straf is direct en proportioneel met output: 1 shot kost -X, 8-shot spam kost -8X,
 * maar dezelfde 8 shots die een 80HP hit landen (+12.0 via damage_delta) zijn netto sterk positief.
 *
 * <p>Implementatie: matcht inventory items tussen prev en curr op {@code weaponClass} en
 * sommeert per-weapon ammo drops. Robuust tegen weapon-switching (alleen het wapen waarvan
 * de bot daadwerkelijk vuurt verliest ammo). Skipt respawn (health ≤ 0). Pickups
 * (curr > prev) tellen als 0 via {@code max(0, ...)}.
 */
public class AmmoConsumptionPenaltyReward implements RewardComponent {

  private final AmmoConsumptionPenaltyParams params;

  public AmmoConsumptionPenaltyReward(AmmoConsumptionPenaltyParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "AmmoConsumptionPenaltyReward requires non-null AmmoConsumptionPenaltyParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    double penalty = params.perAmmoPenalty();
    if (penalty == 0.0) return 0.0;

    PlayerDto prev = ctx.prev().playerPawn;
    PlayerDto curr = ctx.curr().playerPawn;
    if (prev == null || curr == null) return 0.0;
    if (prev.health <= 0 || curr.health <= 0) return 0.0;
    if (prev.inventory == null || curr.inventory == null) return 0.0;

    int totalConsumed = 0;
    Map<String, Integer> prevByClass = new HashMap<>();
    for (InventoryItemDto pi : prev.inventory) {
      if (pi != null && pi.weaponClass != null) {
        prevByClass.put(pi.weaponClass, pi.ammoAmount);
      }
    }
    for (InventoryItemDto ci : curr.inventory) {
      if (ci == null || ci.weaponClass == null) continue;
      Integer prevAmmo = prevByClass.get(ci.weaponClass);
      if (prevAmmo == null) continue;
      int delta = prevAmmo - ci.ammoAmount;
      if (delta > 0) totalConsumed += delta;
    }

    if (totalConsumed == 0) return 0.0;
    return totalConsumed * penalty;
  }
}
