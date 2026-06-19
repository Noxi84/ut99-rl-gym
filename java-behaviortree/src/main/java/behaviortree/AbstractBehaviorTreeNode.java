package behaviortree;

/**
 * Abstract base class for behavior tree nodes. Provides common functionality for all node types,
 * including per-node log buffering, runtime ID assignment, and optional descriptions.
 *
 * <p>Thread-safety: {@code lastStatus} and {@code lastTickId} are volatile so BT-tick writes
 * are visible to HTTP-request reads without additional synchronization. The {@code logBuffer}
 * delegate is itself thread-safe (see {@link NodeLogBuffer}).
 */
public abstract class AbstractBehaviorTreeNode implements BehaviorTreeNode {

  protected final String name;
  private volatile BehaviorTreeStatus lastStatus = null;
  private volatile long lastTickId = -1L;

  /**
   * Path-based runtime ID assigned by the web runtime during tree traversal.
   * Null until {@link #setNodeId(String)} is called.
   */
  private String nodeId;

  /**
   * Per-node log ring buffer bound to {@link #nodeId}. Null until
   * {@link #setNodeId(String)} is called.
   */
  private NodeLogBuffer logBuffer;

  /**
   * Human-readable description of this node's purpose. Set by subclass constructors via
   * {@link #setDescription(String)}. Shown in the web UI when the node is selected.
   */
  private String description = "";

  protected AbstractBehaviorTreeNode(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  public BehaviorTreeStatus getLastStatus() {
    return lastStatus;
  }

  public long getLastTickId() {
    return lastTickId;
  }

  protected BehaviorTreeStatus recordStatus(BehaviorTreeContext context, BehaviorTreeStatus status) {
    this.lastStatus = status;
    if (context != null) {
      this.lastTickId = context.getTickId();
    }
    return status;
  }

  /**
   * Assigns a unique runtime ID to this node and binds its per-node log buffer in
   * {@link NodeLogStore}. Called by the web runtime during tree traversal immediately
   * after the node is registered.
   *
   * @param nodeId non-null path-based node identifier (e.g. {@code "n0_1_2-compile-ucc-code"})
   */
  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
    this.logBuffer = NodeLogStore.getOrCreate(nodeId);
  }

  /**
   * Returns the runtime-assigned node ID, or {@code null} if the node has not yet been
   * registered by the web runtime (e.g. during headless standalone execution).
   */
  public String getNodeId() {
    return nodeId;
  }

  /**
   * Returns the human-readable description of this node's purpose, or an empty string if
   * none was set.
   */
  public String getDescription() {
    return description;
  }

  /**
   * Sets a human-readable description of this node's purpose and intended behaviour.
   * Intended to be called from subclass constructors.
   *
   * @param description description text; {@code null} is treated as empty string
   */
  protected void setDescription(String description) {
    this.description = description == null ? "" : description;
  }

  /**
   * Appends a log entry to this node's per-node log buffer in {@link NodeLogStore}.
   * No-op if the node has not been registered via {@link #setNodeId(String)}.
   *
   * @param level   log level: {@code "INFO"}, {@code "WARN"}, or {@code "ERROR"}
   * @param message human-readable log message
   */
  protected void log(String level, String message) {
    if (logBuffer != null) {
      logBuffer.add(level, message);
    }
  }

  @Override
  public void reset() {
    // Default implementation does nothing.
    // Subclasses can override if they need to reset state.
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + name + "]";
  }

}
