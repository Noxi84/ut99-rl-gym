package behaviortree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe ring buffer that stores the most-recent log entries for a single
 * behavior tree node.
 *
 * <p>When the buffer reaches its capacity, the oldest entry is dropped to make
 * room for the newest one, so the buffer always holds at most {@code capacity}
 * entries.
 *
 * <p>Thread-safety: all methods are {@code synchronized}; the buffer is safe
 * to write from the BT-tick thread and read from an HTTP-request thread
 * simultaneously.
 */
public final class NodeLogBuffer {

  /** Maximum number of entries retained. Older entries are evicted first. */
  private final int capacity;
  private final Deque<NodeLogEntry> entries;

  /**
   * @param capacity maximum number of log entries to retain (must be &gt; 0)
   */
  public NodeLogBuffer(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
    this.capacity = capacity;
    this.entries = new ArrayDeque<>(capacity);
  }

  /**
   * Appends a new entry. If the buffer is full, the oldest entry is evicted.
   *
   * @param level   log level (e.g., {@code "INFO"}, {@code "WARN"}, {@code "ERROR"})
   * @param message human-readable message
   */
  public synchronized void add(String level, String message) {
    if (entries.size() >= capacity) {
      entries.pollFirst();
    }
    entries.addLast(NodeLogEntry.of(level, message));
  }

  /**
   * Returns a snapshot of all retained log entries, oldest first.
   * The returned list is a defensive copy — safe to iterate without holding the lock.
   */
  public synchronized List<NodeLogEntry> entries() {
    return new ArrayList<>(entries);
  }

  /**
   * Returns the number of entries currently in the buffer.
   */
  public synchronized int size() {
    return entries.size();
  }

  /** Removes all entries from the buffer. */
  public synchronized void clear() {
    entries.clear();
  }
}
