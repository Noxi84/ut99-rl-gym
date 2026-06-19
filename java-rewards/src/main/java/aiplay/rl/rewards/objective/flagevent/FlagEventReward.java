package aiplay.rl.rewards.objective.flagevent;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

/**
 * Sparse flag-related rewards: flag taken, dropped, captured (self), team_captured (teammate
 * capper), enemy_captured (enemy team scoort onze vlag), returned (self), team_returned
 * (teammate returner).
 *
 * <p>Self vs team attribution voor returns leunt op {@code FlagDto.lastReturnInstigatorSlot},
 * gevuld door {@code RLCTFGame.ScoreFlag} → {@code RLUdpStateSender.RecordFlagReturn}. Een
 * auto-return (timeout zonder scorer) heeft slot {@code -1} en levert niemand een reward op.
 *
 * <p>{@code enemy_captured} detectie: onze vlag transitie {@code bHome 0→1} terwijl er geen
 * return-instigator is gecredit ({@code lastReturnInstigatorSlot < 0}) én de vlag een carrier
 * had in prev ({@code hasHolder=true}). Een auto-return na drop-timeout heeft
 * {@code prev.hasHolder=false} en wordt zo correct uitgesloten. Mutually exclusive met
 * {@link #computeFlagReturned} en {@link #computeFlagTeamReturned} (die slot {@code >= 0}
 * vereisen).
 */
public class FlagEventReward implements RewardComponent {

  private final FlagEventParams params;

  public FlagEventReward(FlagEventParams params) {
    if (params == null) {
      throw new IllegalArgumentException("FlagEventReward requires non-null FlagEventParams");
    }
    this.params = params;
  }

  public record Result(
      double flagTaken,
      double flagDropped,
      double flagCaptured,
      double flagTeamCaptured,
      double flagEnemyCaptured,
      double flagReturned,
      double flagTeamReturned) {
    public double total() {
      return flagTaken + flagDropped + flagCaptured + flagTeamCaptured
          + flagEnemyCaptured + flagReturned + flagTeamReturned;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    PlayerDto prevPawn = ctx.prev().playerPawn;
    PlayerDto currPawn = ctx.curr().playerPawn;

    double flagTaken =
        (!prevPawn.hasFlag && currPawn.hasFlag)
            ? params.taken() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config())
            : 0.0;

    double flagDropped =
        (prevPawn.hasFlag && !currPawn.hasFlag && currPawn.health > 0) ? params.dropped() : 0.0;

    // Cap-detectie via eigen flagsCaptured-counter (gevuld door RLCTFGame.ScoreFlag
    // → RLUdpStateSender.RecordFlagCapture). PRI.Score-delta + hasFlag was kwetsbaar
    // voor score-mutators: SmartCTF voegt cap-bonus 8 + assist 7 + cover/seal 2 toe
    // aan PRI.Score, en bij een return-tijdens-flag-overdracht zou de oude detectie
    // false-positive triggeren. Eigen counter is mutator-immune.
    double flagCaptured =
        (currPawn.flagsCaptured > prevPawn.flagsCaptured)
            ? params.captured() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config())
            : 0.0;

    double flagTeamCaptured = computeTeamCaptured(ctx, prevPawn);

    double flagEnemyCaptured = computeFlagEnemyCaptured(ctx);
    double flagReturned = computeFlagReturned(ctx);
    double flagTeamReturned = computeFlagTeamReturned(ctx);

