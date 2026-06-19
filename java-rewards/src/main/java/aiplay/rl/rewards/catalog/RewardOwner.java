package aiplay.rl.rewards.catalog;

/**
 * Welk model primair leerverantwoordelijkheid heeft voor het gedrag dat door een reward gedreven
 * wordt. Bepaalt of een reward in een gegeven {@code rewards.json} thuishoort.
 *
 * <ul>
 *   <li>{@link #MOVEMENT}: locomotie + objective progress + spacing.</li>
 *   <li>{@link #VIEWROTATION}: yaw/pitch alignment + tracking + smoothness.</li>
 *   <li>{@link #SHOOTING}: fire-decision events, on/off-target, kill-by-fire, fire-penalties.</li>
 *   <li>{@link #SHARED}: niet eenduidig één model (bijv. {@code alive_bonus}, {@code death});
 *       sparse signaal dat alle modellen kunnen ontvangen.</li>
 * </ul>
 *
 * <p>Bedoeld voor documentatie en debugging — runtime gebruikt deze waarde alleen voor breakdown
 * labelling en consistency-validatie tijdens config-load (geen reward in een rewards.json met
 * verkeerde owner-model-fit).
 */
public enum RewardOwner {
  MOVEMENT,
  VIEWROTATION,
  SHOOTING,
  SHARED
}
