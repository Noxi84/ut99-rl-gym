package aiplay.recordlauncher.json;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class JsonService {
    private long jsonSessionId;          // puur voor mapnaam
    private long sessionStartMillis; // t0 voor elapsed

    public JsonService(String sessionId) {
        startNewSession(sessionId);
    }

    /**
     * Start echt een nieuwe sessie: nieuwe map + t0 reset.
     */
    public void startNewSession(String sessionId) {
        this.jsonSessionId = System.currentTimeMillis();
        this.sessionStartMillis = this.jsonSessionId;
        createJsonRecordingSessionsDirectory();
    }

    public void saveJson(String sessionId, RecorderModel gameStatus) {
        String directory = aiplay.runtime.config.SessionPaths.getSessionDir() + "/json-recording-sessions/" + jsonSessionId + "/";

        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, now - sessionStartMillis);

        // zero-padded zodat sorteren op naam == sorteren op tijd
        String filename = String.format("%013d.json", elapsed);

        File file = new File(directory + filename);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(gameStatus.getJsonString());
        } catch (IOException e) {
            System.err.println("Fout bij opslaan JSON-bestand: " + e.getMessage());
        }
    }

    public void createJsonRecordingSessionsDirectory() {
        String dirPath = aiplay.runtime.config.SessionPaths.getSessionDir() + "/json-recording-sessions/" + jsonSessionId + "/";
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Kon sessie-directory niet aanmaken: " + dirPath);
        } else {
            System.out.println("Created json recording directory: " + dirPath);
        }
    }
}
