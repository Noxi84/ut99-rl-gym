package aiplay.scanners.feature.resolver.translocator;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.enemy.EnemySlotRelativeBatchEnricher;
import aiplay.util.NormalizationUtils;

/**
 * Egocentric translocator-disc features. Berekening on-the-fly uit
 * {@code playerPawn.discLocation} + {@code playerPawn.location} +
 * {@code playerPawn.viewRotation}. {@code timeSinceThrow_norm} komt van
 * de tracking-enricher.
 */
public class TranslocatorDiscFeatureValueResolver implements TrainingFeatureValueResolver {

    /** Z-offset normalisatie-window: ±500 UU dekt de meeste vert-disparities binnen
     *  praktische teleport-afstanden (boven/onder kop). */
    private static final double Z_OFFSET_NORM = 500.0;

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        PlayerDto pawn = (f != null) ? f.playerPawn : null;
        if (pawn == null) return 0.0f;

        return switch (featureId) {
            case "self_disc_present" -> pawn.discPresent ? 1.0f : 0.0f;
            case "self_disc_timeSinceThrow_norm" -> pawn.discTimeSinceThrow_norm;
            case "self_disc_relSin", "self_disc_relCos",
                 "self_disc_distance_norm", "self_disc_pitchBearing_norm",
                 "self_disc_rel_z_norm" -> resolveEgocentric(featureId, pawn);
            default -> null;
        };
    }

    private static float resolveEgocentric(String featureId, PlayerDto pawn) {
        if (!pawn.discPresent) return 0.0f;
        CoordinatesDto loc = pawn.location;
        CoordinatesDto disc = pawn.discLocation;
        if (loc == null || disc == null || pawn.viewRotation == null) return 0.0f;

        double dx = disc.x - loc.x;
        double dy = disc.y - loc.y;
        double dz = disc.z - loc.z;
        double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);

        return switch (featureId) {
            case "self_disc_relSin" -> {
                double[] sc = NormalizationUtils.relativeAngleSinCos(
                    pawn.viewRotation.x & 0xFFFF, loc.x, loc.y, disc.x, disc.y);
                yield (float) sc[0];
            }
            case "self_disc_relCos" -> {
                double[] sc = NormalizationUtils.relativeAngleSinCos(
                    pawn.viewRotation.x & 0xFFFF, loc.x, loc.y, disc.x, disc.y);
                yield (float) sc[1];
            }
            case "self_disc_distance_norm" ->
                (float) NormalizationUtils.normalizeDistance3D(dist3D);
            case "self_disc_pitchBearing_norm" ->
                (float) EnemySlotRelativeBatchEnricher.computePitchBearingNorm(
                    loc, pawn.baseEyeHeight, disc, 0.0);
            case "self_disc_rel_z_norm" ->
                (float) NormalizationUtils.clampM11(dz / Z_OFFSET_NORM);
            default -> 0.0f;
        };
    }
}
