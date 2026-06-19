package aiplay.dto;

/**
 * Line-of-sight rays from bot toward a flag, fired in a horizontal cone.
 * Each value is a ratio [0..1]: 1.0 = ray reaches the flag (clear), lower = blocked earlier.
 * Angles are relative to the direct line from bot to flag.
 */
public class FlagLosDto {
    public float center;    // 0° — direct line to flag
    public float left15;    // -15°
    public float right15;   // +15°
    public float left30;    // -30°
    public float right30;   // +30°
    public float left45;    // -45°
    public float right45;   // +45°

    public static FlagLosDto parse(String csv) {
        FlagLosDto dto = new FlagLosDto();
        if (csv == null || csv.isEmpty()) return dto;
        String[] parts = csv.split(",");
        if (parts.length >= 7) {
            dto.center  = parseFloat(parts[0]);
            dto.left15  = parseFloat(parts[1]);
            dto.right15 = parseFloat(parts[2]);
            dto.left30  = parseFloat(parts[3]);
            dto.right30 = parseFloat(parts[4]);
            dto.left45  = parseFloat(parts[5]);
            dto.right45 = parseFloat(parts[6]);
        }
        return dto;
    }

    private static float parseFloat(String s) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0.0f; }
    }

    public FlagLosDto deepCopy() {
        FlagLosDto c = new FlagLosDto();
        c.center  = this.center;
        c.left15  = this.left15;
        c.right15 = this.right15;
        c.left30  = this.left30;
        c.right30 = this.right30;
        c.left45  = this.left45;
        c.right45 = this.right45;
        return c;
    }
}
