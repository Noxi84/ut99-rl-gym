package behaviortree;

/**
 * Context object that is passed to behavior tree nodes during execution.
 * Contains the blackboard and any other shared state needed by the tree.
 */
public class BehaviorTreeContext {
  private final Blackboard blackboard;
  private long startTimeMillis;
  private long currentTimeMillis;
  private long tickId;

  public BehaviorTreeContext() {
    this.blackboard = new Blackboard();
    reset();
  }

  /**
   * Gets the blackboard for storing and retrieving shared data.
   *
   * @return The blackboard
   */
  public Blackboard getBlackboard() {
    return blackboard;
  }

  /**
   * Gets the time in milliseconds when the context was created.
   *
   * @return The start time in milliseconds
   */
  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  /**
   * Gets the current execution time in milliseconds.
   * This is updated before each tree tick.
   *
   * @return The current time in milliseconds
   */
  public long getCurrentTimeMillis() {
    return currentTimeMillis;
  }

  /**
   * Gets the current tick id.
   *
   * @return The current tick id
   */
  public long getTickId() {
    return tickId;
  }

  /**
   * Updates the current execution time.
   * Should be called before each tree tick.
   */
  public void updateCurrentTime() {
    this.currentTimeMillis = System.currentTimeMillis();
  }

  /**
   * Advances the tick counter and updates the current execution time.
   */
  public void advanceTick() {
    this.tickId++;
    updateCurrentTime();
  }

  /**
   * Resets the context to its initial state.
   */
  public void reset() {
    this.tickId = 0L;
    this.startTimeMillis = System.currentTimeMillis();
    this.currentTimeMillis = this.startTimeMillis;
    this.blackboard.clear();
  }

  /**
   * Gets the elapsed time since the context was created.
   *
   * @return The elapsed time in milliseconds
   */
  public long getElapsedTimeMillis() {
    return currentTimeMillis - startTimeMillis;
  }
}
