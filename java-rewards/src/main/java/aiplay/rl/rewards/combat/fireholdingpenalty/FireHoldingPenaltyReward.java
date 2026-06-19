package aiplay.rl.rewards.combat.fireholdingpenalty;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.LeadAimUtils;
import aiplay.dto.PlayerDto;

/**
 * Continuous per-tick penalty toegepast zolang een fire-knop ingedrukt is en de aim_score onder de
 * min-threshold ligt (geen target zichtbaar / mis-gemikt). Werkt symmetrisch voor zowel primary
 * ({@code fireActive}) als secondary ({@code altFireActive}) — zonder die symmetrie ontsnapt
 * alt-fire grenade-spam aan de holding penalty en blijft een lokaal optimum dominant.
 *
 * <p>Vult de event-getriggerde {@code shot_off_target_penalty} aan: die laatste geeft één puls op
 * de rising edge van fire(Active|Alt), deze geeft sustained pressure tegen vasthouden van de
 * fire-knop richting muren / niets.
 *
 * <p>Wanneer beide modes tegelijk actief zijn (zou via de decoder mutex niet gebeuren) worden de
 * penalties additief — dat straft "alle knoppen ingedrukt" extra.
 *
 * <p>Threshold gebruikt dezelfde {@code shot_min_aim_score} als {@link CombatEventReward} — single
 * source of truth voor "is dit een goed shot".
 */
public class FireHoldingPenaltyReward implements RewardComponent {

  private final FireHoldingPenaltyParams params;

  public FireHoldingPenaltyReward(FireHoldingPenaltyParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "FireHoldingPenaltyReward requires non-null FireHoldingPenaltyParams");
    }
    this.params = params;
  }

  public record Result(double primary, double alt) {
    public double total() { return primary + alt; }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    double penalty = params.perTickPenalty();
    if (penalty == 0.0) return new Result(0.0, 0.0);

    PlayerDto curr = ctx.curr().playerPawn;
    if (curr == null) return new Result(0.0, 0.0);

    boolean primary = curr.fireActive;
    boolean alt = curr.altFireActive;
    if (!primary && !alt) return new Result(0.0, 0.0);

    // Per-mode aim score: alt fire = slug speed (1200), primary = chunks (2700).
    // Bij beide actief (zou via decoder mutex niet gebeuren) gebruiken we de strengste
    // van twee — als één van beide on-target is, geen penalty.
    double primaryAim = primary ? LeadAimUtils.computeAimScore3D(
        ctx.curr(), ctx.modelTargetIndex(),
        ctx.config().getProjectileSpeedFlakPrimaryUu(),
        ctx.config().getProjectileSpeedFlakSecondaryUu(),
        ctx.config().getRocketPrimaryAimTargetHeightUu(),
        ctx.config().getSniperPrimaryAimTargetHeightUu(),
        false) : 0.0;
    double altAim = alt ? LeadAimUtils.computeAimScore3D(
        ctx.curr(), ctx.modelTargetIndex(),
        ctx.config().getProjectileSpeedFlakPrimaryUu(),
        ctx.config().getProjectileSpeedFlakSecondaryUu(),
        ctx.config().getRocketPrimaryAimTargetHeightUu(),
        ctx.config().getSniperPrimaryAimTargetHeightUu(),
        true) : 0.0;
    double aimScore = Math.max(primaryAim, altAim);
    if (aimScore >= params.minAimScore()) {
      return new Result(0.0, 0.0);
    }

    return new Result(primary ? penalty : 0.0, alt ? penalty : 0.0);
  }
}
