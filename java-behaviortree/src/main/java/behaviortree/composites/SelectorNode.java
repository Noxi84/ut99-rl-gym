package behaviortree.composites;

import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeNode;
import behaviortree.BehaviorTreeStatus;
import behaviortree.CompositeNode;

/**
 * A selector node (also called a fallback or priority selector) executes its children in order.
 * - Returns SUCCESS if any child returns SUCCESS
 * - Returns FAILURE if all children return FAILURE
 * - Returns RUNNING if any child returns RUNNING
 *
 * Execution stops at the first SUCCESS or RUNNING child.
 */
public class SelectorNode extends CompositeNode {

  public SelectorNode(String name) {
    super(name);
  }

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    for (int i = currentChildIndex; i < children.size(); i++) {
      BehaviorTreeNode child = children.get(i);
      BehaviorTreeStatus status = child.tick(context);

      switch (status) {
        case SUCCESS:
          currentChildIndex = 0; // Reset for next execution
          return recordStatus(context, BehaviorTreeStatus.SUCCESS);
        case RUNNING:
          currentChildIndex = i; // Remember where we are
          return recordStatus(context, BehaviorTreeStatus.RUNNING);
        case FAILURE:
          // Continue to next child
          break;
      }
    }

    // All children failed
    currentChildIndex = 0; // Reset for next execution
    return recordStatus(context, BehaviorTreeStatus.FAILURE);
  }

  @Override
  public void reset() {
    super.reset();
    currentChildIndex = 0;
  }
}
