package aiplay.dto;

/**
 * UT99 dodge state from Actor.DodgeDir enum.
 * Mapped from integer values in the webservice JSON.
 */
public enum DodgeState {
    NONE(0),
    LEFT(1),
    RIGHT(2),
    FORWARD(3),
    BACK(4),
    ACTIVE(5),
    DONE(6);

    public final int value;

    DodgeState(int value) {
        this.value = value;
    }

    public static DodgeState fromInt(int v) {
        for (DodgeState ds : values()) {
            if (ds.value == v) return ds;
        }
        return NONE;
    }

}
