package aiplay.recordlauncher.capture;

import aiplay.rl.MovementPrimitive;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global keyboard + mouse listener using JNativeHook. Tracks which keys and
 * mouse buttons are currently held down, independent of window focus.
 * Thread-safe — snapshot() can be called from the recording loop while
 * events arrive on the hook thread.
 *
 * On Linux, JNativeHook's getRawCode() returns X11 keysyms: ASCII values for
 * letter keys (e.g. 'z' = 122) and X11 keysym constants for special keys
 * (e.g. space = 32, Escape = 65307). This is keyboard-layout-independent —
 * the Z key on AZERTY returns rawCode=122 ('z'), not a QWERTY-based code.
 *
 * Mouse buttons are tracked via NativeMouseListener. Config values
 * "leftmouse" / "rightmouse" / "middlemouse" map to mouse button tracking.
 */
public final class KeyboardCapture implements NativeKeyListener, NativeMouseListener {

    /** Currently pressed X11 keysyms (via getRawCode). */
    private final Set<Integer> pressedRawCodes = ConcurrentHashMap.newKeySet();
    /** Currently pressed mouse buttons (NativeMouseEvent.BUTTON1 etc.). */
    private final Set<Integer> pressedMouseButtons = ConcurrentHashMap.newKeySet();

    // Negative sentinel values for mouse buttons (to distinguish from keysyms)
    private static final int MOUSE_LEFT = -1;
    private static final int MOUSE_RIGHT = -2;
    private static final int MOUSE_MIDDLE = -3;

    // Resolved X11 keysym values for each configured action
    private final int kcForward;
    private final int kcBackward;
    private final int kcLeft;
    private final int kcRight;
    private final int kcJump;
    private final int kcDuck;
    private final int kcFire;
    private final int kcAltFire;

    public KeyboardCapture(String forward, String backward, String left, String right,
                           String jump, String duck, String fire, String altFire) {
        this.kcForward = toKeysym(forward);
        this.kcBackward = toKeysym(backward);
        this.kcLeft = toKeysym(left);
        this.kcRight = toKeysym(right);
        this.kcJump = toKeysym(jump);
        this.kcDuck = toKeysym(duck);
        this.kcFire = toKeysym(fire);
        this.kcAltFire = toKeysym(altFire);
    }

    public void start() {
        Logger jnhLogger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        jnhLogger.setLevel(Level.WARNING);
        jnhLogger.setUseParentHandlers(false);

        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            System.out.println("KeyboardCapture started via X11 keysyms + mouse"
                    + " (forward=" + kcForward + " back=" + kcBackward
                    + " left=" + kcLeft + " right=" + kcRight + " jump=" + kcJump
                    + " duck=" + kcDuck + " fire=" + kcFire + " altfire=" + kcAltFire + ")");
        } catch (NativeHookException e) {
            System.err.println("KeyboardCapture failed to start: " + e.getMessage());
        }
    }

    public void stop() {
        GlobalScreen.removeNativeKeyListener(this);
        GlobalScreen.removeNativeMouseListener(this);
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException ignore) {
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        pressedRawCodes.add(e.getRawCode());
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        pressedRawCodes.remove(e.getRawCode());
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        pressedMouseButtons.add(e.getButton());
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        pressedMouseButtons.remove(e.getButton());
    }

    @Override
    public void nativeMouseClicked(NativeMouseEvent e) {
        // not needed — tracked via pressed/released
    }

    private boolean isPressed(int keysym) {
        if (keysym == MOUSE_LEFT) return pressedMouseButtons.contains(NativeMouseEvent.BUTTON1);
        if (keysym == MOUSE_RIGHT) return pressedMouseButtons.contains(NativeMouseEvent.BUTTON2);
        if (keysym == MOUSE_MIDDLE) return pressedMouseButtons.contains(NativeMouseEvent.BUTTON3);
        return pressedRawCodes.contains(keysym);
    }

    public String snapshotJson() {
        boolean fwd = isPressed(kcForward);
        boolean back = isPressed(kcBackward);
        boolean left = isPressed(kcLeft);
        boolean right = isPressed(kcRight);
        boolean jump = isPressed(kcJump);
        boolean duck = isPressed(kcDuck);
        boolean fire = isPressed(kcFire);
        boolean altFire = isPressed(kcAltFire);

        MovementPrimitive p = MovementPrimitive.fromLegacyKeyStates(fwd, back, left, right);
        return "{\"moveIdle\":" + (p == MovementPrimitive.IDLE)
                + ",\"moveForward\":" + (p == MovementPrimitive.FORWARD)
                + ",\"moveForwardLeft\":" + (p == MovementPrimitive.FORWARD_LEFT)
                + ",\"moveForwardRight\":" + (p == MovementPrimitive.FORWARD_RIGHT)
                + ",\"moveStrafeLeft\":" + (p == MovementPrimitive.STRAFE_LEFT)
                + ",\"moveStrafeRight\":" + (p == MovementPrimitive.STRAFE_RIGHT)
                + ",\"moveBack\":" + (p == MovementPrimitive.BACK)
                + ",\"moveBackLeft\":" + (p == MovementPrimitive.BACK_LEFT)
                + ",\"moveBackRight\":" + (p == MovementPrimitive.BACK_RIGHT)
                + ",\"bPressedJump\":" + jump
                + ",\"bDuck\":" + duck
                + ",\"bFire\":" + fire
                + ",\"bAltFire\":" + altFire + "}";
    }

    /**
     * Maps a key name (from config) to the X11 keysym that
     * JNativeHook's getRawCode() returns on Linux.
     * Single letters: ASCII value (e.g. "z" → 122).
     * Special keys: X11 keysym constant.
     */
    private static int toKeysym(String keyName) {
        if (keyName == null || keyName.isEmpty()) return -1;
        String k = keyName.toLowerCase().trim();

        // Single letter → ASCII value
        if (k.length() == 1 && k.charAt(0) >= 'a' && k.charAt(0) <= 'z') {
            return k.charAt(0); // 'a'=97 .. 'z'=122
        }

        // Mouse buttons
        switch (k) {
            case "leftmouse":  case "mouse1": return MOUSE_LEFT;
            case "rightmouse": case "mouse2": return MOUSE_RIGHT;
            case "middlemouse": case "mouse3": return MOUSE_MIDDLE;
        }

        // Special keys → X11 keysym constants
        switch (k) {
            case "space":                    return 32;       // XK_space
            case "shift":                    return 0xFFE1;   // XK_Shift_L
            case "control": case "ctrl":     return 0xFFE3;   // XK_Control_L
            case "alt":                      return 0xFFE9;   // XK_Alt_L
            case "up":                       return 0xFF52;   // XK_Up
            case "down":                     return 0xFF54;   // XK_Down
            case "left":                     return 0xFF51;   // XK_Left
            case "right":                    return 0xFF53;   // XK_Right
            case "enter": case "return":     return 0xFF0D;   // XK_Return
            case "tab":                      return 0xFF09;   // XK_Tab
            case "escape": case "esc":       return 0xFF1B;   // XK_Escape
            case "backspace":                return 0xFF08;   // XK_BackSpace
            case "delete": case "del":       return 0xFFFF;   // XK_Delete
            case "insert": case "ins":       return 0xFF63;   // XK_Insert
            default:
                System.err.println("KeyboardCapture: unknown key '" + keyName + "'");
                return -1;
        }
    }
}
