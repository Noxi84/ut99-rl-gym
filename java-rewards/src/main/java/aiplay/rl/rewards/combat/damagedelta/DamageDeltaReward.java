package aiplay.rl.rewards.combat.damagedelta;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.PlayerDto;

/**
 * Damage-based reward voor flak-mode (en andere non-instakill wapens).
 *
 * <p>Per tick:
 *
 * <ul>
 *   <li>damage_dealt = som over alle enemies van max(0, prev.health - curr.health),
 *       maar alleen wanneer {@code enemy.lastDamageInstigatorSlot == ownSlot}
 *   <li>damage_taken = max(0, self.prev.health - self.curr.health)
 *   <li>self_damage = self-inflicted splash (extra penalty bovenop damage_taken)
 *   <li>friendly_fire = HP-drops op teammates met instigatorSlot == ownSlot
 * </ul>
 *
 * <p><b>Attribution:</b> direct via {@code lastDamageInstigatorSlot} dat door
 * {@code RLCTFGame.ReduceDamage} → {@code RLUdpStateSender.RecordDamage} per
 * victim wordt vastgelegd. Multi-bot scenarios (2 RL-bots vuren tegelijk, een
 * teammate-UT99-bot raakt) krijgen geen valse credit meer — alleen de pawn
 * waarvan de slot door de UT99-engine als instigator is gemerkt, krijgt het
 * damage-reward signaal.
 *
 * <p>Edge case "last hit wins": als binnen één state-frame meerdere instigators
 * dezelfde victim raken, wordt alleen de laatste vastgelegd. De HP-drop wordt
 * dan volledig aan die instigator toegewezen, ook al hebben anderen geholpen.
 * Acceptabel — matches UT99's eigen kill-attribution semantiek.
 */
public class DamageDeltaReward implements RewardComponent {

  private final DamageDeltaParams params;

  public DamageDeltaReward(DamageDeltaParams params) {
    if (params == null) {
      throw new IllegalArgumentException("DamageDeltaReward requires non-null DamageDeltaParams");
    }
    this.params = params;
  }

  /** Damage-type van een Sniper-headshot in UT99 ({@code Botpack.SniperRifle.AltDamageType}). */
  private static final String HEADSHOT_DAMAGE_TYPE = "Decapitated";

