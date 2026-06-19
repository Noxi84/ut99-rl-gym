package aiplay.rl;

import aiplay.dto.GameStateDto;
import aiplay.dto.KeyboardMoveDto;
import aiplay.dto.PlayerPawnDto;

public enum MovementPrimitive {
    IDLE("moveIdle"),
    FORWARD("moveForward"),
    FORWARD_LEFT("moveForwardLeft"),
    FORWARD_RIGHT("moveForwardRight"),
    STRAFE_LEFT("moveStrafeLeft"),
    STRAFE_RIGHT("moveStrafeRight"),
    BACK("moveBack"),
    BACK_LEFT("moveBackLeft"),
    BACK_RIGHT("moveBackRight"),
    FORWARD_DODGE_LEFT("moveForward_dodgeLeft"),
    FORWARD_DODGE_RIGHT("moveForward_dodgeRight"),
    BACK_DODGE_LEFT("moveBack_dodgeLeft"),
    BACK_DODGE_RIGHT("moveBack_dodgeRight"),
    STRAFE_LEFT_DODGE_FORWARD("moveStrafeLeft_dodgeForward"),
    STRAFE_LEFT_DODGE_BACK("moveStrafeLeft_dodgeBack"),
    STRAFE_RIGHT_DODGE_FORWARD("moveStrafeRight_dodgeForward"),
    STRAFE_RIGHT_DODGE_BACK("moveStrafeRight_dodgeBack");

    public static final int COUNT = values().length;

    /** All non-dodge locomotion values (9 entries, same order as enum). */
    public static final MovementPrimitive[] LOCOMOTION_VALUES = {
        IDLE, FORWARD, FORWARD_LEFT, FORWARD_RIGHT,
        STRAFE_LEFT, STRAFE_RIGHT, BACK, BACK_LEFT, BACK_RIGHT
    };

    private final String featureId;

    MovementPrimitive(String featureId) {
        this.featureId = featureId;
    }

    public String getFeatureId() {
        return featureId;
    }

    public static MovementPrimitive fromFeatureId(String featureId) {
        for (MovementPrimitive primitive : values()) {
            if (primitive.featureId.equals(featureId)) {
                return primitive;
            }
        }
        return null;
    }

    public static MovementPrimitive fromGameState(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null) {
            return IDLE;
        }
        PlayerPawnDto pawn = frame.playerPawn.playerPawn;
        if (pawn == null) {
            return IDLE;
        }

        // Determine base movement direction
        MovementPrimitive base;
        if (pawn.moveForward != null && pawn.moveForward.value) base = FORWARD;
        else if (pawn.moveForwardLeft != null && pawn.moveForwardLeft.value) base = FORWARD_LEFT;
        else if (pawn.moveForwardRight != null && pawn.moveForwardRight.value) base = FORWARD_RIGHT;
        else if (pawn.moveStrafeLeft != null && pawn.moveStrafeLeft.value) base = STRAFE_LEFT;
        else if (pawn.moveStrafeRight != null && pawn.moveStrafeRight.value) base = STRAFE_RIGHT;
        else if (pawn.moveBack != null && pawn.moveBack.value) base = BACK;
        else if (pawn.moveBackLeft != null && pawn.moveBackLeft.value) base = BACK_LEFT;
        else if (pawn.moveBackRight != null && pawn.moveBackRight.value) base = BACK_RIGHT;
        else base = IDLE;

        // Combine with dodge direction if actively dodging
        // activeDodgeDir is set by DodgeDirTrackingEnricher: 1=fwd, 2=back, 3=left, 4=right
        int dodgeDir = frame.playerPawn.activeDodgeDir;
        if (dodgeDir > 0) {
            MovementPrimitive compound = compoundDodge(base, dodgeDir);
            if (compound != null) return compound;
        }

