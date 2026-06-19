package aiplay.dto;

/**
 * UT99 Pawn physics state. Mirrors UScript {@code EPhysics} enum values. The on-wire
 * representation is the integer ordinal; only the pawn-relevant subset is named here.
 */
public enum Ut99PhysicsType {
    NONE(0),
    WALKING(1),
    FALLING(2),
    SWIMMING(3);

    public final int code;

    Ut99PhysicsType(int code) {
        this.code = code;
    }

    public static Ut99PhysicsType fromInt(int code) {
        for (Ut99PhysicsType t : values()) {
            if (t.code == code) return t;
        }
        return NONE;
    }
}
