package aiplay.scanners.feature.resolver.mover;

import aiplay.dto.GameStateDto;
import aiplay.dto.MoverDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Resolves {@code self_mover{N}_*} features from the per-slot {@link MoverDto}
 * populated by {@link MoverEnricher}.
 */
public class MoverFeatureValueResolver implements TrainingFeatureValueResolver {

    private static final String PREFIX = "self_mover";

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
        if (slot < 0 || slot >= MoverEnricher.MAX_SLOTS) {
            return 0.0f;
        }
        String suffix = featureId.substring(underscoreIdx + 1);

        MoverDto m = lookupMover(f, slot);

        return switch (suffix) {
            case "present"            -> m != null ? 1.0f : 0.0f;
            case "relSin"             -> m != null ? (float) m.relSin : 0.0f;
            case "relCos"             -> m != null ? (float) m.relCos : 0.0f;
            case "distance_norm"      -> m != null ? (float) m.distanceNorm : 0.0f;
            case "forwardDist_norm"   -> m != null ? (float) m.forwardDistNorm : 0.0f;
            case "rightDist_norm"     -> m != null ? (float) m.rightDistNorm : 0.0f;
            case "zOffset_norm"       -> m != null ? (float) m.zOffsetNorm : 0.0f;
            case "onPlatform"         -> m != null && m.onPlatform ? 1.0f : 0.0f;
            case "isMoving"           -> m != null && m.opening ? 1.0f : 0.0f;
            case "moveProgress_norm"  -> m != null ? (float) m.moveProgress : 0.0f;
            case "destZOffset_norm"   -> m != null ? (float) m.destZOffsetNorm : 0.0f;
            case "destDistance_norm"   -> m != null ? (float) m.destDistanceNorm : 0.0f;
            case "timeToArrive_norm"  -> m != null ? (float) m.timeToArriveNorm : 0.0f;
            case "travelRange_norm"   -> m != null ? (float) m.travelRangeNorm : 0.0f;
            default                   -> null;
        };
    }

    private static MoverDto lookupMover(GameStateDto f, int slot) {
        if (f == null || f.movers == null || slot >= f.movers.size()) return null;
        return f.movers.get(slot);
    }
}
