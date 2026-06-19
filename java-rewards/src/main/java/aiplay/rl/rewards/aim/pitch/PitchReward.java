package aiplay.rl.rewards.aim.pitch;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.shared.view.EnemySpawnTargeting;
import aiplay.shared.view.FireModeAimTargeting;
import aiplay.shared.view.ViewTargeting;

/**
 * Dense pitch alignment reward: vertical aim toward the active attention target. Engagement-gated
 * (alleen actief bij ENEMY_* attention) zodat de bot vrij naar pad/objective kan kijken wanneer
 * niet engaged. Lineaire shape + acquisition delta + extreme-pitch penalty.
 */
public class PitchReward implements RewardComponent {

  private final PitchParams params;

  public PitchReward(PitchParams params) {
    if (params == null) {
      throw new IllegalArgumentException("PitchReward requires non-null PitchParams");
    }
    this.params = params;
  }

  @Override
  public double compute(RewardContext ctx) {
    GameStateDto curr = ctx.curr();
    if (curr.playerPawn == null || curr.playerPawn.health <= 0) {
      return 0.0;
    }
    // Curriculum multiplier scales only the heuristic alignment cost; PBRS acquisition and the
    // extreme-pitch ceiling penalty stay full strength so we never lose the safety rail or the
    // dense convergence signal — only the prescription-pull weakens as the bot matures.
    double effectiveAlignmentWeight =
        params.alignmentWeight() * ctx.config().getPitchAlignmentCurriculumMultiplier();
    if (effectiveAlignmentWeight == 0.0
        && params.acquisitionBonus() == 0.0
        && params.extremePenalty() == 0.0) {
      return 0.0;
    }
    if (curr.playerPawn.viewRotation == null) {
      return 0.0;
    }

    double currentPitchNorm = ViewTargeting.extractCurrentPitchNorm(curr);

    // Resolve the active pitch target. Besides enemy/fire-mode aim, all-enemies-dead frames get
    // a spawn aimpoint so pitch does not drift to the vertical extremes while yaw looks for work.
    double desiredPitchNorm = resolveDesiredPitchNorm(
        curr,
        ctx.modelTargetIndex(),
        ctx.config().getProjectileSpeedFlakPrimaryUu(),
        ctx.config().getProjectileSpeedFlakSecondaryUu(),
        ctx.config().getRocketPrimaryAimTargetHeightUu(),
        ctx.config().getSniperPrimaryAimTargetHeightUu());
    boolean hasPitchTarget = Double.isFinite(desiredPitchNorm);

    double reward = 0.0;

    if (hasPitchTarget) {
      double pitchErrorNorm = Math.abs(desiredPitchNorm - currentPitchNorm);

      // Lineaire negatieve cost — symmetrisch met yaw cos² in gradient-magnitude rondom 0.
      reward += -effectiveAlignmentWeight * pitchErrorNorm;

      // Potential-based acquisition: beloont reductie van |pitchError| tussen prev en curr.
      // Verandert de optimal policy niet (Ng et al. 1999) maar geeft een dichte gradient.
      double acqBonus = params.acquisitionBonus();
      if (acqBonus != 0.0
          && ctx.prev() != null
          && ctx.prev().playerPawn != null
          && ctx.prev().playerPawn.viewRotation != null
          && ctx.prev().playerPawn.location != null
          && hasAnyPitchTarget(ctx.prev())) {
        double prevDesired = resolveDesiredPitchNorm(
            ctx.prev(),
            ctx.modelTargetIndex(),
            ctx.config().getProjectileSpeedFlakPrimaryUu(),
            ctx.config().getProjectileSpeedFlakSecondaryUu(),
            ctx.config().getRocketPrimaryAimTargetHeightUu(),
            ctx.config().getSniperPrimaryAimTargetHeightUu());
        if (Double.isFinite(prevDesired)) {
          double prevCurrent = ViewTargeting.extractCurrentPitchNorm(ctx.prev());
          double prevErr = Math.abs(prevDesired - prevCurrent);
          reward += acqBonus * (prevErr - pitchErrorNorm);
        }
      }
    }

    // Extreme-pitch penalty: ungated. Threshold widens around the engagement target's
    // natural pitch (so aiming up/down at a high/low enemy is still allowed). When not
    // engaged, the threshold falls back to the static neutral threshold — anything past
    // that is sustained off-distribution and punished, breaking the "always look down"
    // mode-collapse path.
    if (params.extremePenalty() != 0.0) {
      double allowedExtreme = params.extremeThresholdNorm();
      if (hasPitchTarget) {
        allowedExtreme = Math.max(
            allowedExtreme,
            Math.abs(desiredPitchNorm) + params.extremeTargetMarginNorm());
      }
      double excessivePitch = Math.max(0.0, Math.abs(currentPitchNorm) - allowedExtreme);
      reward += params.extremePenalty() * excessivePitch;
    }

    return reward;
  }

