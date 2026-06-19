package aiplay.rl.rewards.objective.flagcarrierkill;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.FlagDto;
import aiplay.dto.PlayerDto;

/**
 * Sparse bonus when a frag this tick coincides with our own flag transitioning carried→dropped —
 * i.e. we (likely) just killed the enemy carrier. The proximity term scales linearly with how close
 * the carrier was to scoring at the moment of death: full proximity bonus when they died at the
 * enemy base (would-have-scored), zero at distance ≥ near_base_max_distance_uu.
 *
 * <p>Attribution heuristic: we trigger when both signals (our score++) and (own flag dropped)
 * coincide in the same tick. False positives are possible (someone else killed them while our score
 * independently incremented from a different frag), but rare enough that the reward signal stays
 * net-correct. No exact damage attribution because the binary UDP transport doesn't carry per-shot
 * damage events.
 */
public class FlagCarrierKillReward implements RewardComponent {

  private final FlagCarrierKillParams params;

  public FlagCarrierKillReward(FlagCarrierKillParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "FlagCarrierKillReward requires non-null FlagCarrierKillParams");
    }
    this.params = params;
  }

  public record Result(double carrierKill, double carrierKillNearBase) {
    public double total() {
      return carrierKill + carrierKillNearBase;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    double bonus = params.bonus();
    double proxBonus = params.nearBaseBonus();
    if (bonus == 0.0 && proxBonus == 0.0) return new Result(0.0, 0.0);

    PlayerDto prevPawn = ctx.prev().playerPawn;
    PlayerDto currPawn = ctx.curr().playerPawn;
    if (prevPawn == null || currPawn == null) return new Result(0.0, 0.0);

    // Frag this tick — eigen frags-counter (mutator-immune, zie CombatEventReward.frag).
    // PRI.Score-delta was kwetsbaar voor SmartCTF returns/covers/seals/assists die
    // ook score laten stijgen zonder dat er een frag was.
    if (currPawn.frags <= prevPawn.frags) return new Result(0.0, 0.0);

    // Our own flag must have just transitioned carried→dropped (not returned to base)
    int team = currPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;
    if (prevOwnFlag == null || currOwnFlag == null) return new Result(0.0, 0.0);
    if (!prevOwnFlag.hasHolder) return new Result(0.0, 0.0);
    if (currOwnFlag.hasHolder) return new Result(0.0, 0.0);
    if (currOwnFlag.bHome) return new Result(0.0, 0.0);

    double carrierKill = bonus;
    double carrierKillNearBase = 0.0;

    if (proxBonus != 0.0 && currOwnFlag.location != null) {
      // Carrier would have scored by reaching the ENEMY base (their own flag's
      // base location is where they capture). Distance from drop-point to
      // that base = how close they were to scoring.
      FlagDto enemyFlag = (team == 0) ? ctx.curr().blueFlag : ctx.curr().redFlag;
      if (enemyFlag != null && enemyFlag.baseLocation != null) {
        double dx = currOwnFlag.location.x - enemyFlag.baseLocation.x;
        double dy = currOwnFlag.location.y - enemyFlag.baseLocation.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double maxDist = params.nearBaseMaxDistanceUu();
        if (maxDist > 0.0) {
          double proximityNorm = Math.max(0.0, 1.0 - dist / maxDist);
          carrierKillNearBase = proxBonus * proximityNorm;
        }
      }
    }

    return new Result(carrierKill, carrierKillNearBase);
  }
}