    return new Result(flagTaken, flagDropped, flagCaptured, flagTeamCaptured,
        flagEnemyCaptured, flagReturned, flagTeamReturned);
  }

  /**
   * Team capture event: enemy flag was being carried by a teammate in prev and is now home in
   * curr, while self was not the carrier (otherwise that's the personal capture). Detected via
   * the enemy flag's bHome 0→1 transition combined with a teammate having had {@code hasFlag}
   * in prev.
   */
  private double computeTeamCaptured(RewardContext ctx, PlayerDto prevPawn) {
    if (prevPawn.hasFlag) {
      return 0.0; // self is the capper — counted by flagCaptured
    }
    int team = ctx.curr().playerPawn.team;
    FlagDto prevEnemyFlag = (team == 0) ? ctx.prev().blueFlag : ctx.prev().redFlag;
    FlagDto currEnemyFlag = (team == 0) ? ctx.curr().blueFlag : ctx.curr().redFlag;
    if (prevEnemyFlag == null || currEnemyFlag == null) {
      return 0.0;
    }
    boolean wasNotHome = !prevEnemyFlag.bHome;
    boolean nowHome = currEnemyFlag.bHome;
    if (!(wasNotHome && nowHome)) {
      return 0.0;
    }
    if (!teammateHadEnemyFlag(ctx.prev())) {
      return 0.0;
    }
    return params.teamCaptured() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config());
  }

  private static boolean teammateHadEnemyFlag(GameStateDto state) {
    if (state.teammates == null) return false;
    for (PlayerDto t : state.teammates) {
      if (t != null && t.hasFlag) return true;
    }
    return false;
  }

  /**
   * Enemy capture: onze eigen vlag bHome 0→1 transitie zonder dat een teamgenoot of wijzelf de
   * return touchten ({@code lastReturnInstigatorSlot < 0}) én er was een carrier in prev. Dat
   * laatste sluit auto-returns na drop-timeout uit (die hebben {@code prev.hasHolder=false}). Een
   * vlag wordt nooit door een teammate gedragen — alleen het andere team — dus
   * {@code prev.hasHolder=true} impliceert dat de carrier een enemy was, en de bHome-transitie
   * met afwezige return-credit impliceert {@code RLCTFGame.ScoreFlag} capture-branch.
   * Mutually exclusive met {@link #computeFlagReturned} en {@link #computeFlagTeamReturned}.
   */
  private double computeFlagEnemyCaptured(RewardContext ctx) {
    int team = ctx.curr().playerPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;
    if (prevOwnFlag == null || currOwnFlag == null) {
      return 0.0;
    }
    if (!(!prevOwnFlag.bHome && currOwnFlag.bHome)) {
      return 0.0;
    }
    if (currOwnFlag.lastReturnInstigatorSlot >= 0) {
      return 0.0;
    }
    if (!prevOwnFlag.hasHolder) {
      return 0.0;
    }
    return params.enemyCaptured() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config());
  }

  /**
   * Self-return: own-flag bHome 0→1 EN instigator slot matcht eigen pawn slot. Auto-returns
   * (slot == -1) en teammate-returns (slot != self) leveren 0 op via dit pad.
   */
  private double computeFlagReturned(RewardContext ctx) {
    int team = ctx.curr().playerPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;

    if (prevOwnFlag == null || currOwnFlag == null) {
      return 0.0;
    }
    if (!(!prevOwnFlag.bHome && currOwnFlag.bHome)) {
      return 0.0;
    }
    int instSlot = currOwnFlag.lastReturnInstigatorSlot;
    int selfSlot = ctx.curr().playerPawn.slot;
    if (instSlot < 0 || selfSlot < 0 || instSlot != selfSlot) {
      return 0.0;
    }
    return params.returned();
  }

  /**
   * Team-return: own-flag bHome 0→1 EN instigator is een teamgenoot (slot >= 0 en niet self).
   * Mutually exclusive met {@link #computeFlagReturned}. Auto-returns (slot == -1) leveren 0 op.
   */
  private double computeFlagTeamReturned(RewardContext ctx) {
    int team = ctx.curr().playerPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;

    if (prevOwnFlag == null || currOwnFlag == null) {
      return 0.0;
    }
    if (!(!prevOwnFlag.bHome && currOwnFlag.bHome)) {
      return 0.0;
    }
    int instSlot = currOwnFlag.lastReturnInstigatorSlot;
    int selfSlot = ctx.curr().playerPawn.slot;
    if (instSlot < 0 || instSlot == selfSlot) {
      return 0.0;
    }
    return params.teamReturned() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config());
  }
}
