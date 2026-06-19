package aiplay.rl.rewards.core;

/**
 * Grove categorisering van een {@link RewardSignal}, enkel gebruikt voor de
 * logging-subtotalen (sparse events vs. per-tick dense shaping vs.
 * actie-gebonden movement-penalties).
 *
 * <p>Elk signaal valt in exact één categorie, zodat de som van de drie
 * categorie-subtotalen gelijk is aan {@link RewardBreakdown#total()}. Vóór de
 * signaal-catalog werd die eigenschap handmatig (en incompleet) onderhouden in
 * {@code RewardBreakdown.sparseTotal/denseTotal/actionTotal} — waardoor
 * later toegevoegde rewards (o.a. {@code teamAssist}) in geen enkel subtotaal
 * meetelden. De categorie hoort hier in {@code java-rewards} omdat ze
 * intrinsiek is aan de aard van de reward, niet aan de joint-model-decompositie.</p>
 */
public enum RewardCategory {
  /** Sparse, event-driven: flag-/combat-events, shot-onset, damage-deltas, pickups. */
  SPARSE,
  /** Dense per-tick shaping: alignment, speed, spacing, proximity, team-presence. */
  DENSE,
  /** Actie-gebonden movement-penalties: collision, stuck, dodge, idle. */
  ACTION
}
