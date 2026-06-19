package aiplay.recordlauncher;

import aiplay.recordlauncher.capture.KeyboardCapture;
import aiplay.recordlauncher.json.JsonService;
import aiplay.recordlauncher.json.RecorderModel;
import aiplay.recordlauncher.json.RecorderService;

public class JsonFacade {

    private final JsonService jsonService;
    private final UT99WebserviceDao neuralNetWebserviceDao;
    private final RecorderService recorderService;
    private final KeyboardCapture keyboardCapture;
    private String lastJsonOutput = "";

    public JsonFacade(String sessionId, KeyboardCapture keyboardCapture) {
        this.jsonService = new JsonService(sessionId);
        this.neuralNetWebserviceDao = new UT99WebserviceDao();
        this.recorderService = new RecorderService();
        this.keyboardCapture = keyboardCapture;
        jsonService.createJsonRecordingSessionsDirectory();
    }

    public String readAndSaveGameStatus(String sessionId) {
        // Capture keyboard state as close to the webservice read as possible
        String kbSnapshot = keyboardCapture != null ? keyboardCapture.snapshotJson() : null;
        String newJsonOutput = neuralNetWebserviceDao.readGameStatusJsonFromURL();

        if (!newJsonOutput.equals(lastJsonOutput)) {
            if (!lastJsonOutput.isEmpty()) {
                RecorderModel oldStatus = recorderService.createGameStatus(lastJsonOutput);
                RecorderModel newStatus = recorderService.createGameStatus(newJsonOutput);

                // Bij vlag-inlevering → nieuwe sessie
                if (oldStatus.getHasFlag() == 1 && newStatus.getHasFlag() == 0) {
                    System.out.println("Vlag binnengebracht! Nieuwe sessie gestart.");
                    jsonService.createJsonRecordingSessionsDirectory();
                }

                // Inject keyboard capture into JSON before saving
                String jsonToSave = injectKeyboardCapture(newJsonOutput, kbSnapshot);
                newStatus.setJsonString(jsonToSave);
                jsonService.saveJson(sessionId, newStatus);
            }
            lastJsonOutput = newJsonOutput;
            return newJsonOutput;
        }

        return null; // Geen verandering
    }

    /**
     * Injects "KeyboardCapture": {...} at the end of the root JSON object,
     * right before the closing brace. Fast string operation, no JSON parsing.
     */
    private String injectKeyboardCapture(String json, String kbSnapshot) {
        if (kbSnapshot == null || json == null) return json;
        int lastBrace = json.lastIndexOf('}');
        if (lastBrace < 0) return json;
        return json.substring(0, lastBrace)
                + ",\"KeyboardCapture\":" + kbSnapshot
                + "}";
    }
}
