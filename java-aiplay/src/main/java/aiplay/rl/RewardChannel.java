package aiplay.rl;

/**
 * De zes skill-kanalen van het joint {@code rl_pawn} model plus een residual,
 * elk een eigen critic-head in de multi-head Q-decompositie. Dit is
 * joint-model-decompositiekennis en leeft daarom in {@code java-aiplay}, niet
 * bij de reward-signalen in {@code java-rewards}.
 *
 * <p>Elk {@link aiplay.rl.rewards.core.RewardSignal} wordt op één of meer
 * kanalen gerouteerd door {@link JointRewardDecompositionStrategy}; de som over
 * de kanalen + residual is de scalar reward.</p>
 */
public enum RewardChannel {
  MOVEMENT,
  VIEW,
  PITCH,
  FIRE,
  ALT_FIRE,
  TEAM_ASSIST,
  RESIDUAL;

  public static final int COUNT = values().length;
}
