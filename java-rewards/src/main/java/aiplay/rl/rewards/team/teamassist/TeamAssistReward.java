package aiplay.rl.rewards.team.teamassist;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.rl.rewards.catalog.EndgameUrgencyParams;
import aiplay.rl.rewards.team.endgame.EndgameUrgency;

/**
 * Team-coordination shaping for the 6th critic head ({@code team_assist}). Owns five sub-
 * components that all decompose to the same head:
 *
 * <ol>
 *   <li>Team-captured assist — fires on the tick a teammate captures the enemy flag (same edge
 *       {@link aiplay.rl.rewards.objective.FlagEventReward#computeTeamCaptured} detects: enemy flag {@code bHome 0→1} with a
 *       teammate having had {@code hasFlag} in prev) and the bot is within
 *       {@code assistRadiusUu} of the bot's own flag base. Captures happen at the bot's home
 *       base, so proximity-to-own-base is the operational stand-in for "near the capture event".
 *   <li>Team-returned assist — fires when our own flag transitions {@code bHome 0→1} with a
 *       teammate as instigator (slot &ge; 0 AND != self) and the bot is within
 *       {@code assistRadiusUu} of the own flag base (= return location).
 *   <li>Carrier-kill assist — fires when our own flag transitions carried→dropped (= enemy carrier
 *       just died) but this bot did NOT frag this tick. Bot must be within
 *       {@code killAssistRadiusUu} of the drop site. The killer themselves earns
 *       {@link aiplay.rl.rewards.objective.FlagCarrierKillReward}; this branch rewards nearby help.
 *   <li>Escort-proximity dense — per-tick reward scaling linearly with how close the bot is to
 *       any living NON-carrier teammate, capped at {@code escortDenseRangeUu}. Defaults to zero in
 *       every rewardgroup; only the Cover group should set it to non-zero. The teammate-carrier is
 *       excluded (2026-06-06): the carry phase belongs exclusively to {@code cover_escort} (with
 *       its escort-standoff), so the two ramps never stack onto the carrier and glue the escort
 *       against him.
 *   <li>Endgame attack bonus — per-tick dense reward = {@code endgameAttackBonus × urgency ×
 *       clamp01(botDepthInEnemyHalf)} where {@code urgency} comes from
 *       {@link EndgameUrgency#urgency}. Non-zero only for the Defend/Cover rolegroups: when the
 *       team is behind in the closing minutes of the match, these roles should peel off camping
 *       and escort and join the attack. Pushing into enemy territory becomes positive instead of
 *       penalised (combined with the urgency-modulator on {@link DefenderPresenceReward} and
 *       {@link CoverEscortReward} which damp the camping/escort signal in the same window).
 * </ol>
 *
 * <p>The sub-component details are kept around as a {@code Result} so {@link RewardComputer} can
 * log them for debugging, but downstream the {@code RewardBreakdown} only carries a single
 * {@code teamAssist} scalar — the 6th critic head consumes one merged channel.
 */
public class TeamAssistReward implements RewardComponent {

  private final TeamAssistParams params;
  private final EndgameUrgencyParams endgameParams;

  public TeamAssistReward(TeamAssistParams params, EndgameUrgencyParams endgameParams) {
    if (params == null) {
      throw new IllegalArgumentException("TeamAssistReward requires non-null TeamAssistParams");
    }
    if (endgameParams == null) {
      throw new IllegalArgumentException(
          "TeamAssistReward requires non-null EndgameUrgencyParams");
    }
    this.params = params;
    this.endgameParams = endgameParams;
  }

  public record Result(
      double teamCapturedAssist,
      double teamReturnedAssist,
      double carrierKillAssist,
      double escortProximityDense,
      double endgameAttackBonus) {
    public double total() {
      return teamCapturedAssist + teamReturnedAssist + carrierKillAssist + escortProximityDense
          + endgameAttackBonus;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    if (!params.enabled()) {
      return new Result(0.0, 0.0, 0.0, 0.0, 0.0);
    }
    PlayerDto currPawn = ctx.curr().playerPawn;
    PlayerDto prevPawn = ctx.prev().playerPawn;
    if (currPawn == null || currPawn.location == null || currPawn.health <= 0) {
      return new Result(0.0, 0.0, 0.0, 0.0, 0.0);
    }
    if (prevPawn == null) {
      return new Result(0.0, 0.0, 0.0, 0.0, 0.0);
    }

    double captured = computeTeamCapturedAssist(ctx, prevPawn, currPawn);
    double returned = computeTeamReturnedAssist(ctx, currPawn);
    double carrierKill = computeCarrierKillAssist(ctx, prevPawn, currPawn);
    double escort = computeEscortProximityDense(ctx, currPawn);
    double endgame = computeEndgameAttackBonus(ctx);

    return new Result(captured, returned, carrierKill, escort, endgame);
  }

  private double computeTeamCapturedAssist(RewardContext ctx, PlayerDto prevPawn,
      PlayerDto currPawn) {
    if (params.teamCapturedAssist() == 0.0) {
      return 0.0;
    }
    if (prevPawn.hasFlag) {
      return 0.0; // self is the capper — counted by flag_event.captured
    }
    int team = currPawn.team;
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

    FlagDto ownFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;
    if (ownFlag == null || ownFlag.baseLocation == null) {
      return 0.0;
    }
    double dist = RewardUtils.distance(currPawn.location, ownFlag.baseLocation);
    if (dist > params.assistRadiusUu()) {
      return 0.0;
    }
    return params.teamCapturedAssist() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config());
  }

