package aiplay.rl.rewards.combat.damagedelta;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Damage-based shaping (flak-mode). Per-tick HP-deltas tussen prev en curr frame:
 *
 * <ul>
 *   <li>{@code dealtPerHp}: bonus per HP geschoten door de bot. Attributie is exact:
 *       UC's {@code RLCTFGame.ReduceDamage} hook merkt elke damage event met de
 *       instigator slot, wat per-victim wordt doorgestuurd in de UDP state. Reward
 *       geldt alleen voor HP-drops waar de UT99-engine ons als instigator merkt.</li>
 *   <li>{@code takenPerHp}: straf per HP geleden, ongeacht attributie.</li>
 *   <li>{@code selfDamageExtraPerHp}: extra straf bovenop {@code takenPerHp} wanneer de damage als
 *       self-inflicted is geflagged door de UC ReduceDamage hook (flak chunks tegen muur,
 *       close-range grenade alt-fire).</li>
 *   <li>{@code friendlyFirePerHp}: straf per HP toegebracht aan teammates (zelfde
 *       instigator-slot attributie als {@code dealtPerHp}).</li>
 *   <li>{@code headshotBonus}: extra bonus per headshot-event (Sniper Rifle alt-damage-type
 *       {@code "Decapitated"} — UT99 past automatisch 100 HP toe i.p.v. 45 bij hit op
 *       head-zone {@code HitLocation.Z > Pawn.Location.Z + 0.62 * CollisionHeight}).
 *       Wordt per geraakte enemy één keer toegekend voor elke frame waarin het damage
 *       type "Decapitated" is met onze slot als instigator. Active bij weapon_profile=sniper
 *       of any-mode waarin sniper beschikbaar is.</li>
 *   <li>{@code efcDamageBonusPerHp}: EXTRA bonus per HP geschoten op de enemy flag carrier
 *       (EFC = enemy die ONZE vlag draagt; in CTF kan een enemy alleen onze vlag dragen, dus
 *       {@code enemy.hasFlag} identificeert de EFC schaal-vrij). Bovenop {@code dealtPerHp}.
 *       Dense shaping-gradient die "stop en schiet de EFC neer" lonender maakt dan voorbijlopen:
 *       lost het overshoot-gedrag op waarbij de attacker langs de EFC rent i.p.v. te engagen.
 *       Gevouwen in {@code damageDealt} → fire-head. Sparse {@code flag_carrier_kill} blijft de
 *       terminale kill-bonus; deze dense reward maakt de weg ernaartoe glad.</li>
 * </ul>
 */
public record DamageDeltaParams(
    RewardMetadata metadata,
    double dealtPerHp,
    double takenPerHp,
    double selfDamageExtraPerHp,
    double selfDamageMaxHpPerEvent,
    double friendlyFirePerHp,
    double headshotBonus,
    double efcDamageBonusPerHp,
    double voidFallPenalty,
    double voidFallHpThreshold)
    implements RewardBlock {

  public DamageDeltaParams {
    if (metadata == null) {
      throw new IllegalArgumentException("DamageDeltaParams.metadata required");
    }
    // Map-onafhankelijke veiligheids-clamp: engine-instakills (void-fall, lava, telefrag)
    // rapporteren een enorme lastDamageAmount (10k+ HP). Ongeclampt domineert dat alle andere
    // rewards en destabiliseert de SAC-critic (±8000 outliers in een ±10 buffer). Een echte
    // self-inflicted UT99-splash (flak/rocket close-range) overschrijdt ~150 HP niet, dus de
    // clamp behoudt de splash-deterrent maar maakt een val een begrensde, death-schaal straf.
    if (selfDamageMaxHpPerEvent <= 0.0) {
      throw new IllegalArgumentException(
          "DamageDeltaParams.selfDamageMaxHpPerEvent must be > 0, was " + selfDamageMaxHpPerEvent);
    }
  }

  @Override
  public boolean enabled() {
    return dealtPerHp != 0.0
        || takenPerHp != 0.0
        || selfDamageExtraPerHp != 0.0
        || friendlyFirePerHp != 0.0
        || headshotBonus != 0.0
        || efcDamageBonusPerHp != 0.0;
  }
}
