package aiplay.rl.rewards.catalog;

/**
 * Constructie-context doorgegeven aan {@link RewardModule#create}. Draagt alles wat een reward-
 * component nodig kan hebben buiten z'n eigen {@code *Params}:
 *
 * <ul>
 *   <li>{@link #catalog()} — de volledig geparsede {@link RewardCatalog}, voor de per-reward typed
 *       params ({@code catalog.flagEvent()} ...) en cross-reward params zoals
 *       {@code catalog.endgameUrgency()} (gedeeld door de drie team-rewards).</li>
 *   <li>{@link #modelKey()} — voor model-afhankelijke constructie zoals
 *       {@code MovementActionModule}'s {@code bIdle} action-index lookup.</li>
 * </ul>
 */
public record RewardComponentContext(RewardCatalog catalog, String modelKey) {

  public RewardComponentContext {
    if (catalog == null) {
      throw new IllegalArgumentException("RewardComponentContext.catalog must not be null");
    }
    if (modelKey == null || modelKey.isBlank()) {
      throw new IllegalArgumentException("RewardComponentContext.modelKey must not be blank");
    }
  }
}
