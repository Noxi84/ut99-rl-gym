package aiplay.rl.rewards.core;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.MapKey;
import aiplay.runtime.geo.GeodesicField;
import aiplay.runtime.geo.GeodesicFieldRepository;

import java.util.Optional;

/**
 * Route-afstanden voor progress-shaping: geodesisch (langs de bezoekgraaf van de map)
 * waar een veld beschikbaar is, anders euclidisch.
 *
 * <p>Altijd als PAAR berekend (prev + curr naar hetzelfde doel): beide afstanden komen
 * gegarandeerd uit dezelfde metriek, zodat de per-tick delta nooit een geodesisch/euclidisch
 * mengsel is (dat zou spike-ruis in de shaping geven; de ±50 UU clamp in de callers dempt
 * de resterende metriek-wissels tússen ticks).
 *
 * <p>Fallback-semantiek: {@code geodesic_field=false} voor de map, een punt buiten de
 * veld-dekking, of een doel zonder route → euclidisch paar. Zo degradeert de shaping
 * gracieus naar het oude gedrag in plaats van te verdwijnen.
 */
public final class RouteDistances {

    private RouteDistances() {}

    /**
     * 3D prev/curr-afstand naar {@code target}. Geodesisch wanneer de map een veld heeft
     * en beide punten een eindige route naar het doel hebben; anders euclidisch (3D).
     */
    public static double[] pairTo(GameStateDto frame, CoordinatesDto prev, CoordinatesDto curr,
                                  CoordinatesDto target) {
        Optional<GeodesicField> field = fieldFor(frame);
        if (field.isPresent()) {
            double prevGeo = field.get().distanceOrNaN(prev, target);
            double currGeo = field.get().distanceOrNaN(curr, target);
            if (!Double.isNaN(prevGeo) && !Double.isNaN(currGeo)) {
                return new double[] {prevGeo, currGeo};
            }
        }
        return new double[] {
            RewardUtils.distance(prev, target),
            RewardUtils.distance(curr, target)
        };
    }

    /**
     * Prev/curr-afstand naar {@code target} met 2D-euclidische fallback — voor callers
     * die historisch hoogte negeren (EFC-threat). Het geodesische pad is inherent 3D
     * (routes lopen over hellingen/lifts); de fallback behoudt het oude 2D-gedrag.
     */
    public static double[] pairTo2dFallback(GameStateDto frame, CoordinatesDto prev,
                                            CoordinatesDto curr, CoordinatesDto target) {
        Optional<GeodesicField> field = fieldFor(frame);
        if (field.isPresent()) {
            double prevGeo = field.get().distanceOrNaN(prev, target);
            double currGeo = field.get().distanceOrNaN(curr, target);
            if (!Double.isNaN(prevGeo) && !Double.isNaN(currGeo)) {
                return new double[] {prevGeo, currGeo};
            }
        }
        return new double[] {
            RewardUtils.distance2d(prev, target),
            RewardUtils.distance2d(curr, target)
        };
    }

    private static Optional<GeodesicField> fieldFor(GameStateDto frame) {
        return GeodesicFieldRepository.forMap(MapKey.fromFrame(frame));
    }
}
