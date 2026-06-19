package aiplay.scanners.feature.resolver.teammate;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.PlayerDtoFeatureResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves features for any teammate slot by parsing the slot index from the feature ID.
 * Pattern: "teammate{N}_{suffix}" where N is the slot index (0..MAX-1).
 *
 * Teammate-specific features (isAlive, egocentric, pitchBearing) are handled directly.
 * All PlayerDto-level features (collision, velocity, acceleration, viewRotation, hasFlag, etc.)
 * are delegated to {@link PlayerDtoFeatureResolver} — adding a feature there
 * automatically makes it available for all teammate slots.
 *
 * Slot index and suffix are pre-parsed at class load time to avoid repeated string
 * parsing on every resolve call.
 */
public class TeammateSlotFeatureValueResolver implements TrainingFeatureValueResolver {

    private record ParsedSlot(int slot, String suffix) {}

    private static final Map<String, ParsedSlot> SLOT_CACHE;

    static {
        Map<String, ParsedSlot> cache = new HashMap<>(
                TeammateSlotFeatureComponent.MAX_SLOTS * TeammateSlotFeatureComponent.FEATURE_SUFFIXES.length);
        for (int slot = 0; slot < TeammateSlotFeatureComponent.MAX_SLOTS; slot++) {
            String prefix = "teammate" + slot + "_";
            for (String suffix : TeammateSlotFeatureComponent.FEATURE_SUFFIXES) {
                cache.put(prefix + suffix, new ParsedSlot(slot, suffix));
            }
        }
        SLOT_CACHE = Map.copyOf(cache);
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        ParsedSlot parsed = SLOT_CACHE.get(featureId);
        if (parsed == null) return null;
        int slot = parsed.slot;
        String suffix = parsed.suffix;

        PlayerDto teammate = getTeammateSlot(f, slot);
        PlayerRelationDto rel = getTeammateRel(f, slot);

        return switch (suffix) {
            // Status (teammate-specific)
            case "isAlive" -> (teammate != null && teammate.health > 0) ? 1.0f : 0.0f;

            // Egocentric (from enrichment — relative to bot's view direction)
            case "relSin" -> (rel != null) ? (float) rel.relSin : 0.0f;
            case "relCos" -> (rel != null) ? (float) rel.relCos : 0.0f;
            case "forwardDist_norm" -> (rel != null) ? (float) rel.forwardDist_norm : 0.0f;
            case "rightDist_norm" -> (rel != null) ? (float) rel.rightDist_norm : 0.0f;
            case "distance_norm" -> (rel != null) ? (float) rel.distance_norm : 0.0f;
            case "pitchBearing_norm" -> (rel != null) ? (float) rel.pitchBearing_norm : 0.0f;

            case "relVelForward_norm" -> (rel != null) ? (float) rel.relVelForward_norm : 0.0f;
            case "relVelRight_norm" -> (rel != null) ? (float) rel.relVelRight_norm : 0.0f;
            case "relVelUp_norm" -> (rel != null) ? (float) rel.relVelUp_norm : 0.0f;

            // Egocentric vertical offset (map-onafhankelijk; tanh-geschaald op 512 UU)
            case "relZ_norm" -> (rel != null) ? (float) rel.relZ_norm : 0.0f;

            // All PlayerDto-level features (collision, velocity, viewRotation, hasFlag, etc.)
            default -> PlayerDtoFeatureResolver.resolve(suffix, teammate);
        };
    }

    private static PlayerDto getTeammateSlot(GameStateDto f, int slot) {
        if (f == null || f.teammates == null || slot >= f.teammates.length) return null;
        return f.teammates[slot];
    }

    private static PlayerRelationDto getTeammateRel(GameStateDto f, int slot) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        PlayerRelationDto[] rels = f.playerPawn.enrichments.teammateRels;
        if (rels == null || slot >= rels.length) return null;
        return rels[slot];
    }
}
