package aiplay.dto;

public class PlayerPawnDto {
    public KeyboardMoveDto moveIdle;
    public KeyboardMoveDto moveForward;
    public KeyboardMoveDto moveForwardLeft;
    public KeyboardMoveDto moveForwardRight;
    public KeyboardMoveDto moveStrafeLeft;
    public KeyboardMoveDto moveStrafeRight;
    public KeyboardMoveDto moveBack;
    public KeyboardMoveDto moveBackLeft;
    public KeyboardMoveDto moveBackRight;
    public KeyboardMoveDto bJump;

    public PlayerPawnDto deepCopy() {
        PlayerPawnDto copy = new PlayerPawnDto();
        if (this.moveIdle != null) copy.moveIdle = this.moveIdle.deepCopy();
        if (this.moveForward != null) copy.moveForward = this.moveForward.deepCopy();
        if (this.moveForwardLeft != null) copy.moveForwardLeft = this.moveForwardLeft.deepCopy();
        if (this.moveForwardRight != null) copy.moveForwardRight = this.moveForwardRight.deepCopy();
        if (this.moveStrafeLeft != null) copy.moveStrafeLeft = this.moveStrafeLeft.deepCopy();
        if (this.moveStrafeRight != null) copy.moveStrafeRight = this.moveStrafeRight.deepCopy();
        if (this.moveBack != null) copy.moveBack = this.moveBack.deepCopy();
        if (this.moveBackLeft != null) copy.moveBackLeft = this.moveBackLeft.deepCopy();
        if (this.moveBackRight != null) copy.moveBackRight = this.moveBackRight.deepCopy();
        if (this.bJump != null) copy.bJump = this.bJump.deepCopy();
        return copy;
    }
}
