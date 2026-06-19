package aiplay.rl.rewards.objective.scoregainrate;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/** {@link RewardModule} voor {@link RewardId#SCORE_GAIN_RATE}. */
@RewardModuleComponent
public final class ScoreGainRateModule implements RewardModule<ScoreGainRateParams> {

  @Override
  public RewardId id() {
    return RewardId.SCORE_GAIN_RATE;
  }

  @Override
  public ScoreGainRateParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.SCORE_GAIN_RATE, block);
    return new ScoreGainRateParams(
        md,
        s.requireDouble(RewardId.SCORE_GAIN_RATE, block, "weight"),
        s.requireInt(RewardId.SCORE_GAIN_RATE, block, "window_ms"),
        s.requireInt(RewardId.SCORE_GAIN_RATE, block, "min_window_ms"));
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new ScoreGainRateReward(ctx.catalog().scoreGainRate());
  }
}
