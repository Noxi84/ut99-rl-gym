package aiplay.scanners.model.writer;

import aiplay.ut99webmodel.GameState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.*;

public class FileDao {

    /**
     * Tolerant JSON-parser voor recording-frames. UC-mod heeft historisch
     * trailing-comma bugs gehad (bv. SniperRifle pre-2026-05-17): laatste
     * createJsonField(..., true) gevolgd door createJsonHeaderEnd produceerde
     * "{..., \"x\": \"...\",}". Strict Jackson faalt daar. Met
     * ALLOW_TRAILING_COMMA blijft historic data parsen, en nieuwe UC builds
     * (na de fix in NeuralNetWebserver.uc:407) emit sowieso valide JSON.
     * INCLUDE_SOURCE_IN_LOCATION zet Jackson source-snippets aan in
     * exceptions zodat we voortaan exact zien WELK veld faalt.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .enable(com.fasterxml.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    /**
     * Lees een GameState uit een InputStream (bv. uit een ZIP entry).
     *
     * @param timestampMillis timestamp die je wil associëren aan dit frame
     */
    public GameState readStream(InputStream inputStream, long timestampMillis) throws Exception {
        GameState gameState = mapper.readValue(inputStream, GameState.class);
        gameState.timestampMillis = timestampMillis;
        return gameState;
    }

    public GameState readFromJsonString(String jsonString) throws JsonProcessingException {
        GameState gameState = mapper.readValue(jsonString, GameState.class);
        return gameState;
    }

    public void writeFileOverwrite(String filename, String csvLine) {
        ensureParentDirectoryExists(filename);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, false))) {
            writer.write(csvLine);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException("Kon bestand niet overschrijven: " + filename, e);
        }
    }

    public void writeFileAppend(String filename, String csvLine) {
        ensureParentDirectoryExists(filename);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
            writer.write(csvLine);
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException("Kon bestand niet aanvullen: " + filename, e);
        }
    }

    private void ensureParentDirectoryExists(String filename) {
        File file = new File(filename);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new RuntimeException("Kon directory niet aanmaken: " + parentDir.getAbsolutePath());
            }
        }
    }
}
