package aiplay.config.global;

/**
 * Map normalization parameters for the active map. Resolved by ActiveMapConfigResolver from
 * {@code resources/config/maps/<name>.json → map_norm}.
 *
 * <p>Bounds are expressed as signed world-space min/max per axis, so the map need not be
 * centered at the origin (the mapper can place geometry anywhere). Derived quantities:
 * <ul>
 *   <li>{@link #centerX()}, {@link #centerY()}, {@link #centerZ()} — geometric center.
 *   <li>{@link #halfWidthX()}, etc. — half-extent per axis, used to map world-coords to
 *       {@code [-1, +1]} via {@code (x - centerX) / halfWidthX}.
 *   <li>{@link #fullDiagonal()} — total diagonal used to normalize distances.
 * </ul>
 *
 * <p>{@code symmetric} indicates whether the two team flag-bases sit mirror-symmetric around
 * the map center — the condition for {@code CanonicalPerspectiveNormalizer}'s 180° rotation
 * to be valid. {@code edge} and {@code k} control the edge-squash applied to normalized
 * locations (see {@code NormalizationUtils}).
 */
public record MapNormConfig(
    double boundsMinX, double boundsMaxX,
    double boundsMinY, double boundsMaxY,
    double boundsMinZ, double boundsMaxZ,
    double edge,
    double k,
    boolean symmetric
) {
    public double centerX() { return (boundsMinX + boundsMaxX) / 2.0; }
    public double centerY() { return (boundsMinY + boundsMaxY) / 2.0; }
    public double centerZ() { return (boundsMinZ + boundsMaxZ) / 2.0; }

    public double halfWidthX() { return (boundsMaxX - boundsMinX) / 2.0; }
    public double halfWidthY() { return (boundsMaxY - boundsMinY) / 2.0; }
    public double halfWidthZ() { return (boundsMaxZ - boundsMinZ) / 2.0; }

    public double fullDiagonal() {
        double dx = boundsMaxX - boundsMinX;
        double dy = boundsMaxY - boundsMinY;
        double dz = boundsMaxZ - boundsMinZ;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
}
