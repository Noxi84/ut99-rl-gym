package behaviortree;

/**
 * Base class for decorator nodes that modify the behavior of a single child node.
 * Decorators wrap a child node and can change its return status or control when it executes.
 */
public abstract class DecoratorNode extends AbstractBehaviorTreeNode {
  protected final BehaviorTreeNode child;

  protected DecoratorNode(String name, BehaviorTreeNode child) {
    super(name);
    this.child = child;
  }

  /**
   * Gets the child node.
   *
   * @return The child node
   */
  public BehaviorTreeNode getChild() {
    return child;
  }

  @Override
  public void reset() {
    child.reset();
  }
}