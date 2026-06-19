package aiplay.util;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.MapNormConfig;
import aiplay.runtime.config.ActiveMapConfigResolver;
import aiplay.runtime.context.MapKey;

public class NormalizationUtils {

    private static final double TAU = 2.0 * Math.PI;

    /**
     * Conventie: sin>0 = target rechts van kijkrichting.
     */
    public static final boolean RIGHT_IS_POSITIVE = true;

    /* ==================== ViewRotation (pitch/yaw) ==================== */

    /**
     * Normalize UT99 pitch to [-1, 1] with 0 = center (straight ahead).
     * UT99 pitch: 0/65535 = center, 18000 = max up (+1.0), 49152 = max down (-0.91).
     * Positive = looking up, negative = looking down.
     */
    public static double normalizeViewRotationY(int rotY) {
        int signed;
        if (rotY <= 18000) {
            signed = rotY;             // 0..18000 = center..max_up
        } else if (rotY >= 49152) {
            signed = rotY - 65536;     // 49152..65535 → -16384..-1 = max_down..near_center
        } else {
            signed = 0;                // invalid range, treat as center
        }
        return signed / 18000.0;
    }

    public static double normalizeViewRotationX(int viewRotationX) {
        double MOD = GlobalConfigRepository.shared().view().maxViewrotationX();
        return (((viewRotationX % MOD) + MOD) % MOD) / MOD;
    }

    public static double viewRotationXToRad(int viewRotationX) {
        return normalizeViewRotationX(viewRotationX) * TAU;
    }

    /* ==================== Posities & afstanden ==================== */

    public static double softDistance01(double d, double tau) {
        if (!(Double.isFinite(d)) || d <= 0.0) return 0.0;
        double t = Math.max(1e-9, tau);
        return 1.0 - Math.exp(-d / t);
    }

    public static double normalizeDistance3D(double distance) {
        if (!Double.isFinite(distance) || distance <= 0.0) {
            return 0.0;
        }
        double fullDiag = ActiveMapConfigResolver.resolve(MapKey.active()).fullDiagonal();
        if (!Double.isFinite(fullDiag) || fullDiag <= 1e-9) {
            return 0.0;
        }
        return clamp01(distance / fullDiag);
    }

    /**
     * Locaties stabiel naar [-1,1] met "edge squash", relatief tot het map-center.
     * Gebruikt {@code (value - centerAxis) / halfWidthAxis} zodat asymmetrische maps
     * (waarvan de mapper het 0-punt niet in het center heeft gelegd) correct worden
     * genormaliseerd rond hun eigen center.
     */
    public static double normalizeLocationX(double value) {
        MapNormConfig map = ActiveMapConfigResolver.resolve(MapKey.active());
        return normalizeLocationEdgeSquash(value, map.centerX(), map.halfWidthX());
    }

    public static double normalizeLocationY(double value) {
        MapNormConfig map = ActiveMapConfigResolver.resolve(MapKey.active());
        return normalizeLocationEdgeSquash(value, map.centerY(), map.halfWidthY());
    }

    public static double normalizeLocationZ(double value) {
        MapNormConfig map = ActiveMapConfigResolver.resolve(MapKey.active());
        return normalizeLocationEdgeSquash(value, map.centerZ(), map.halfWidthZ());
    }

    private static double normalizeLocationEdgeSquash(double value, double center, double halfWidth) {
        if (!(Double.isFinite(value)) || !(Double.isFinite(halfWidth)) || halfWidth <= 0.0) {
            return 0.0;
        }

        MapNormConfig map = ActiveMapConfigResolver.resolve(MapKey.active());
        double edge = map.edge();
        double k = map.k();

        // sanity clamps for configs
        if (edge < 0.50) edge = 0.50;
        if (edge > 0.999) edge = 0.999;
        if (k < 0.5) k = 0.5;
        if (k > 20.0) k = 20.0;

        double v = (value - center) / halfWidth;
        if (v < -1.0) v = -1.0;
        if (v > 1.0) v = 1.0;

        double a = Math.abs(v);
        if (a <= edge) return v;

        double t = (a - edge) / (1.0 - edge); // 0..1
        double tanhK = Math.tanh(k);
        double s;
        if (tanhK < 1e-12) {
            s = edge; // extreme edge case
        } else {
            s = edge + (1.0 - edge) * Math.tanh(k * t) / tanhK;
        }
        return Math.copySign(s, v);
    }

    /* ==================== Relatief t.o.v. view ==================== */

    public static double[] relativeAngleSinCos(int viewRotationX,
                                               double playerX, double playerY,
                                               double targetX, double targetY) {
        double yaw = viewRotationXToRad(viewRotationX);
        double fx = Math.cos(yaw), fy = Math.sin(yaw);          // forward
        double rx = RIGHT_IS_POSITIVE ? fy : -fy;              // right
        double ry = RIGHT_IS_POSITIVE ? -fx : fx;

        double dx = targetX - playerX, dy = targetY - playerY;
        double len = Math.hypot(dx, dy);
        double tx = (len > 1e-9 ? dx / len : fx);
        double ty = (len > 1e-9 ? dy / len : fy);

        double cos = clamp(fx * tx + fy * ty, -1.0, 1.0);
        double sin = clamp(rx * tx + ry * ty, -1.0, 1.0);

        double n = Math.hypot(sin, cos);
        if (n < 1e-9) return new double[]{0.0, 1.0};
        return new double[]{sin / n, cos / n};
    }

    public static double[] stabilizeSinCosNear(double sin, double cos, double distanceNorm, boolean hasLineOfSight) {
        if (!hasLineOfSight) return new double[]{sin, cos};
        double nearDist = GlobalConfigRepository.shared().gameplay().nearDistNorm();
        if (distanceNorm >= 0.0 && distanceNorm < nearDist) {
            double t = clamp01(distanceNorm / nearDist);
            double s = t * sin;
            double c = (1.0 - t) + t * cos;
            double n = Math.hypot(s, c);
            return (n > 1e-12) ? new double[]{s / n, c / n} : new double[]{0.0, 1.0};
        }
        return new double[]{sin, cos};
    }

    public static double[] forwardRightDistNorm(double sin, double cos, double distanceNorm) {
        double d = clamp01(distanceNorm);
        double f = clampM11(cos) * d;
        double r = clampM11(sin) * d;
        return new double[]{f, r};
    }

    /* ==================== Utils ==================== */
    public static double clampM11(double v) {
        return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}
