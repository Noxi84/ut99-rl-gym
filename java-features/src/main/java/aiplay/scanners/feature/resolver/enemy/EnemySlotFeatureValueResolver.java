package aiplay.scanners.feature.resolver.enemy;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.PlayerDtoFeatureResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves features for any enemy slot by parsing the slot index from the feature ID.
 * Pattern: "enemy{N}_{suffix}" where N is the slot index (0..MAX-1).
 *
 * Returns 0.0f for empty slots (guard: enemy{N}_isAlive = 0).
 *
 * Enemy-specific features (isAlive, egocentric, pitchBearing) are handled directly.
 * All PlayerDto-level features (collision, velocity, acceleration, viewRotation,
 * hasFlag, visible, dodge, etc.) are delegated to {@link PlayerDtoFeatureResolver}
 * — adding a feature there automatically makes it available for all enemy slots.
 *
 * Slot index and suffix are pre-parsed at class load time to avoid repeated string
 * parsing on every resolve call.
 */
public class EnemySlotFeatureValueResolver implements TrainingFeatureValueResolver {

    private record ParsedSlot(int slot, String suffix) {}

    private static final Map<String, ParsedSlot> SLOT_CACHE;

    static {
        Map<String, ParsedSlot> cache = new HashMap<>(
                EnemySlotFeatureComponent.MAX_SLOTS * EnemySlotFeatureComponent.FEATURE_SUFFIXES.length);
        for (int slot = 0; slot < EnemySlotFeatureComponent.MAX_SLOTS; slot++) {
            String prefix = "enemy" + slot + "_";
            for (String suffix : EnemySlotFeatureComponent.FEATURE_SUFFIXES) {
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

        PlayerDto enemy = getEnemySlot(f, slot);
        PlayerRelationDto rel = getEnemyRel(f, slot);

        return switch (suffix) {
            // Status (enemy-specific)
            case "isAlive" -> (enemy != null && enemy.health > 0) ? 1.0f : 0.0f;

            // Egocentric (from enrichment — relative to bot's view direction)
            case "relSin" -> (rel != null) ? (float) rel.relSin : 0.0f;
            case "relCos" -> (rel != null) ? (float) rel.relCos : 0.0f;
            case "forwardDist_norm" -> (rel != null) ? (float) rel.forwardDist_norm : 0.0f;
            case "rightDist_norm" -> (rel != null) ? (float) rel.rightDist_norm : 0.0f;
            case "distance_norm" -> (rel != null) ? (float) rel.distance_norm : 0.0f;
            case "pitchBearing_norm" -> (rel != null) ? (float) rel.pitchBearing_norm : 0.0f;
            case "aimAlignmentDot_norm" -> (rel != null) ? (float) rel.aimAlignmentDot_norm : 0.0f;

            // Egocentric velocity — target velocity in bot view-frame (lead-aim signal)
            case "relVelForward_norm" -> (rel != null) ? (float) rel.relVelForward_norm : 0.0f;
            case "relVelRight_norm" -> (rel != null) ? (float) rel.relVelRight_norm : 0.0f;
            case "relVelUp_norm" -> (rel != null) ? (float) rel.relVelUp_norm : 0.0f;

            // Egocentric vertical offset (map-onafhankelijk; tanh-geschaald op 512 UU)
            case "relZ_norm" -> (rel != null) ? (float) rel.relZ_norm : 0.0f;

            // All PlayerDto-level features (collision, velocity, viewRotation, hasFlag, visible, dodge, etc.)
            default -> PlayerDtoFeatureResolver.resolve(suffix, enemy);
        };
    }

    private static PlayerDto getEnemySlot(GameStateDto f, int slot) {
        if (f == null || f.enemies == null || slot >= f.enemies.length) return null;
        return f.enemies[slot];
    }

    private static PlayerRelationDto getEnemyRel(GameStateDto f, int slot) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        PlayerRelationDto[] rels = f.playerPawn.enrichments.enemyRels;
        if (rels == null || slot >= rels.length) return null;
        return rels[slot];
    }
}
