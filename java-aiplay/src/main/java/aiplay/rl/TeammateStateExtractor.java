package aiplay.rl;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.rl.rewards.core.RewardUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fase 2.5 CTDE — extracts a fixed-size closest-2 teammate slice for the Python critic.
 *
 * <p>The slice is written as an NPZ aux key {@code teammate_state.npy} (shape
 * {@code [N, FEATURES_PER_SLOT * NUM_SLOTS] = [N, 40]}) parallel to {@code target_label}, and the
 * Python {@code CTDEMultiHeadSACCritic} concatenates it to the self-state before the per-head Q
 * MLP. Decentralized execution: the slice is NEVER fed to the runtime actor — it is critic-only.
 *
 * <h2>Ordering</h2>
 *
 * Carrier-first: when any living teammate currently carries a flag (i.e. holds the enemy flag),
 * slot 0 is that teammate and slot 1 is the closest of the remaining living teammates. When no
 * teammate carries, slot 0 is the closest living teammate and slot 1 is the second-closest.
 * Missing slots are zero-padded (no synthesis; absent teammate = zero block).
 *
 * <h2>Perception model</h2>
 *
 * Self-perceived (variant A in {@code team-coordination.md} §"L2 variants"): the slice is taken
 * straight off the bot's own {@link GameStateDto} — the teammate's own world-view is not
 * tick-synced. Acceptable for the critic since it only needs to learn the *correlation* between
 * teammate state and team-utility, not perfect teammate perspective. Pre-built relative features
 * (relSin/relCos/distance/pitchBearing) come from the existing
 * {@code TeammateSlotRelativeBatchEnricher} via {@code PlayerPawn.enrichments.teammateRels}.
 *
 * <h2>Feature layout (20 per slot)</h2>
 *
 * <ol>
 *   <li>{@code isAlive}: 1.0 when the slot has a non-null living teammate, else 0.0</li>
 *   <li>{@code health_norm}: clamp({@code teammate.health / 100}, [0, 2]) — UDamage-shielded over-100 is preserved</li>
 *   <li>{@code armor_norm}: clamp({@code teammate.armor / 200}, [0, 1])</li>
 *   <li>{@code hasFlag}: 1.0 when teammate.hasFlag</li>
 *   <li>{@code relSin}: egocentric — sin of bearing from bot to teammate (-1..1)</li>
 *   <li>{@code relCos}: egocentric — cos of bearing from bot to teammate (-1..1)</li>
 *   <li>{@code forwardDist_norm}: signed projected distance along bot view-axis</li>
 *   <li>{@code rightDist_norm}: signed projected distance perpendicular to bot view-axis</li>
 *   <li>{@code distance_norm}: 2D distance bot↔teammate normalized on inter-base distance</li>
 *   <li>{@code pitchBearing_norm}: pitch from bot to teammate (-1..1)</li>
 *   <li>{@code relVelForward_norm}: teammate velocity projected on bot forward</li>
 *   <li>{@code relVelRight_norm}: teammate velocity projected on bot right</li>
 *   <li>{@code relVelUp_norm}: teammate vertical velocity</li>
 *   <li>{@code speed_norm}: teammate 2D speed magnitude (pre-normalized by Java)</li>
 *   <li>{@code fireActive}: 1.0 when teammate fire button is down</li>
 *   <li>{@code altFireActive}: 1.0 when teammate alt-fire button is down</li>
 *   <li>{@code dodgeCooldownNorm}: 0.0 = just dodged, 1.0 = recovered</li>
 *   <li>{@code yawAngularVelocity_norm}: teammate yaw-rate (turn rate)</li>
 *   <li>{@code pitchAngularVelocity_norm}: teammate pitch-rate</li>
 *   <li>{@code proximityToOwnFlag_norm}: 1.0 when teammate is on top of own flag base, 0.0 at inter-base distance</li>
 * </ol>
 */
public final class TeammateStateExtractor {

  public static final int FEATURES_PER_SLOT = 20;
  public static final int NUM_SLOTS = 2;
  public static final int SLICE_SIZE = FEATURES_PER_SLOT * NUM_SLOTS;

  private TeammateStateExtractor() {
  }

