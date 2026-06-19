package aiplay.rl;

/**
 * Per-skill reward decompositie voor het joint {@code rl_pawn} model
 * (vr-shooting-sac-merge.md sectie 7.5 commitment 3). Splitst elke tick-reward
 * in zes skill-kanalen (movement / view / pitch / fire / altFire / team_assist)
 * plus een residual voor events die niet aan één specifieke skill toe te wijzen
 * zijn.
 *
 * <p>Team_assist is de Fase 2.5 CTDE-uitbreiding (zie
 * {@code docs/policy/team-coordination-rollout.md}): aparte gradient-channel voor
 * team-coordination rewards (team_captured_assist + team_returned_assist +
 * carrier_kill_assist + escort_proximity_dense uit
 * {@code TeamAssistReward}). Wanneer alleen rewardgroup-overrides actief zijn
 * blijft dit kanaal nul.
 *
 * <p><b>Invariant:</b>
 * {@code scalar = rewardMovement + rewardView + rewardPitch + rewardFire + rewardAltFire + rewardTeamAssist + rewardResidual}.
 * Bij het opbouwen van decomp komen alle component-bijdragen <em>al
 * gewogen</em> (per-component {@code *_weight} uit joint rewards.json) in een
 * skill-kanaal terecht; er is geen tweede normalisatie- of weeg-stap op de
 * decomp zelf.</p>
 *
   * <p>Decoupled modellen schrijven {@link #zero(double)} — alle zes de
   * skill-velden nul, scalar = legacy reward — zodat een gedeelde NPZ
 * writer pad geen regressie veroorzaakt voor bestaande SAC kernels.</p>
 */
public record RewardDecomposition(
    double rewardMovement,
    double rewardView,
    double rewardPitch,
    double rewardFire,
    double rewardAltFire,
    double rewardTeamAssist,
    double rewardResidual,
    double rewardScalar
) {

  /** Floating-point tolerantie voor de invariant {@code scalar ≈ Σ kanalen + residual}. */
  public static final double INVARIANT_EPSILON = 1e-9;

  /**
   * Decoupled-mode constructor — alle skill-kanalen leeg, residual draagt de
   * volledige scalar zodat de invariant blijft kloppen zonder per-skill
   * attributie te dwingen op modellen die er geen baat bij hebben.
   */
  public static RewardDecomposition zero(double scalar) {
    return new RewardDecomposition(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, scalar, scalar);
  }

  /** Som van alleen de zes skill-kanalen (zonder residual). Handig voor logging. */
  public double skillTotal() {
    return rewardMovement + rewardView + rewardPitch + rewardFire + rewardAltFire
        + rewardTeamAssist;
  }

  /**
   * Controleert de invariant {@code scalar ≈ skillTotal + residual} binnen
   * {@link #INVARIANT_EPSILON}. Bedoeld voor tests en debug-asserts; productie
   * code moet geen invariant-fouten verwachten omdat alle constructie via
   * {@link aiplay.rl.JointRewardDecompositionStrategy} verloopt.
   */
  public boolean isInvariantValid() {
    double expected = skillTotal() + rewardResidual;
    return Math.abs(rewardScalar - expected) <= INVARIANT_EPSILON;
  }
}
