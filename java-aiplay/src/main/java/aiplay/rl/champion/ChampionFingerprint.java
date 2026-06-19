package aiplay.rl.champion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes sha256 over raw file bytes, matching Python's
 * {@code train.common.champion_store.compute_json_fingerprint}. Used to
 * verify that a champion snapshot was created against the same
 * features.json / model.json schema as is currently active — mismatch means
 * the snapshot's ONNX would be loaded against an incompatible feature
 * vector and must be hard-rejected (no silent fallback per project policy).
 */
public final class ChampionFingerprint {

    private ChampionFingerprint() {}

    public static String compute(Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read fingerprint source: " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