  public record Result(double damageDealt, double damageTaken, double selfDamage,
                       double friendlyFire, double headshot) {
    public double total() {
      return damageDealt + damageTaken + selfDamage + friendlyFire + headshot;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    PlayerDto prevSelf = ctx.prev().playerPawn;
    PlayerDto currSelf = ctx.curr().playerPawn;

    int ownSlot = currSelf != null ? currSelf.slot : -1;

    // --- Damage taken (always counted — being shot costs HP regardless of attribution) ---
    int selfPrevH = prevSelf.health;
    int selfCurrH = currSelf.health;
    double selfDelta = (selfPrevH > 0 && selfCurrH >= 0) ? Math.max(0, selfPrevH - selfCurrH) : 0;
    // Filter out respawn-delta (health jumps from 0 to 100): prev.health must be alive.
    // Filter out spawn-invulnerability / healing: only count negative transitions.
    double damageTaken = selfDelta * params.takenPerHp();

    // --- Self-damage extra penalty ---
    // When the UC ReduceDamage hook flagged the latest event as self-inflicted
    // (flak chunks tegen muur, close-range grenade alt-fire), apply an extra
    // per-HP penalty on top of the generic damage_taken so the shooting model
    // gets a stronger deterrent for these specific mistakes.
    double selfDamage = 0.0;
    double extraPerHp = params.selfDamageExtraPerHp();
    if (extraPerHp != 0.0
        && currSelf.lastDamageAmount > 0
        && currSelf.lastDamageSelfInflicted) {
      // Clamp het per-event HP-bedrag: engine-instakills (void-fall op Facing Worlds, lava,
      // telefrag) rapporteren tienduizenden HP, wat ongeclampt de reward verplettert (-8000+ in
      // één tick) en de SAC-critic destabiliseert. selfDamageMaxHpPerEvent begrenst dit tot een
      // death-schaal straf en laat echte flak/rocket-splash (≤~150 HP) onaangeroerd. Map-
      // onafhankelijk: elke map met void/lava/fall-deaths profiteert.
      double clampedHp = Math.min(currSelf.lastDamageAmount, params.selfDamageMaxHpPerEvent());
      selfDamage = clampedHp * extraPerHp;
    }

    // --- Terminale void/lava-val-straf (2026-06-14, option A) ---
    // Een instakill-val (void op Facing Worlds, lava) rapporteert een lastDamageAmount ver boven
    // elke combat/splash-schade (≤~150 HP) → detecteer dat ONAFHANKELIJK van lastDamageSelfInflicted
    // (de UC-hook zet die niet betrouwbaar voor fall-deaths). Reden (gevalideerde root-cause): de dense
    // approach-progressie (objProgress) maakte "oprukken-richting-vlag-dan-vallen" netto-positief → de
    // policy KOOS de val (falls immuun voor std/edge-exploratie-tweaks). Deze EENMALIGE terminale kost
    // op de death-frame (geen per-frame farm zoals de gereverteerde void_avoidance, dus geen
    // idle-regressie) maakt de val onontkoombaar duurder dan de eraan voorafgaande approach-winst.
    // Map-onafhankelijk: elke map met instakill-void/lava profiteert. Via selfDamage → fire-head.
    if (params.voidFallPenalty() != 0.0
        && currSelf.lastDamageAmount > params.voidFallHpThreshold()) {
      selfDamage += params.voidFallPenalty();
    }

    // --- Damage dealt (credited only when UC reports US as instigator) ---
    // --- Headshot bonus: per Sniper-decap damage event waarvan WIJ instigator zijn ---
    double damageDealt = 0.0;
    double headshot = 0.0;
    if (ownSlot >= 0
        && (params.dealtPerHp() != 0.0
            || params.headshotBonus() != 0.0
            || params.efcDamageBonusPerHp() != 0.0)) {
      PlayerDto[] prevEnemies = ctx.prev().enemies;
      PlayerDto[] currEnemies = ctx.curr().enemies;
      if (prevEnemies != null && currEnemies != null) {
        int n = Math.min(prevEnemies.length, currEnemies.length);
        int headshotCount = 0;
        double rawHp = 0.0;
        // HP geschoten op de enemy flag carrier (EFC = enemy die ONZE vlag draagt). In CTF
        // kan een enemy alleen onze vlag dragen, dus hasFlag identificeert de EFC schaal-vrij.
        // pe.hasFlag || ce.hasFlag vangt zowel de pre-kill dense ticks (carried) als de
        // killing blow (pe carried, ce net dood/gedropt) robuust af.
        double efcHp = 0.0;
        for (int i = 0; i < n; i++) {
          PlayerDto pe = prevEnemies[i];
          PlayerDto ce = currEnemies[i];
          if (pe == null || ce == null) {
            continue;
          }
          // HP-drop credited only when the engine identifies us as instigator.
          if (pe.health > 0 && ce.health >= 0 && ce.health < pe.health
              && ce.lastDamageInstigatorSlot == ownSlot) {
            int hp = pe.health - ce.health;
            rawHp += hp;
            if (pe.hasFlag || ce.hasFlag) {
              efcHp += hp;
            }
            // Headshot bij sniper alt-damage-type "Decapitated".
            // UT99 zet die type wanneer HitLocation in head-zone valt; UT past
            // automatisch 2.2× damage (100 vs 45) toe, dus dealtPerHp pikt het
            // al impliciet op. headshotBonus voegt een expliciete eenmalige
            // event-bonus toe per gedecapiteerde enemy. Case-insensitive want
            // UnrealScript `name`-waarden zijn dat ook en de bron gebruikt zowel
            // 'Decapitated' als 'decapitated' (zie WeaponClassNameTable).
            if (HEADSHOT_DAMAGE_TYPE.equalsIgnoreCase(ce.lastDamageType)) {
              headshotCount++;
            }
          }
        }
        // Basis per-HP + EFC-extra per-HP. EFC-schade is dus (dealtPerHp + efcDamageBonusPerHp)
        // per HP → dense gradient die "engage + schiet de EFC neer" lonender maakt dan
        // voorbijlopen. Gevouwen in damageDealt zodat het via de fire-head wordt geleerd en in
        // het dmgDealt log-veld zichtbaar is.
        damageDealt = rawHp * params.dealtPerHp() + efcHp * params.efcDamageBonusPerHp();
        headshot = headshotCount * params.headshotBonus();
      }
    }

    // --- Friendly fire (damage to teammates, instigator-slot attribution) ---
    // Only count when the engine reports us as instigator. Vermijdt de cross-
    // attribution waar een ENEMY teammate van ons hit en wij toevallig recent
    // vuurden — dat oude pad gaf willekeurige FF-penalties.
    double friendlyFire = 0.0;
    double ffPerHp = params.friendlyFirePerHp();
    if (ownSlot >= 0 && ffPerHp != 0.0) {
      PlayerDto[] prevTeammates = ctx.prev().teammates;
      PlayerDto[] currTeammates = ctx.curr().teammates;
      if (prevTeammates != null && currTeammates != null) {
        int n = Math.min(prevTeammates.length, currTeammates.length);
        double ffDamage = 0.0;
        for (int i = 0; i < n; i++) {
          PlayerDto pt = prevTeammates[i];
          PlayerDto ct = currTeammates[i];
          if (pt == null || ct == null) {
            continue;
          }
          if (pt.health > 0 && ct.health >= 0 && ct.health < pt.health
              && ct.lastDamageInstigatorSlot == ownSlot) {
            ffDamage += (pt.health - ct.health);
          }
        }
        friendlyFire = ffDamage * ffPerHp;
      }
    }

    return new Result(damageDealt, damageTaken, selfDamage, friendlyFire, headshot);
  }
}
