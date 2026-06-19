package aiplay.rl.rewards.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Strikte (no-fallback) field-access helpers voor {@link RewardModule#parse} implementaties.
 *
 * <p>Eén instance per catalog-load draagt de {@code ctx}-prefix (bijv. {@code "rewards.json
 * (rl_pawn)"}) zodat elke parser dezelfde, exact ge-prefixte foutmeldingen produceert zonder die
 * context per call door te geven. Alle {@code requireX}-methodes gooien een
 * {@link IllegalStateException} wanneer het veld ontbreekt of het verkeerde JSON-type heeft —
 * conform de project-regel "no config fallbacks".
 *
 * <p>Path-style namen ({@code "weights.frag"}, {@code "kill_credit.window_ticks"}) gebruiken het
 * laatste segment voor de lookup maar de volledige naam in de foutmelding, zodat de error wijst naar
 * de logische JSON-locatie i.p.v. de leaf-key alleen.
 */
public final class RewardParseSupport {

  private final String ctx;

  public RewardParseSupport(String ctx) {
    if (ctx == null || ctx.isBlank()) {
      throw new IllegalArgumentException("RewardParseSupport.ctx must not be blank");
    }
    this.ctx = ctx;
  }

  /**
   * Bouwt een {@link IllegalStateException} met de ctx-prefix voor ad-hoc validatie die niet in de
   * generieke {@code requireX}-vorm past (bijv. dynamische map-keys onder {@code weights.ammo}).
   * Formaat: {@code "<ctx>: <detail>"}.
   */
  public IllegalStateException malformed(String detail) {
    return new IllegalStateException(ctx + ": " + detail);
  }

  /**
   * Haalt het reward-block {@code rewards.<id>} op uit de (reeds rewardgroup-merged)
   * rewards-tree. Gooit wanneer het block ontbreekt of geen object is.
   */
  public JsonNode requireBlock(JsonNode rewards, RewardId id) {
    JsonNode block = rewards.get(id.jsonKey());
    if (block == null || !block.isObject()) {
      throw new IllegalStateException(
          ctx + ": missing reward block 'rewards." + id.jsonKey() + "'");
    }
    return block;
  }

  /** Description / kind / owner / maps uit het reward-block. */
  public RewardMetadata metadata(RewardId id, JsonNode block) {
    String description = requireString(id, block, "description");
    RewardKind kind = parseKind(id, requireString(id, block, "kind"));
    RewardOwner owner = parseOwner(id, requireString(id, block, "owner"));
    Set<String> maps = parseMaps(block);
    return new RewardMetadata(id, description, kind, owner, maps);
  }

  public JsonNode requireWeights(RewardId id, JsonNode block) {
    JsonNode weights = block.get("weights");
    if (weights == null || !weights.isObject()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + ": missing 'weights' object");
    }
    return weights;
  }

  public JsonNode requireField(RewardId id, JsonNode block, String name) {
    JsonNode value = block.get(name);
    if (value == null || value.isNull()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + ": missing required field '" + name + "'");
    }
    return value;
  }

  public double requireDouble(RewardId id, JsonNode parent, String name) {
    JsonNode value = parent.get(leafKey(name));
    if (value == null || !value.isNumber()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + "." + name + ": must be a number");
    }
    return value.asDouble();
  }

  public int requireInt(RewardId id, JsonNode parent, String name) {
    JsonNode value = parent.get(leafKey(name));
    if (value == null || !value.isIntegralNumber()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + "." + name + ": must be an integer");
    }
    return value.asInt();
  }

  public boolean requireBoolean(RewardId id, JsonNode parent, String name) {
    JsonNode value = parent.get(leafKey(name));
    if (value == null || !value.isBoolean()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + "." + name + ": must be a boolean");
    }
    return value.asBoolean();
  }

  public String requireString(RewardId id, JsonNode parent, String name) {
    JsonNode value = parent.get(leafKey(name));
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + "." + name + ": must be a non-blank string");
    }
    return value.asText();
  }

  private static Set<String> parseMaps(JsonNode block) {
    JsonNode mapsNode = block.get("maps");
    if (mapsNode == null || !mapsNode.isArray()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonNode entry : mapsNode) {
      if (entry.isTextual() && !entry.asText().isBlank()) {
        result.add(entry.asText().toLowerCase());
      }
    }
    return Set.copyOf(result);
  }

  private RewardKind parseKind(RewardId id, String value) {
    try {
      return RewardKind.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + ".kind: invalid value '" + value
              + "', expected one of " + java.util.Arrays.toString(RewardKind.values()));
    }
  }

  private RewardOwner parseOwner(RewardId id, String value) {
    try {
      return RewardOwner.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          ctx + " rewards." + id.jsonKey() + ".owner: invalid value '" + value
              + "', expected one of " + java.util.Arrays.toString(RewardOwner.values()));
    }
  }

  /** Path-style names like "weights.frag" or "kill_credit.window_ticks" → use last segment. */
  private static String leafKey(String name) {
    int dot = name.lastIndexOf('.');
    return (dot >= 0) ? name.substring(dot + 1) : name;
  }
}
