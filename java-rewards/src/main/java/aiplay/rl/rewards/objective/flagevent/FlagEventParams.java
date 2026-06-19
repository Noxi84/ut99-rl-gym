package aiplay.rl.rewards.objective.flagevent;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Sparse rewards rond CTF flag-events: pakken, droppen, scoren (zelf), team-capture (door
 * teamgenoot), enemy-capture (vijand-team scoort onze vlag), het terugbrengen van de eigen vlag
 * (zelf), en team-return (door teamgenoot).
 *
 * <p>De zeven weights worden onafhankelijk getriggerd door state-deltas
 * ({@code prev.hasFlag → curr.hasFlag}, {@code prev.score → curr.score}) plus de
 * {@code bHome}-transitie op de eigen vlag voor returned/team_returned/enemy_captured en op de
 * enemy vlag voor team_captured. Self vs team-attribution gebruikt
 * {@code FlagDto.lastReturnInstigatorSlot}, dat door de UC mutator (RLCTFGame.ScoreFlag →
 * RLUdpStateSender.RecordFlagReturn) per state-frame wordt gezet — exact dezelfde
 * instigator-attribution die DamageDeltaReward gebruikt. {@code taken}, {@code captured},
 * {@code team_captured}, {@code team_returned} en {@code enemy_captured} worden geschaald met
 * {@code RewardUtils.timeMultiplier} om laat-in-match events iets minder te belonen dan vroege.
 *
 * <p>{@code team_captured} en {@code captured} zijn mutually exclusive per tick: de capper krijgt
 * captured, alle andere teamgenoten krijgen team_captured. Idem voor {@code team_returned} vs
 * {@code returned}: de speler die de vlag aanraakte krijgt {@code returned}, alle andere
 * teamgenoten krijgen {@code team_returned}. Auto-returns (timeout zonder scorer) leveren niemand
 * een reward op — instigator slot is {@code -1} en zowel self- als team-checks falen.
 *
 * <p>{@code enemy_captured} (negatief gebruikt) detecteert de bHome 0→1 transitie op de eigen
 * vlag waarbij geen return-instigator gecredit is én de vlag in prev een carrier had — dit
 * onderscheidt enemy-cap van een auto-return na drop-timeout (die heeft hasHolder=false in
 * prev). Mutually exclusive met {@code returned} en {@code team_returned} op dezelfde transitie:
 * een return vereist {@code lastReturnInstigatorSlot >= 0}.
 *
 * <p>De rolverdeling stemt het verschil: Attacker hoge captured / lage team_captured / lage
 * team_returned + milde enemy_captured penalty (focus op offense, niet defense-faal); Cover middel
 * returned / hoog team_returned + team_captured + zware enemy_captured (escort + recovery moeten
 * intercept); Defender hoog returned + hoog team_returned + zwaarste enemy_captured (failed-job-
 * penalty, symmetrisch met flag_carrier_kill.near_base_bonus); DeathMatch nul.
 */
public record FlagEventParams(
    RewardMetadata metadata,
    double taken,
    double dropped,
    double captured,
    double teamCaptured,
    double enemyCaptured,
    double returned,
    double teamReturned)
    implements RewardBlock {

  public FlagEventParams {
    if (metadata == null) {
      throw new IllegalArgumentException("FlagEventParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return taken != 0.0 || dropped != 0.0 || captured != 0.0
        || teamCaptured != 0.0 || enemyCaptured != 0.0
        || returned != 0.0 || teamReturned != 0.0;
  }
}
