package aiplay.rl.rewards.catalog;

/**
 * Marker-interface voor elk typed {@code *Params}-record dat door
 * {@link aiplay.rl.rewards.catalog.RewardCatalog} wordt geleverd.
 *
 * <p>Iedere reward-implementatie ({@link aiplay.rl.rewards.core.RewardComponent}) ontvangt z'n eigen
 * {@code *Params} via constructor-injection — geen lookup tegen een gedeeld god-object meer. De
 * twee universele methods hieronder maken het mogelijk om generieke logging/validatie te schrijven
 * zonder per-reward casts.
 *
 * <ul>
 *   <li>{@link #metadata()} — description / kind / owner uit {@code rewards.json}.</li>
 *   <li>{@link #enabled()} — true wanneer de reward minstens één non-zero weight heeft. Een
 *       disabled reward wordt door {@link aiplay.rl.RewardComputer} niet eens ge-instantieerd.</li>
 * </ul>
 */
public interface RewardBlock {

  RewardMetadata metadata();

  boolean enabled();
}
