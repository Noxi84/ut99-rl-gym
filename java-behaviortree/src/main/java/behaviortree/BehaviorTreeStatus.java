package behaviortree;

/**
 * Represents the execution status of a behavior tree node.
 */
public enum BehaviorTreeStatus {

  /**
   * The node has successfully completed its task.
   */
  SUCCESS,

  /**
   * The node has failed to complete its task.
   */
  FAILURE,

  /**
   * The node is still executing and hasn't completed yet.
   */
  RUNNING
}