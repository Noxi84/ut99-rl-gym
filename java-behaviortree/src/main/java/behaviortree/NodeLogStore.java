package behaviortree;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of per-node log buffers, keyed by node ID.
 *
 * <p>Buffers are created lazily on first access and never destroyed during normal
 * operation. Call {@link #clearAll()} when the behavior tree is torn down to
 * free stale entries from the previous run.
 *
 * <p>Thread-safety: the map itself is a {@link ConcurrentHashMap}; individual
 * buffer operations are synchronized inside {@link NodeLogBuffer}.
 */
public final class NodeLogStore {

  /** Maximum entries retained per node. */
  private static final int BUFFER_CAPACITY = 200;

  private static final ConcurrentHashMap<String, NodeLogBuffer> STORE =
      new ConcurrentHashMap<>();

  private NodeLogStore() {}

  /**
   * Returns the log buffer for {@code nodeId}, creating it if it does not yet exist.
   *
   * @param nodeId non-null node identifier
   * @return existing or newly-created {@link NodeLogBuffer}
   */
  public static NodeLogBuffer getOrCreate(String nodeId) {
    return STORE.computeIfAbsent(nodeId, id -> new NodeLogBuffer(BUFFER_CAPACITY));
  }

  /**
   * Returns the log buffer for {@code nodeId}, or {@code null} if none exists.
   *
   * @param nodeId node identifier to look up
   */
  public static NodeLogBuffer get(String nodeId) {
    return STORE.get(nodeId);
  }

  /**
   * Returns all log entries for {@code nodeId}, or an empty list if the node
   * has no buffer or no entries.
   *
   * @param nodeId node identifier to look up
   */
  public static List<NodeLogEntry> entries(String nodeId) {
    NodeLogBuffer buf = STORE.get(nodeId);
    return buf == null ? List.of() : buf.entries();
  }

  /**
   * Clears all log entries for {@code nodeId}. No-op if no buffer exists.
   *
   * @param nodeId node identifier to clear
   */
  public static void clear(String nodeId) {
    NodeLogBuffer buf = STORE.get(nodeId);
    if (buf != null) buf.clear();
  }

  /**
   * Clears log entries for every registered node.
   * Called when the behavior tree is stopped / rebuilt.
   */
  public static void clearAll() {
    for (NodeLogBuffer buf : STORE.values()) {
      buf.clear();
    }
  }

  /** Returns all currently registered node IDs (for diagnostics). */
  public static Collection<String> registeredNodeIds() {
    return STORE.keySet();
  }
}
