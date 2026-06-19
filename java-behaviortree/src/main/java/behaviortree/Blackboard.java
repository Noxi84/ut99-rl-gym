package behaviortree;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A blackboard is a shared memory space for behavior tree nodes to store and retrieve data.
 * It allows nodes to communicate with each other through shared state.
 */
public class Blackboard {
  private final Map<String, Object> data = new ConcurrentHashMap<>();

  /**
   * Sets a value in the blackboard.
   *
   * @param key   The key to store the value under
   * @param value The value to store
   */
  public void set(String key, Object value) {
    data.put(key, value);
  }

  /**
   * Gets a value from the blackboard.
   *
   * @param key The key to retrieve
   * @param <T> The expected type of the value
   * @return The value, or null if not found
   */
  @SuppressWarnings("unchecked")
  public <T> T get(String key) {
    return (T) data.get(key);
  }

  /**
   * Gets a value from the blackboard with a default value.
   *
   * @param key          The key to retrieve
   * @param defaultValue The default value if the key is not found
   * @param <T>          The expected type of the value
   * @return The value, or the default value if not found
   */
  @SuppressWarnings("unchecked")
  public <T> T getOrDefault(String key, T defaultValue) {
    return (T) data.getOrDefault(key, defaultValue);
  }

  /**
   * Checks if the blackboard contains a key.
   *
   * @param key The key to check
   * @return true if the key exists, false otherwise
   */
  public boolean has(String key) {
    return data.containsKey(key);
  }

  /**
   * Removes a value from the blackboard.
   *
   * @param key The key to remove
   */
  public void remove(String key) {
    data.remove(key);
  }

  /**
   * Clears all data from the blackboard.
   */
  public void clear() {
    data.clear();
  }

  /**
   * Gets a snapshot copy of the blackboard data.
   *
   * @return A copy of the current blackboard data
   */
  public Map<String, Object> snapshot() {
    return new ConcurrentHashMap<>(data);
  }
}
