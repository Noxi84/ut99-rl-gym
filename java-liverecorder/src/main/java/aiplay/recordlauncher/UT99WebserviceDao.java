package aiplay.recordlauncher;

import aiplay.runtime.config.NeuralNetEndpointResolver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal HTTP JSON fetcher for the legacy UWeb game-state endpoint.
 *
 * <p>The play path uses the binary UDP state channel (see aiplay.play.udpstate.UdpStateReceiver in java-aiplay).
 * This class lives in the java-liverecorder module and is only used by {@link RecordLauncher}
 * to record human gameplay sessions, where no UDP sender is running.
 */
public class UT99WebserviceDao {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(250))
            .build();

    private final AtomicLong lastErrorLogMs = new AtomicLong(0L);

    public String readGameStatusJsonFromURL() {
        String url = NeuralNetEndpointResolver.resolve();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(500))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
            }
            String body = response.body();
            return (body != null && !body.isBlank()) ? body : emptyShape();
        } catch (Exception e) {
            rateLimitedErr("Fout bij ophalen game status JSON: " + e.getMessage());
            return emptyShape();
        }
    }

    private static String emptyShape() {
        return "{\"MapInfo\":{},\"Flags\":[],\"Players\":[]}";
    }

    private void rateLimitedErr(String msg) {
        long now = System.currentTimeMillis();
        long prev = lastErrorLogMs.get();
        if (now - prev < 2000L) return;
        if (!lastErrorLogMs.compareAndSet(prev, now)) return;
        System.err.println(msg);
    }
}
