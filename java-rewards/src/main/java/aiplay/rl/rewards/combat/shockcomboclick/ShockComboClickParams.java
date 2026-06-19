package aiplay.rl.rewards.combat.shockcomboclick;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Rising-edge klik-reward voor de shock-combo: beloont het MOMENT van het primary-schot
 * (fireActive 0→1) naar rato van hoe goed dat schot getimed/gericht is op een eigen live
 * {@code Botpack.ShockProj} nabij een enemy. Opvolger van de continue
 * {@link aiplay.rl.rewards.combat.shockcomboaim.ShockComboAimParams}-shaping (2026-06-05): die
 * beloonde elk frame van de fire-puls + gebruikte een afstandsafhankelijke cosine-drempel,
 * waardoor "knop vasthouden + richting bal staren" al krediet ving zonder klik-TIMING. Deze
 * variant maakt het klik-moment zelf het beloonde event — precies de beslissing (wachten →
 * klikken) die de combo onderscheidt van continu vuren.
 *
 * <p>Score per klik = {@code weight · exp(−beamMiss/beamSigmaUu) · exp(−ballEnemyDist/enemySigmaUu)}
 * over de beste eigen bal. {@code beamMiss} is de loodrechte afstand kijk-straal↔bal
 * ({@link aiplay.shared.view.FireModeAimTargeting#beamMissDistanceUu}) — afstandsonafhankelijk,
 * i.t.t. een cosine. Geen eigen bal of geen enemy → 0 (geen straf: gewone combat-schoten blijven
 * vrij). Weapon-gated op de vastgehouden shock rifle.
 *
 * <p>Anti-farm: het event is per definitie schaars (max 1 per primary-cyclus ~0.8s) en vereist
 * zowel beam-op-bal als bal-bij-enemy — beide tegelijk farmen = de combo daadwerkelijk uitvoeren.
 */
public record ShockComboClickParams(
    RewardMetadata metadata,
    /** Schaal van de klik-reward; een perfect getimede perfecte klik levert ~weight. */
    double weight,
    /** Sigma van de beam↔bal-misafstand-weging exp(−d/sigma). UU. */
    double beamSigmaUu,
    /** Sigma van de bal↔enemy-afstand-weging exp(−d/sigma); combo-radius is 250 UU. UU. */
    double enemySigmaUu,
    /** Onder deze bot→bal-afstand telt de bal niet (point-blank self-splash, geen combo). UU. */
    double minBallDistUu,
    /** Opportunity-cost-offset: reward per klik = weight·(score − offset), alléén wanneer er een
     *  kwalificerende eigen bal onderweg was. Een klik die een combo-kans verspilt (bal nog ver van
     *  de enemy → score < offset) wordt licht negatief; wachten-en-dan-klikken wordt zo direct
     *  lonend — de uitstel-samples die anders nooit ontstaan (fire-rate ~0.99 omdat klikken gratis
     *  is). Kliks ZONDER eigen bal blijven 0: gewone hitscan-combat onaangetast. */
    double baselineOffset)
    implements RewardBlock {

  public ShockComboClickParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ShockComboClickParams.metadata required");
    }
    if (beamSigmaUu <= 0.0) {
      throw new IllegalArgumentException("ShockComboClickParams.beamSigmaUu must be > 0");
    }
    if (enemySigmaUu <= 0.0) {
      throw new IllegalArgumentException("ShockComboClickParams.enemySigmaUu must be > 0");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
