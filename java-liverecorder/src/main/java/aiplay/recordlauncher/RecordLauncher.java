package aiplay.recordlauncher;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.RecordingKeysConfig;
import aiplay.runtime.config.SessionPaths;
import aiplay.recordlauncher.capture.KeyboardCapture;
import aiplay.recordlauncher.x11.UT99WindowDetector;

public class RecordLauncher {

    public static void main(String[] args) {
        String sessionId = "default";
        SessionPaths.ensureSessionDirsExist();

        RecordingKeysConfig keys = GlobalConfigRepository.shared().recording().keys();
        String forward = keys.moveForward();
        String backward = keys.moveBackward();
        String left = keys.moveLeft();
        String right = keys.moveRight();
        String jump = keys.jump();
        String duck = keys.duck();
        // Recorder uses mouse for fire (LeftMouse=Fire, RightMouse=AltFire in User.ini)
        String fire = "leftmouse";
        String altFire = "rightmouse";

        KeyboardCapture keyboardCapture = new KeyboardCapture(forward, backward, left, right, jump, duck, fire, altFire);
        keyboardCapture.start();

        Runtime.getRuntime().addShutdownHook(new Thread(keyboardCapture::stop));

        JsonFacade jsonFacade = new JsonFacade(sessionId, keyboardCapture);
        UT99WindowDetector.waitForUT99WindowActive();

        System.out.println("Recording started. KeyboardCapture active. Press Ctrl+C to stop.");

        while (true) {
            jsonFacade.readAndSaveGameStatus(sessionId);
        }
    }
}
