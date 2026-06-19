package aiplay.scanners.feature.resolver.jumppad;

import aiplay.dto.GameStateDto;
import aiplay.dto.JumpPadRelationDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Resolves {@code self_jumpPad{N}_*} features from the per-slot {@link JumpPadRelationDto}
 * populated by {@link JumpPadEnricher}.
 *
 * <p>Slot index N must be in {@code [0, JumpPadEnricher.MAX_SLOTS-1]}; out-of-range or
 * absent pads return 0 (also implicitly handled via {@code present}).
 *
 * <p>Owner-prefix is {@code self_}: pads are scored relative to the bot's own location.
 * Equivalent enemy/teammate variants would have their own resolver (separate component).
 */
public class JumpPadFeatureValueResolver implements TrainingFeatureValueResolver {

    /** Prefix incl. owner: "self_jumpPad". Slot index volgt direct na de prefix. */
    private static final String PREFIX = "self_jumpPad";

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        if (featureId == null || !featureId.startsWith(PREFIX)) {
            return null;
        }
        int underscoreIdx = featureId.indexOf('_', PREFIX.length());
        if (underscoreIdx <= PREFIX.length()) {
            return null;
        }
        int slot;
        try {
            slot = Integer.parseInt(featureId.substring(PREFIX.length(), underscoreIdx));
        } catch (NumberFormatException e) {
            return null;
        }
        if (slot < 0 || slot >= JumpPadEnricher.MAX_SLOTS) {
            return 0.0f;
        }
        String suffix = featureId.substring(underscoreIdx + 1);

        JumpPadRelationDto rel = lookupRelation(f, slot);

        return switch (suffix) {
            case "present"                  -> rel != null ? 1.0f : 0.0f;
            case "relSin"                   -> rel != null ? (float) rel.relSin : 0.0f;
            case "relCos"                   -> rel != null ? (float) rel.relCos : 0.0f;
            case "distance_norm"            -> rel != null ? (float) rel.distance_norm : 0.0f;
            case "forwardDist_norm"         -> rel != null ? (float) rel.forwardDist_norm : 0.0f;
            case "rightDist_norm"           -> rel != null ? (float) rel.rightDist_norm : 0.0f;
            case "zOffset_norm"             -> rel != null ? (float) rel.zOffset_norm : 0.0f;
            case "landingForwardDist_norm"  -> rel != null ? (float) rel.landingForwardDist_norm : 0.0f;
            case "landingRightDist_norm"    -> rel != null ? (float) rel.landingRightDist_norm : 0.0f;
            case "landingZOffset_norm"      -> rel != null ? (float) rel.landingZOffset_norm : 0.0f;
            case "landingTowardsGoal_cos"   -> rel != null ? (float) rel.landingTowardsGoal_cos : 0.0f;
            default                         -> null;
        };
    }

    private static JumpPadRelationDto lookupRelation(GameStateDto f, int slot) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        JumpPadRelationDto[] rels = f.playerPawn.enrichments.jumpPadRels;
        if (rels == null || slot >= rels.length) return null;
        return rels[slot];
    }
}
