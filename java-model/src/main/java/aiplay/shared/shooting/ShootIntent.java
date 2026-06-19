package aiplay.shared.shooting;

public record ShootIntent(boolean fire, boolean altFire, long timestampMs,
                          boolean fireSuppressedByCooldown) {

    public ShootIntent(boolean fire, boolean altFire, long timestampMs) {
        this(fire, altFire, timestampMs, false);
    }
}