  /**
   * Build the closest-2 teammate slice. Returns a {@link #SLICE_SIZE}-element {@code float[]} with
   * zero blocks for absent slots.
   */
  public static float[] extract(GameStateDto state) {
    float[] out = new float[SLICE_SIZE];
    if (state == null || state.playerPawn == null || state.playerPawn.location == null) {
      return out;
    }
    PlayerDto[] teammates = state.teammates;
    if (teammates == null || teammates.length == 0) {
      return out;
    }
    CoordinatesDto selfLoc = state.playerPawn.location;
    PlayerRelationDto[] rels = (state.playerPawn.enrichments != null)
        ? state.playerPawn.enrichments.teammateRels : null;

    // Build (teammate, rel, originalSlotIndex, distance) tuples for living teammates.
    List<Entry> living = new ArrayList<>(teammates.length);
    for (int i = 0; i < teammates.length; i++) {
      PlayerDto t = teammates[i];
      if (t == null || t.location == null || t.health <= 0) continue;
      PlayerRelationDto rel = (rels != null && i < rels.length) ? rels[i] : null;
      double dist = RewardUtils.distance2d(selfLoc, t.location);
      living.add(new Entry(t, rel, dist));
    }
    if (living.isEmpty()) {
      return out;
    }
    living.sort(Comparator.comparingDouble(e -> e.distance2d));

    // Carrier-first reorder: if any teammate has the flag, move it to position 0.
    int carrierIdx = -1;
    for (int i = 0; i < living.size(); i++) {
      if (living.get(i).teammate.hasFlag) {
        carrierIdx = i;
        break;
      }
    }
    if (carrierIdx > 0) {
      Entry carrier = living.remove(carrierIdx);
      living.add(0, carrier);
    }

    double interBaseDist = RewardUtils.computeInterBaseDistance(state);
    int slotsFilled = Math.min(NUM_SLOTS, living.size());
    for (int slot = 0; slot < slotsFilled; slot++) {
      Entry e = living.get(slot);
      writeSlot(out, slot * FEATURES_PER_SLOT, state, e.teammate, e.rel, interBaseDist);
    }
    return out;
  }

  private static void writeSlot(float[] out, int offset, GameStateDto state, PlayerDto t,
      PlayerRelationDto rel, double interBaseDist) {
    out[offset + 0] = 1.0f; // isAlive — we only sort living teammates in
    out[offset + 1] = clamp(t.health / 100.0f, 0.0f, 2.0f);
    out[offset + 2] = clamp(t.armor / 200.0f, 0.0f, 1.0f);
    out[offset + 3] = t.hasFlag ? 1.0f : 0.0f;
    if (rel != null) {
      out[offset + 4] = (float) rel.relSin;
      out[offset + 5] = (float) rel.relCos;
      out[offset + 6] = (float) rel.forwardDist_norm;
      out[offset + 7] = (float) rel.rightDist_norm;
      out[offset + 8] = (float) rel.distance_norm;
      out[offset + 9] = (float) rel.pitchBearing_norm;
      out[offset + 10] = (float) rel.relVelForward_norm;
      out[offset + 11] = (float) rel.relVelRight_norm;
      out[offset + 12] = (float) rel.relVelUp_norm;
    }
    out[offset + 13] = t.speed_norm;
    out[offset + 14] = t.fireActive ? 1.0f : 0.0f;
    out[offset + 15] = t.altFireActive ? 1.0f : 0.0f;
    out[offset + 16] = t.dodgeCooldownNorm;
    out[offset + 17] = t.yawAngularVelocity_norm;
    out[offset + 18] = t.pitchAngularVelocity_norm;
    out[offset + 19] = (float) computeProximityToOwnFlag(state, t, interBaseDist);
  }

  /**
   * Returns {@code 1.0} when the teammate stands on their own flag base, {@code 0.0} at one
   * inter-base distance (or further). Linear ramp in between. Critic uses this as a proxy for
   * "is this teammate defending home?".
   */
  private static double computeProximityToOwnFlag(GameStateDto state, PlayerDto teammate,
      double interBaseDist) {
    if (teammate.location == null || interBaseDist <= 1.0) {
      return 0.0;
    }
    int team = teammate.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    if (ownFlag == null || ownFlag.baseLocation == null) {
      return 0.0;
    }
    double d = RewardUtils.distance2d(teammate.location, ownFlag.baseLocation);
    double r = Math.max(0.0, 1.0 - d / interBaseDist);
    return Math.min(1.0, r);
  }

  private static float clamp(float value, float lo, float hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
  }

  private record Entry(PlayerDto teammate, PlayerRelationDto rel, double distance2d) {
  }
}
