package behaviortree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base class for composite nodes that have child nodes.
 * Composite nodes control the execution flow of their children.
 */
public abstract class CompositeNode extends AbstractBehaviorTreeNode {
  protected final List<BehaviorTreeNode> children;
  protected int currentChildIndex;

  protected CompositeNode(String name) {
    super(name);
    this.children = new ArrayList<>();
    this.currentChildIndex = 0;
  }

  /**
   * Adds a child node to this composite.
   *
   * @param child The child node to add
   * @return This composite node for method chaining
   */
  public CompositeNode addChild(BehaviorTreeNode child) {
    children.add(child);
    return this;
  }

  /**
   * Adds multiple child nodes to this composite.
   *
   * @param children The child nodes to add
   * @return This composite node for method chaining
   */
  public CompositeNode addChildren(BehaviorTreeNode... children) {
    this.children.addAll(Arrays.asList(children));
    return this;
  }

  /**
   * Gets the list of child nodes.
   *
   * @return The child nodes
   */
  public List<BehaviorTreeNode> getChildren() {
    return children;
  }

  @Override
  public void reset() {
    currentChildIndex = 0;
    for (BehaviorTreeNode child : children) {
      child.reset();
    }
  }
}