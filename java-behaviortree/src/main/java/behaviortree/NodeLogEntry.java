package behaviortree;

import java.time.Instant;

/**
 * Immutable log entry recorded for a single behavior tree node execution.
 *
 * @param timestamp ISO-8601 instant the entry was created
 * @param level     log level: {@code INFO}, {@code WARN}, or {@code ERROR}
 * @param message   human-readable log message
 */
public record NodeLogEntry(String timestamp, String level, String message) {

  /** Convenience factory that stamps the current instant as the timestamp. */
  public static NodeLogEntry of(String level, String message) {
    return new NodeLogEntry(Instant.now().toString(), level, message);
  }
}
