package aiplay.rl.rewards.combat.shockcomboevent;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

/**
 * Sparse event-bonus voor een succesvolle shock-rifle combo, gedetecteerd via schade-hoogte.
 *
 * <p><b>Detectie</b>: een enemy-slot telt deze tick als combo-slachtoffer wanneer
 * <ul>
 *   <li>de enemy deze tick HP verloor ({@code pe.health > 0 && ce.health >= 0 && ce.health < pe.health}
 *       — dezelfde edge-detect als {@code DamageDeltaReward});
 *   <li>de UT99-engine ONS als instigator markeert ({@code ce.lastDamageInstigatorSlot == selfSlot},
 *       niet self-inflicted);
 *   <li>de schade ≥ {@code comboMinDamage} is — alleen {@code ShockProj.SuperExplosion()}
 *       ({@code Damage*3=165}, in de praktijk 117-158 na radius-falloff) haalt dat; een primary-beam
 *       (40) of gewone bal-explosie (max 55) kan dat fysiek nooit in één {@code TakeDamage};
 *   <li>het schade-type shock is ({@code "jolted"}) — sluit flak/rocket/elk ander wapen uit.
 * </ul>
 *
 * <p>Dit vervangt de oude bal-tracking + beam-detonatie-gate, die door frame-jitter en
 * beam-positie-raden ~de helft van de echte detonaties miste. De nieuwe detectie gebruikt
 * uitsluitend bestaande UDP-velden — geen UnrealScript-mod nodig — en is veilig-by-construction
 * voor andere wapens (zie {@link ShockComboEventParams}).
 *
 * <p>Damage-shaping zelf zit in {@code DamageDeltaReward} (Δhp wordt al beloond); deze reward
 * voegt een eenmalige event-bonus toe per combo zodat het model een expliciet skill-signaal krijgt.
 */
public class ShockComboEventReward implements RewardComponent {

  private static final String SHOCK_DAMAGE_TYPE = "jolted";

  private final ShockComboEventParams params;

  public ShockComboEventReward(ShockComboEventParams params) {
    if (params == null) {
      throw new IllegalArgumentException("ShockComboEventReward requires non-null params");
    }
    this.params = params;
  }

  public record Result(double bonus, int comboCount) {
    public double total() {
      return bonus;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    GameStateDto prev = ctx.prev();
    GameStateDto curr = ctx.curr();
    if (prev == null || curr == null) return new Result(0.0, 0);

    PlayerDto self = curr.playerPawn;
    if (self == null) return new Result(0.0, 0);
    int selfSlot = self.slot;
    if (selfSlot < 0) return new Result(0.0, 0);

    PlayerDto[] prevEnemies = prev.enemies;
    PlayerDto[] currEnemies = curr.enemies;
    if (prevEnemies == null || currEnemies == null) return new Result(0.0, 0);
    int n = Math.min(prevEnemies.length, currEnemies.length);

    double bonus = 0.0;
    int comboCount = 0;
    for (int i = 0; i < n; i++) {
      PlayerDto pe = prevEnemies[i];
      PlayerDto ce = currEnemies[i];
      if (isShockComboHit(pe, ce, selfSlot)) {
        bonus += params.eventWeight();
        comboCount++;
        logCombo(self, ce);
      }
    }
    return new Result(bonus, comboCount);
  }

  /**
   * True wanneer enemy-slot {@code ce} deze tick een shock-combo van ons opliep: HP-drop (freshness),
   * wij als engine-instigator, schade ≥ comboMinDamage (combo doet {@code Damage*3}), shock-type.
   */
  private boolean isShockComboHit(PlayerDto pe, PlayerDto ce, int selfSlot) {
    if (pe == null || ce == null) return false;
    // Freshness: enemy verloor deze tick HP (identiek aan DamageDeltaReward's edge-detect).
    if (!(pe.health > 0 && ce.health >= 0 && ce.health < pe.health)) return false;
    // Attributie: UT99-engine markeert ons als instigator van deze TakeDamage.
    if (ce.lastDamageInstigatorSlot != selfSlot) return false;
    if (ce.lastDamageSelfInflicted) return false;
    // Combo-discriminant: alleen de SuperExplosion (Damage*3=165 → 117-158) haalt deze schade in
    // één TakeDamage; een primary-beam (40) of gewone bal-explosie (55) kan dat nooit.
    if (ce.lastDamageAmount < params.comboMinDamage()) return false;
    // Shock-type maakt het wapen-specifiek → veilig voor flak/rocket/elk ander wapen.
    return isShockDamage(ce.lastDamageType);
  }

  private static boolean isShockDamage(String type) {
    return type != null && SHOCK_DAMAGE_TYPE.equalsIgnoreCase(type.trim());
  }

  // Lichte telemetrie zodat de combo-rate uit de bot-logs te grep'en is (COMBO_HIT). De
  // DeltaGate/scores-logger heeft geen combo-KPI; dit is de enige meetbron. Sample-loos: een
  // combo is zeldzaam genoeg dat elke hit loggen geen log-spam geeft.
  private static void logCombo(PlayerDto self, PlayerDto victim) {
    System.out.println("COMBO_HIT by=" + String.valueOf(self.name)
        + " victim=" + String.valueOf(victim.name)
        + " dmg=" + victim.lastDamageAmount);
  }
}
