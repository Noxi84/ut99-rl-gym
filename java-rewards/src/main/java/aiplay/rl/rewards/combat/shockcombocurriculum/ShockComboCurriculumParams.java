package aiplay.rl.rewards.combat.shockcombocurriculum;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense PBRS-curriculum voor de shock-combo. Potential Φ(s) = max over eigen live
 * {@code Botpack.ShockProj} van {@code beamProximity·enemyProximity} (hoe dicht de primary-beam bij de
 * eigen bal is × hoe dicht die bal bij een enemy is). De reward is de per-frame delta
 * {@code Φ(curr)−Φ(prev)}, geclipt en alleen bij continuïteit (beide frames een combo-relevante bal) —
 * zo beloont het de PROGRESSIE naar een geslaagde detonatie zonder de voltooiing (bal verdwijnt →
 * Φ valt) af te straffen of een bal-spawn/-wissel te laten farmen.
 */
public record ShockComboCurriculumParams(
    RewardMetadata metadata,
    /** Globale schaal van de shaping-delta. */
    double weight,
    /** σ (UU) voor beamProximity = exp(−beamMissDistance/σ). Klein → alleen echte bijna-treffers van de
     *  beam op de bal tellen (fijne precisie — de ontbrekende stap t.o.v. de grove cos-drempel van
     *  ShockComboAimReward). */
    double beamSigmaUu,
    /** σ (UU) voor enemyProximity = exp(−ballEnemyDist/σ). Schaalt op de combo-splash-context. */
    double enemySigmaUu,
    /** Max bal→enemy-afstand (UU) waarbinnen een bal combo-relevant is; daarbuiten levert hij geen Φ. */
    double maxBallEnemyRangeUu,
    /** Clip op |Φ(curr)−Φ(prev)| per frame; dempt sprongen door bal-wissel of resterende
     *  discontinuïteit tot een plausibele single-frame-verbetering. */
    double maxDeltaPerFrame)
    implements RewardBlock {

  public ShockComboCurriculumParams {
    if (metadata == null) {
      throw new IllegalArgumentException("ShockComboCurriculumParams.metadata required");
    }
  }

  @Override
  public boolean enabled() {
    return weight != 0.0;
  }
}
