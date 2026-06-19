package aiplay.rl.rewards.catalog;

import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-reward module: bundelt de drie verantwoordelijkheden die voorheen verspreid zaten over
 * {@code JsonRewardCatalog} (parsing), {@code catalog.params} ({@link RewardBlock} data) en
 * {@code RewardComputer} (constructie) in één co-located unit naast de
 * {@code <Name>Params}- en {@code <Name>Reward}-klassen.
 *
 * <p>Eén implementatie per {@link RewardId} (24 totaal); geregistreerd in {@link RewardModules}.
 * {@code EndgameUrgencyParams} valt buiten dit contract — het is geen {@link RewardBlock}, heeft
 * geen eigen {@link RewardComponent} en wordt top-level geparsed door {@code JsonRewardCatalog}.
 *
 * @param <P> het typed {@link RewardBlock}-record dat {@link #parse} produceert en dat de
 *     bijbehorende {@link RewardComponent} via {@link RewardCatalog} consumeert.
 */
public interface RewardModule<P extends RewardBlock> {

  /** Stabiele identifier; bepaalt de {@code rewards.json}-key en de catalog-slot. */
  RewardId id();

  /**
   * Parse het (reeds rewardgroup-merged) reward-block tot het typed {@code *Params}-record.
   * Gebruikt {@link RewardParseSupport} voor strikte, uniform ge-prefixte veld-validatie.
   */
  P parse(RewardParseSupport support, JsonNode block);

  /**
   * Construeer de {@link RewardComponent} voor deze reward. De typed params worden uit
   * {@code ctx.catalog()} gehaald via de per-reward accessor — type-safe, geen cast. Cross-reward
   * afhankelijkheden (bijv. {@code endgameUrgency}) en model-context ({@code ctx.modelKey()}) komen
   * eveneens uit {@code ctx}.
   */
  RewardComponent create(RewardComponentContext ctx);
}
