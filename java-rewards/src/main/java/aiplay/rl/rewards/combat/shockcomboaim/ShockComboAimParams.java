package aiplay.rl.rewards.combat.shockcomboaim;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense ontdekkings-shaping voor de shock-combo. Waar {@link
 * aiplay.rl.rewards.combat.shockcomboevent.ShockComboEventParams} alleen op SUCCES (bal vanished +
 * enemy geraakt) een sparse bonus geeft, levert deze component een continue per-tick gradient
 * richting de combo-<em>uitvoering</em> zelf: de bot wordt beloond wanneer hij zijn primary beam
 * (hitscan) richt op een eigen live {@code Botpack.ShockProj} terwijl die bal in de buurt van een
 * enemy is. Dat is precies de "schiet primary op je eigen bal nabij de vijand"-timing die anders
 * alleen toevallig ontdekt wordt.
 *
 * <p>Weapon-mechanic-based en daarmee map-/wapen-agnostisch: triggert uitsluitend als er een eigen
 * ShockProj bestaat → 0 effect op flak/rocket/elk ander wapen, op elke map. De afstanden zijn
 * wapen-fysica-constanten (bal-snelheid, beam-range), niet map-geometrie, dus een from-scratch run
 * of een nieuwe map leert de skill zonder her-tuning.
 *
 * <p>Anti-farm: gated op {@code fireActive} (alleen tijdens het beamen — correcte credit), op een
 * bal op bruikbare mid-range (geen point-blank self-splash), en op een enemy binnen
 * {@code enemyContextRangeUu} (geen reward voor combo'en tegen een muur). De {@code ballEnemySigmaUu}
 * weegt de reward naar ballen die de vijand daadwerkelijk zullen vangen.
 */
public record ShockComboAimParams(
    RewardMetadata metadata,
    /** Schaal van de per-tick combo-aim reward (vóór routing-gewicht). */
    double weight,
    /** Minimale aim-cosine bot→bal; daaronder 0 (beam moet écht op de bal wijzen). */
    double minAimCos,
    /** Onder deze bot→bal-afstand geen reward (point-blank = self-splash-risico, geen combo-ruimte). UU. */
    double minBallDistUu,
    /** Boven deze bot→bal-afstand geen reward (bal al voorbij bruikbare beam-detonatie-range). UU. */
    double maxBallDistUu,
    /** Combat-context: enemy moet binnen deze afstand zijn, anders 0 (anti-farm). UU. */
    double enemyContextRangeUu,
    /** Sigma van de bal→enemy nabijheids-weging exp(-d/sigma): hoger = ook verder-van-enemy ballen tellen. UU. */
    double ballEnemySigmaUu)
    implements RewardBlock {

  public ShockComboAimParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ShockComboAimParams.metadata required");
    }
    if (minAimCos >= 1.0 || minAimCos < 0.0) {
      throw new IllegalArgumentException("ShockComboAimParams.minAimCos must be in [0,1)");
    }
    if (ballEnemySigmaUu <= 0.0) {
      throw new IllegalArgumentException("ShockComboAimParams.ballEnemySigmaUu must be > 0");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
