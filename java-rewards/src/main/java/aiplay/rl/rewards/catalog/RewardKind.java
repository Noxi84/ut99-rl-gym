package aiplay.rl.rewards.catalog;

/**
 * Categorie van een reward voor breakdown-aggregatie.
 *
 * <ul>
 *   <li>{@link #SPARSE}: event-getriggerd (frag, flag-capture, shot-edge). Hoge magnitude per
 *       occurrence, weinig per match.</li>
 *   <li>{@link #DENSE}: per-tick continuous shaping (alignment, progress, spacing). Lage magnitude
 *       per tick, telt op over een venster.</li>
 *   <li>{@link #ACTION}: action-conditional (collision, stuck, dodge). Vereist {@code action} in de
 *       {@link aiplay.rl.rewards.core.RewardContext}.</li>
 * </ul>
 *
 * <p>De waarde wordt door de {@link aiplay.rl.rewards.core.RewardBreakdown}-aggregator gebruikt om
 * sparse/dense/action-totalen automatisch te berekenen — niet langer hand-getypte sommatie.
 */
public enum RewardKind {
  SPARSE,
  DENSE,
  ACTION
}
