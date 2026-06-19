package aiplay.rl.rewards.team.teamassist;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#TEAM_ASSIST}. */
@RewardModuleComponent
public final class TeamAssistModule implements RewardModule<TeamAssistParams> {

  @Override
  public RewardId id() {
    return RewardId.TEAM_ASSIST;
  }

  @Override
  public TeamAssistParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.TEAM_ASSIST, block);
    JsonNode w = s.requireWeights(RewardId.TEAM_ASSIST, block);
    return new TeamAssistParams(
        md,
        s.requireDouble(RewardId.TEAM_ASSIST, w, "weights.team_captured_assist"),
        s.requireDouble(RewardId.TEAM_ASSIST, w, "weights.team_returned_assist"),
        s.requireDouble(RewardId.TEAM_ASSIST, w, "weights.carrier_kill_assist"),
        s.requireDouble(RewardId.TEAM_ASSIST, w, "weights.escort_proximity_dense"),
        s.requireDouble(RewardId.TEAM_ASSIST, w, "weights.endgame_attack_bonus"),
        s.requireDouble(RewardId.TEAM_ASSIST, block, "assist_radius_uu"),
        s.requireDouble(RewardId.TEAM_ASSIST, block, "kill_assist_radius_uu"),
        s.requireDouble(RewardId.TEAM_ASSIST, block, "escort_dense_range_uu"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new TeamAssistReward(ctx.catalog().teamAssist(), ctx.catalog().endgameUrgency());
  }
}
