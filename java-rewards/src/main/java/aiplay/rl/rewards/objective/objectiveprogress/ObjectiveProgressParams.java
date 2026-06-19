package aiplay.rl.rewards.objective.objectiveprogress;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense progress-shaping naar het huidige movement-objective + alive bonus per tick.
 *
 * <ul>
 *   <li>{@code progressScale}: vermenigvuldigt de afstandsdelta (UU prev → curr) richting het
 *       objective. Geclamped op ±50 UU/tick om objective-switches niet absurd te belonen.</li>
 *   <li>{@code aliveBonus}: vlakke per-tick bonus zolang {@code health > 0}.</li>
 *   <li>{@code ownFlagReturnProgressScale}: alternatieve schaal die actief is wanneer
 *       {@code RewardUtils.isOwnFlagReturnPriority} geldt — typically hoger zodat het terughalen
 *       van een gedropte eigen vlag zwaarder weegt dan de standaard CTF flow.</li>
 *   <li>{@code carrierProgressScale}: alternatieve (hogere) progress-schaal die ALLEEN actief is
 *       wanneer deze bot zelf de enemy-vlag draagt ({@code hasFlag}). Vervangt {@code progressScale}
 *       op het carry-home pad zodat de escape-en-breng-thuis veel sterker beloond wordt dan de
 *       grab-run — de carrier prioriteert het ontsnappen uit enemy-territory + de dense per-tick pull
 *       compenseert de sparse {@code captured}-event (anders leert de carry-home niet: bot grijpt maar
 *       dropt ~80%). 0.0 = uit (valt terug op progressScale). Puur movement-shaping.</li>
 *   <li>{@code efcEngagementRangeUu}: engagement-standoff voor de EFC-chase (eigen vlag CARRIED door
 *       enemy). Wanneer actief worden prev/curr afstand tot de bewegende EFC geclamped op
 *       {@code max(dist, efcEngagementRangeUu)} vóór de progress-delta. Effect: sluiten wordt beloond
 *       tot de band-rand, beweging binnen de band geeft 0, en de band-rand is een attractor → de bot
 *       trailt een vluchtende EFC op ~range i.p.v. erdoorheen te lopen (overshoot-fix Q2). Geldt ALLEEN
 *       bij status==CARRIED (niet de stilstaande dropped-flag of generieke capture-run). 0.0 = uit
 *       (monotone Δdist→0, oude gedrag). ~350 UU = buiten eigen rocket-splash (220) + binnen
 *       betrouwbare splash op de EFC.</li>
 *   <li>{@code efcThreatProgressScale}: dense "deny-progress" straf naarmate de EFC (enemy die onze
 *       vlag draagt) dichter bij ZIJN capture-punt (vijandelijke vlagbasis) komt. Potential-based +
 *       symmetrisch: straf wanneer de EFC vooruitkomt, beloning wanneer hij wordt teruggedrongen/
 *       geblokt. Geschaald met {@code efcThreatProximityRangeUu} (de bot die kan blokkeren voelt het
 *       't sterkst; verre bots 0). 0.0 = uit. Geeft oplopende urgentie om de EFC te stoppen vóór de
 *       capture + beloont blokkeren emergent.</li>
 *   <li>{@code efcThreatProximityRangeUu}: nabijheidsbereik (UU) waarbinnen de bot de EFC-threat-straf
 *       voelt; proximityFactor = max(0, 1 - botToEfc / range). Buiten bereik → 0 (geen ruis).</li>
 * </ul>
 */
public record ObjectiveProgressParams(
    RewardMetadata metadata,
    double progressScale,
    double aliveBonus,
    double ownFlagReturnProgressScale,
    double carrierProgressScale,
    double carrierProximityBonus,
    double carrierProximityRadiusUu,
    double efcEngagementRangeUu,
    double efcThreatProgressScale,
    double efcThreatProximityRangeUu)
    implements RewardBlock {

  public ObjectiveProgressParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ObjectiveProgressParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return progressScale != 0.0 || aliveBonus != 0.0 || ownFlagReturnProgressScale != 0.0
        || carrierProgressScale != 0.0 || carrierProximityBonus != 0.0;
  }
}
