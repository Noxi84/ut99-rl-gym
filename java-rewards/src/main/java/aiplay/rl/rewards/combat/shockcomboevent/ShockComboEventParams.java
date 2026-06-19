package aiplay.rl.rewards.combat.shockcomboevent;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Sparse event-bonus voor een succesvolle shock-rifle combo.
 *
 * <p><b>Detectie via schade-hoogte</b> (2026-06-04). De UT99-bron toont dat de combo de
 * functie {@code ShockProj.SuperExplosion()} is — {@code HurtRadius(Damage*3, 250, ...)},
 * dus 3× de schade van een gewone bal-explosie ({@code HurtRadius(Damage, 70, ...)}). Stock
 * UT99 markeert de combo NIET met een eigen schade-type: directe beam, gewone explosie én
 * combo dragen allemaal {@code MyDamageType="jolted"}. Maar de schade-grootte onderscheidt ze
 * fysiek schoon: een primary-beam doet {@code HitDamage=40}, een gewone bal-explosie max
 * {@code Damage=55}, terwijl de combo {@code Damage*3=165} doet (in de praktijk 117-158 na
 * radius-falloff). Op 3070-self-play-data is er een leeg gat tussen ~67 en ~117 — elk
 * {@code lastDamageAmount >= comboMinDamage} (met shock-type) is dus eenduidig een combo.
 *
 * <p>Dit vervangt de oude bal-tracking + beam-detonatie-gate (die ~de helft van de echte
 * detonaties miste door frame-jitter en beam-positie-raden). De nieuwe detectie gebruikt
 * uitsluitend bestaande UDP-velden ({@code lastDamageAmount/Type/InstigatorSlot}) — geen
 * UnrealScript-mod nodig. Veilig-by-construction: het shock-type-filter ("jolted") sluit
 * flak/rocket/elk ander wapen uit, dus 0 effect op andere wapens of maps.
 *
 * <p>Damage-shaping zelf zit in {@code DamageDeltaReward} (Δhp wordt al beloond); deze reward
 * voegt een eenmalige event-bonus toe per combo zodat het model een expliciet skill-signaal
 * krijgt voor de "schiet primary op eigen ShockProj met enemy in splash"-tactiek.
 */
public record ShockComboEventParams(
    RewardMetadata metadata,
    /** Vlakke bonus per gedetecteerde combo-event (enemy nam combo-schade van ons deze tick). */
    double eventWeight,
    /** Minimale {@code lastDamageAmount} (in HP) om een shock-hit als combo te tellen. De combo
     *  doet {@code Damage*3=165} (117-158 na falloff), gewone shock-hits max 55 (bal) of 40 (beam);
     *  een drempel in het lege gat (~90) scheidt ze eenduidig. */
    double comboMinDamage)
    implements RewardBlock {

  public ShockComboEventParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ShockComboEventParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return eventWeight != 0.0;
  }
}