  private double computeTeamReturnedAssist(RewardContext ctx, PlayerDto currPawn) {
    if (params.teamReturnedAssist() == 0.0) {
      return 0.0;
    }
    int team = currPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;
    if (prevOwnFlag == null || currOwnFlag == null) {
      return 0.0;
    }
    if (!(!prevOwnFlag.bHome && currOwnFlag.bHome)) {
      return 0.0;
    }
    int instSlot = currOwnFlag.lastReturnInstigatorSlot;
    int selfSlot = currPawn.slot;
    if (instSlot < 0 || instSlot == selfSlot) {
      return 0.0; // auto-return or self-return
    }
    if (currOwnFlag.baseLocation == null) {
      return 0.0;
    }
    double dist = RewardUtils.distance(currPawn.location, currOwnFlag.baseLocation);
    if (dist > params.assistRadiusUu()) {
      return 0.0;
    }
    return params.teamReturnedAssist() * RewardUtils.timeMultiplier(ctx.curr(), ctx.config());
  }

  private double computeCarrierKillAssist(RewardContext ctx, PlayerDto prevPawn,
      PlayerDto currPawn) {
    if (params.carrierKillAssist() == 0.0) {
      return 0.0;
    }
    // Self-kill credits to FlagCarrierKillReward — exclude here.
    if (currPawn.frags > prevPawn.frags) {
      return 0.0;
    }
    int team = currPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? ctx.prev().redFlag : ctx.prev().blueFlag;
    FlagDto currOwnFlag = (team == 0) ? ctx.curr().redFlag : ctx.curr().blueFlag;
    if (prevOwnFlag == null || currOwnFlag == null) {
      return 0.0;
    }
    // Own flag transitioned carried→dropped (not returned home) — same edge as
    // FlagCarrierKillReward but without the self-frag gate.
    if (!prevOwnFlag.hasHolder) return 0.0;
    if (currOwnFlag.hasHolder) return 0.0;
    if (currOwnFlag.bHome) return 0.0;
    if (currOwnFlag.location == null) {
      return 0.0;
    }
    double dist = RewardUtils.distance(currPawn.location, currOwnFlag.location);
    if (dist > params.killAssistRadiusUu()) {
      return 0.0;
    }
    return params.carrierKillAssist();
  }

  private double computeEscortProximityDense(RewardContext ctx, PlayerDto currPawn) {
    if (params.escortProximityDense() == 0.0) {
      return 0.0;
    }
    // De teammate-CARRIER telt niet mee als target (2026-06-06): dit is het open-field signaal;
    // de carry-fase is exclusief cover_escort (mét escort-standoff). Vóór deze uitsluiting
    // stapelden beide ramps op dezelfde carrier — samen met de objective_progress-pull was
    // "sta ÍN de carrier" het optimum, en de escort duwde/blokkeerde de carrier fysiek op zijn
    // capture-run (pawns botsen).
    PlayerDto closest = findClosestLivingNonCarrierTeammate(ctx.curr(), currPawn.location);
    if (closest == null || closest.location == null) {
      return 0.0;
    }
    double dist = RewardUtils.distance(currPawn.location, closest.location);
    double range = params.escortDenseRangeUu();
    if (dist >= range) {
      return 0.0;
    }
    double scale = 1.0 - (dist / range);
    return params.escortProximityDense() * scale;
  }

  private double computeEndgameAttackBonus(RewardContext ctx) {
    if (params.endgameAttackBonus() == 0.0) {
      return 0.0;
    }
    double urgency = EndgameUrgency.urgency(ctx.curr(), endgameParams);
    if (urgency <= 0.0) {
      return 0.0;
    }
    double depth = RewardUtils.botDepthInEnemyHalf(ctx.curr());
    if (depth <= 0.0) {
      return 0.0;
    }
    return params.endgameAttackBonus() * urgency * depth;
  }

  private static PlayerDto findClosestLivingNonCarrierTeammate(GameStateDto state,
      CoordinatesDto selfLoc) {
    if (state.teammates == null || selfLoc == null) {
      return null;
    }
    PlayerDto closest = null;
    double closestDist = Double.MAX_VALUE;
    for (PlayerDto t : state.teammates) {
      if (t == null || t.location == null || t.health <= 0 || t.hasFlag) continue;
      double d = RewardUtils.distance(selfLoc, t.location);
      if (d < closestDist) {
        closestDist = d;
        closest = t;
      }
    }
    return closest;
  }

  private static boolean teammateHadEnemyFlag(GameStateDto state) {
    if (state.teammates == null) return false;
    for (PlayerDto t : state.teammates) {
      if (t != null && t.hasFlag) return true;
    }
    return false;
  }
}
