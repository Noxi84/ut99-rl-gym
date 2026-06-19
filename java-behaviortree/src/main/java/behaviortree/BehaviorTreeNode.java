package behaviortree;

/**
 * Base interface for all behavior tree nodes.
 * A behavior tree node represents a single unit of behavior that can be executed.
 */
public interface BehaviorTreeNode {
  /**
   * Executes this node with the given context.
   *
   * @param context The execution context containing shared state and blackboard data
   * @return The status of the node execution (SUCCESS, FAILURE, or RUNNING)
   */
  BehaviorTreeStatus tick(BehaviorTreeContext context);

  /**
   * Resets the node to its initial state.
   * Called when the tree is reset or when a parent node needs to restart execution.
   */
  void reset();

  /**
   * Gets the name of this node for debugging and visualization purposes.
   *
   * @return The node name
   */
  String getName();
}