        return base;
    }

    /**
     * Combine a base movement with a dodge direction into a compound primitive.
     * Only valid combinations exist (movement perpendicular to dodge).
     * Returns null if no valid compound exists.
     */
    private static MovementPrimitive compoundDodge(MovementPrimitive base, int dodgeDir) {
        return switch (base) {
            case FORWARD, FORWARD_LEFT, FORWARD_RIGHT -> switch (dodgeDir) {
                case 3 -> FORWARD_DODGE_LEFT;   // dodge left
                case 4 -> FORWARD_DODGE_RIGHT;  // dodge right
                default -> null;
            };
            case BACK, BACK_LEFT, BACK_RIGHT -> switch (dodgeDir) {
                case 3 -> BACK_DODGE_LEFT;      // dodge left
                case 4 -> BACK_DODGE_RIGHT;     // dodge right
                default -> null;
            };
            case STRAFE_LEFT -> switch (dodgeDir) {
                case 1 -> STRAFE_LEFT_DODGE_FORWARD;  // dodge forward
                case 2 -> STRAFE_LEFT_DODGE_BACK;     // dodge back
                default -> null;
            };
            case STRAFE_RIGHT -> switch (dodgeDir) {
                case 1 -> STRAFE_RIGHT_DODGE_FORWARD; // dodge forward
                case 2 -> STRAFE_RIGHT_DODGE_BACK;    // dodge back
                default -> null;
            };
            default -> null;
        };
    }

    public static MovementPrimitive fromLegacyKeyStates(boolean forward, boolean back, boolean left, boolean right) {
        if (forward) {
            if (left && !right) {
                return FORWARD_LEFT;
            }
            if (right && !left) {
                return FORWARD_RIGHT;
            }
            return FORWARD;
        }
        if (back) {
            if (left && !right) {
                return BACK_LEFT;
            }
            if (right && !left) {
                return BACK_RIGHT;
            }
            return BACK;
        }
        if (left && !right) {
            return STRAFE_LEFT;
        }
        if (right && !left) {
            return STRAFE_RIGHT;
        }
        return IDLE;
    }

    public boolean isForwardIntent() {
        MovementPrimitive loco = getLocomotionComponent();
        return loco == FORWARD || loco == FORWARD_LEFT || loco == FORWARD_RIGHT;
    }

    public boolean isBackIntent() {
        MovementPrimitive loco = getLocomotionComponent();
        return loco == BACK || loco == BACK_LEFT || loco == BACK_RIGHT;
    }

    public boolean isLeftIntent() {
        MovementPrimitive loco = getLocomotionComponent();
        return loco == STRAFE_LEFT || loco == FORWARD_LEFT || loco == BACK_LEFT;
    }

    public boolean isRightIntent() {
        MovementPrimitive loco = getLocomotionComponent();
        return loco == STRAFE_RIGHT || loco == FORWARD_RIGHT || loco == BACK_RIGHT;
    }

    public boolean isIdle() {
        return this == IDLE;
    }

    public boolean isDodge() {
        return getDodgeDir() != 0;
    }

    /** Get the KeyboardMoveDto field on PlayerPawnDto for this locomotion primitive. */
    public KeyboardMoveDto getMoveDtoFromPawn(PlayerPawnDto p) {
        if (p == null) return null;
        return switch (this) {
            case IDLE -> p.moveIdle;
            case FORWARD -> p.moveForward;
            case FORWARD_LEFT -> p.moveForwardLeft;
            case FORWARD_RIGHT -> p.moveForwardRight;
            case STRAFE_LEFT -> p.moveStrafeLeft;
            case STRAFE_RIGHT -> p.moveStrafeRight;
            case BACK -> p.moveBack;
            case BACK_LEFT -> p.moveBackLeft;
            case BACK_RIGHT -> p.moveBackRight;
            default -> null;
        };
    }

    /** Set the KeyboardMoveDto field on PlayerPawnDto for this locomotion primitive. */
    public void setMoveDtoOnPawn(PlayerPawnDto p, KeyboardMoveDto dto) {
        if (p == null) return;
        switch (this) {
            case IDLE -> p.moveIdle = dto;
            case FORWARD -> p.moveForward = dto;
            case FORWARD_LEFT -> p.moveForwardLeft = dto;
            case FORWARD_RIGHT -> p.moveForwardRight = dto;
            case STRAFE_LEFT -> p.moveStrafeLeft = dto;
            case STRAFE_RIGHT -> p.moveStrafeRight = dto;
            case BACK -> p.moveBack = dto;
            case BACK_LEFT -> p.moveBackLeft = dto;
            case BACK_RIGHT -> p.moveBackRight = dto;
            default -> { }
        }
    }

    /**
     * Returns dodge direction for UT99 (1=fwd, 2=back, 3=left, 4=right), or 0 if not a dodge.
     * Works for both pure dodge and compound movement+dodge primitives.
     */
    public int getDodgeDir() {
        return switch (this) {
            case STRAFE_LEFT_DODGE_FORWARD, STRAFE_RIGHT_DODGE_FORWARD -> 1;
            case STRAFE_LEFT_DODGE_BACK, STRAFE_RIGHT_DODGE_BACK -> 2;
            case FORWARD_DODGE_LEFT, BACK_DODGE_LEFT -> 3;
            case FORWARD_DODGE_RIGHT, BACK_DODGE_RIGHT -> 4;
            default -> 0;
        };
    }

    /**
     * For compound movement+dodge primitives, returns the locomotion component.
     * For pure locomotion or pure dodge, returns this.
     */
    public MovementPrimitive getLocomotionComponent() {
        return switch (this) {
            case FORWARD_DODGE_LEFT, FORWARD_DODGE_RIGHT -> FORWARD;
            case BACK_DODGE_LEFT, BACK_DODGE_RIGHT -> BACK;
            case STRAFE_LEFT_DODGE_FORWARD, STRAFE_LEFT_DODGE_BACK -> STRAFE_LEFT;
            case STRAFE_RIGHT_DODGE_FORWARD, STRAFE_RIGHT_DODGE_BACK -> STRAFE_RIGHT;
            default -> this;
        };
    }
}
