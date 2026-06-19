package behaviortree;

/**
 * The main behavior tree class that manages the root node and context.
 */
public class BehaviorTree {

  private final BehaviorTreeNode root;
  private final BehaviorTreeContext context;
  private BehaviorTreeStatus lastStatus;

  public BehaviorTree(BehaviorTreeNode root) {
    this.root = root;
    this.context = new BehaviorTreeContext();
    this.lastStatus = BehaviorTreeStatus.FAILURE;
  }

  /**
   * Executes one tick of the behavior tree.
   *
   * @return The status of the root node execution
   */
  public BehaviorTreeStatus tick() {
    context.advanceTick();
    lastStatus = root.tick(context);
    return lastStatus;
  }

  /**
   * Resets the entire behavior tree to its initial state.
   */
  public void reset() {
    root.reset();
    context.reset();
    lastStatus = BehaviorTreeStatus.FAILURE;
  }

  /**
   * Gets the root node of the tree.
   *
   * @return The root node
   */
  public BehaviorTreeNode getRoot() {
    return root;
  }

  /**
   * Gets the execution context.
   *
   * @return The context
   */
  public BehaviorTreeContext getContext() {
    return context;
  }

  /**
   * Gets the last execution status.
   *
   * @return The last status
   */
  public BehaviorTreeStatus getLastStatus() {
    return lastStatus;
  }
}