  private static double resolveDesiredPitchNorm(
      GameStateDto frame, int targetIndex, double flakPrimary, double flakSecondary,
      double rocketPrimaryAimTargetHeightUu, double sniperPrimaryAimTargetHeightUu) {
    if (frame == null || frame.playerPawn == null) {
      return Double.NaN;
    }

    // Always compute the weapon-conditional fire-mode aim target — the bot needs to maintain
    // correct aim for the next shot during cooldown frames too. Rocket primary fire is bFire=true
    // only ~7% of the time (100 ms animation in a 1.5 s cycle); the old code path only honored
    // the weapon-specific target during those few frames and fell back to enemy-eye for the
    // remaining 93%, which produced a contradictory pitch gradient. altFire selection follows
    // the actual altFireActive state (grenade lob target); otherwise we default to primary fire
    // mode so the target stays continuous across the cooldown.
    boolean altFireSelect = frame.playerPawn.altFireActive;
    boolean fireSelect = !altFireSelect;
    double weaponConditionalPitch = FireModeAimTargeting.computeSelectedPitchNorm(
        frame,
        targetIndex,
        flakPrimary,
        flakSecondary,
        rocketPrimaryAimTargetHeightUu,
        sniperPrimaryAimTargetHeightUu,
        fireSelect,
        altFireSelect);
    if (Double.isFinite(weaponConditionalPitch)) {
      return weaponConditionalPitch;
    }

    // Fallback: no usable enemy resolvable by FireModeAimTargeting. Honor the engagement layer's
    // attention target — typically an OBJECTIVE_* (flag location) since ENEMY_* would have given
    // FireModeAimTargeting a usable enemy above.
    if (RewardUtils.isEnemyAttentionTarget(frame)) {
      int playerTeam = frame.playerPawn.team;
      FlagDto ownFlag = (playerTeam == 1) ? frame.blueFlag : frame.redFlag;
      FlagDto enemyFlag = (playerTeam == 1) ? frame.redFlag : frame.blueFlag;
      CoordinatesDto enemyTarget = ViewTargeting.resolveAttentionTarget(frame, ownFlag, enemyFlag);
      if (enemyTarget != null) {
        return ViewTargeting.computePitchNormToward(frame, enemyTarget);
      }
    }

    CoordinatesDto spawnTarget = frame.annotatedEnemySpawnTarget;
    if (spawnTarget == null) {
      spawnTarget = EnemySpawnTargeting.resolveAimPoint(frame);
    }
    if (spawnTarget != null) {
      return ViewTargeting.computePitchNormToward(frame, spawnTarget);
    }

    return Double.NaN;
  }

  private static boolean hasAnyPitchTarget(GameStateDto frame) {
    return frame != null
        && frame.playerPawn != null
        && (frame.playerPawn.fireActive
            || frame.playerPawn.altFireActive
            || RewardUtils.isEnemyAttentionTarget(frame)
            || frame.annotatedEnemySpawnTarget != null
            || EnemySpawnTargeting.hasAllEnemiesDead(frame));
  }
}
