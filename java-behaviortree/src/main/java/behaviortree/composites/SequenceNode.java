package behaviortree.composites;

import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeNode;
import behaviortree.BehaviorTreeStatus;
import behaviortree.CompositeNode;

/**
 * A sequence node executes its children in order. - Returns SUCCESS if all children return SUCCESS - Returns FAILURE if any child returns FAILURE - Returns RUNNING if any child returns RUNNING
 * <p>
 * Execution stops at the first FAILURE or RUNNING child.
 */
public class SequenceNode extends CompositeNode {

  public SequenceNode(String name) {
    super(name);
  }

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    for (int i = currentChildIndex; i < children.size(); i++) {
      BehaviorTreeNode child = children.get(i);
      BehaviorTreeStatus status = child.tick(context);
      if (status == null) {
        System.out.println("Status is null of " + child.getName());
      }
      switch (status) {
        case FAILURE:
          currentChildIndex = 0; // Reset for next execution
          return recordStatus(context, BehaviorTreeStatus.FAILURE);
        case RUNNING:
          currentChildIndex = i; // Remember where we are
          return recordStatus(context, BehaviorTreeStatus.RUNNING);
        case SUCCESS:
          // Continue to next child
          break;
      }
    }

    // All children succeeded
    currentChildIndex = 0; // Reset for next execution
    return recordStatus(context, BehaviorTreeStatus.SUCCESS);
  }

  @Override
  public void reset() {
    super.reset();
    currentChildIndex = 0;
  }
}
