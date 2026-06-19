package aiplay.rl.rewards.objective.pickupevent;

import aiplay.rl.rewards.catalog.RewardComponentContext;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModuleComponent;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.core.RewardComponent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@link RewardModule} voor {@link RewardId#PICKUP_EVENT}. */
@RewardModuleComponent
public final class PickupEventModule implements RewardModule<PickupEventParams> {

  @Override
  public RewardId id() {
    return RewardId.PICKUP_EVENT;
  }

  @Override
  public PickupEventParams parse(RewardParseSupport s, JsonNode block) {
    RewardMetadata md = s.metadata(RewardId.PICKUP_EVENT, block);
    JsonNode w = s.requireWeights(RewardId.PICKUP_EVENT, block);
    JsonNode caps = s.requireField(RewardId.PICKUP_EVENT, block, "caps");
    // Pad-A: ammo-weights uit weights.ammo (Map). Geen ammo configured = empty map.
    Map<String, Double> ammoWeights = new LinkedHashMap<>();
    JsonNode ammoNode = w.path("ammo");
    if (ammoNode.isObject()) {
      var it = ammoNode.fields();
      while (it.hasNext()) {
        var e = it.next();
        if (e.getKey().startsWith("_")) continue;
        if (!e.getValue().isNumber()) {
          throw s.malformed("pickup_event.weights.ammo." + e.getKey() + " must be numeric");
        }
        ammoWeights.put(e.getKey().toLowerCase(), e.getValue().asDouble());
      }
    }
    return new PickupEventParams(
        md,
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.shieldbelt"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.shieldbelt"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.armor"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.armor"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.thighpads"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.thighpads"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.amp_flat"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.megahealth"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.megahealth"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.medbox"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.medbox"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.vial"),
        s.requireDouble(RewardId.PICKUP_EVENT, caps, "caps.vial"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.weapon_new_flat"),
        s.requireDouble(RewardId.PICKUP_EVENT, w, "weights.weapon_owned_flat"),
        ammoWeights);
  }

  @Override
  public RewardComponent create(RewardComponentContext ctx) {
    return new PickupEventReward(ctx.catalog().pickupEvent());
  }
}
