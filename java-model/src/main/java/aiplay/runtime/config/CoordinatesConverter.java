package aiplay.runtime.config;

import aiplay.config.global.MapNormConfig;
import aiplay.dto.CoordinatesDto;
import aiplay.runtime.context.MapKey;
import aiplay.util.NormalizationUtils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class CoordinatesConverter {

    private static final Logger LOG = Logger.getLogger(CoordinatesConverter.class.getName());
    private static final long WARN_INTERVAL_MS = 60_000L;
    private static final AtomicLong nextWarnX = new AtomicLong(0L);
    private static final AtomicLong nextWarnY = new AtomicLong(0L);
    private static final AtomicLong nextWarnZ = new AtomicLong(0L);

    private static void maybeWarnOutOfBounds(AtomicLong gate, String axis, double value, double center, double halfWidth) {
        long now = System.currentTimeMillis();
        long next = gate.get();
        if (now >= next && gate.compareAndSet(next, now + WARN_INTERVAL_MS)) {
            LOG.warning(axis + " outside map bounds: " + value + " (center=" + center + ", halfWidth=" + halfWidth + ")");
        }
    }

    public CoordinatesDto convert(String coordinates) {
        if (coordinates == null) {
            return null;
        }
        String trimmed = coordinates.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int firstComma = trimmed.indexOf(',');
        if (firstComma < 0) {
            return null;
        }
        int secondComma = trimmed.indexOf(',', firstComma + 1);
        if (secondComma < 0) {
            return null;
        }

        double x = parseDoubleSafe(trimmed, 0, firstComma);
        double y = parseDoubleSafe(trimmed, firstComma + 1, secondComma);
        double z = parseDoubleSafe(trimmed, secondComma + 1, trimmed.length());
        return convert(x, y, z);
    }

    private static double parseDoubleSafe(String value, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return 0.0;
        }
        while (startInclusive < endExclusive && Character.isWhitespace(value.charAt(startInclusive))) {
            startInclusive++;
        }
        while (endExclusive > startInclusive && Character.isWhitespace(value.charAt(endExclusive - 1))) {
            endExclusive--;
        }
        if (startInclusive >= endExclusive) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.substring(startInclusive, endExclusive));
        } catch (Exception ignore) {
            return 0.0;
        }
    }

    public CoordinatesDto convert(double x, double y, double z) {
        CoordinatesDto coordinatesDto = new CoordinatesDto();
        coordinatesDto.x = x;
        coordinatesDto.y = y;
        coordinatesDto.z = z;

        coordinatesDto.x_norm = NormalizationUtils.normalizeLocationX(x);
        coordinatesDto.y_norm = NormalizationUtils.normalizeLocationY(y);
        coordinatesDto.z_norm = NormalizationUtils.normalizeLocationZ(z);

        MapNormConfig map = ActiveMapConfigResolver.resolve(MapKey.active());
        double dx = Math.abs(x - map.centerX());
        double dy = Math.abs(y - map.centerY());
        double dz = Math.abs(z - map.centerZ());
        if (dx > map.halfWidthX()) {
            maybeWarnOutOfBounds(nextWarnX, "X", x, map.centerX(), map.halfWidthX());
        }
        if (dy > map.halfWidthY()) {
            maybeWarnOutOfBounds(nextWarnY, "Y", y, map.centerY(), map.halfWidthY());
        }
        if (dz > map.halfWidthZ()) {
            maybeWarnOutOfBounds(nextWarnZ, "Z", z, map.centerZ(), map.halfWidthZ());
        }

        return coordinatesDto;
    }
}
