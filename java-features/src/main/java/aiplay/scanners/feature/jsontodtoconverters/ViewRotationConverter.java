package aiplay.scanners.feature.jsontodtoconverters;

import aiplay.dto.ViewRotationDto;
import aiplay.util.NormalizationUtils;

public class ViewRotationConverter {

    private static final boolean DEBUG_LOG = Boolean.getBoolean("ut99.debug.viewrotation");
    private static long convertCount = 0;

    public ViewRotationDto convert(String coordinates) {
        ViewRotationDto dto = new ViewRotationDto();
        if (coordinates == null) {
            return dto;
        }
        int firstComma = coordinates.indexOf(',');
        if (firstComma < 0) {
            return dto;
        }
        int secondComma = coordinates.indexOf(',', firstComma + 1);
        String first = coordinates.substring(0, firstComma).trim();
        String second = (secondComma >= 0
            ? coordinates.substring(firstComma + 1, secondComma)
            : coordinates.substring(firstComma + 1)).trim();
        // x is 2e, UT swapt X/Y
        dto.x = parseIntSafe(second);
        dto.y = parseIntSafe(first);

        double ang = NormalizationUtils.viewRotationXToRad(dto.x);
        dto.x_sin = Math.sin(ang);
        dto.x_cos = Math.cos(ang);
        dto.y_norm = NormalizationUtils.normalizeViewRotationY(dto.y);

        if (DEBUG_LOG && (convertCount++ < 5 || convertCount % 10000 == 0)) {
            System.out.println("VR_CONVERT_DIAG raw=\"" + coordinates + "\" x=" + dto.x + " y=" + dto.y
                + " sin=" + String.format("%.4f", dto.x_sin) + " cos=" + String.format("%.4f", dto.x_cos)
                + " y_norm=" + String.format("%.4f", dto.y_norm));
        }

        return dto;
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignore) {
            try {
                return (int) Math.round(Double.parseDouble(value.trim()));
            } catch (Exception ignore2) {
                return 0;
            }
        }
    }
}
