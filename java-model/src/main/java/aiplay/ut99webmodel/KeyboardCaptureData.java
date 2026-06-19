package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JNativeHook keyboard capture data recorded alongside UT99 game state.
 * Contains the movement primitive (one-hot) and action states at time of recording.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyboardCaptureData {
    public Boolean moveIdle;
    public Boolean moveForward;
    public Boolean moveForwardLeft;
    public Boolean moveForwardRight;
    public Boolean moveStrafeLeft;
    public Boolean moveStrafeRight;
    public Boolean moveBack;
    public Boolean moveBackLeft;
    public Boolean moveBackRight;
    public Boolean bPressedJump;
    public Boolean bDuck;
    public Boolean bFire;
    public Boolean bAltFire;

    // Legacy fields for backward compatibility with old recordings
    public Boolean bWasForward;
    public Boolean bWasBack;
    public Boolean bWasLeft;
    public Boolean bWasRight;
}
