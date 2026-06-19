package aiplay.rl.rewards.catalog;

import java.util.Set;

/**
 * Metadata-tripel dat elke reward op het hoogste niveau bezit, los van de reward-specifieke
 * parameters (weights, thresholds, sigma's, ...).
 *
 * <p>Beschikbaar via {@link aiplay.rl.rewards.catalog.RewardCatalog#metadata(RewardId)} en
 * impliciet via elke typed {@code *Params}-record (zie {@link RewardBlock#metadata()}).
 *
 * <p>{@code description} is de menselijk-leesbare uitleg uit {@code rewards.json}. Verplicht en
 * non-blank — een reward zonder description wordt geweigerd bij load. Doel: documentatie en code
 * single source of truth.
 *
 * <p>{@code maps} beperkt de reward tot specifieke maps. Lege set = actief op alle maps.
 */
public record RewardMetadata(
    RewardId id,
    String description,
    RewardKind kind,
    RewardOwner owner,
    Set<String> maps) {

  public RewardMetadata {
    if (id == null) {
      throw new IllegalArgumentException("RewardMetadata.id must not be null");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException(
          "RewardMetadata.description must be a non-blank string for reward '" + id + "'");
    }
    if (kind == null) {
      throw new IllegalArgumentException("RewardMetadata.kind must not be null for reward '" + id + "'");
    }
    if (owner == null) {
      throw new IllegalArgumentException("RewardMetadata.owner must not be null for reward '" + id + "'");
    }
    if (maps == null) {
      throw new IllegalArgumentException("RewardMetadata.maps must not be null for reward '" + id + "'");
    }
  }

  public boolean isActiveForMap(String mapName) {
    if (maps.isEmpty()) return true;
    if (mapName == null || mapName.isEmpty()) return false;
    return maps.contains(mapName.toLowerCase());
  }
}
