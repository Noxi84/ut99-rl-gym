package aiplay.behaviortreebuilder;

import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeNode;
import behaviortree.BehaviorTreeStatus;
import behaviortree.DecoratorNode;

/**
 * Root decorator for the behavior tree. All runtime services (buses, predictor, sender, RL, producer thread) are created and started by {@link aiplay.runtime.BotRuntime} before the first tick. This decorator simply ticks its child.
 */
public class ProducerFpsDecorator extends DecoratorNode {

  public ProducerFpsDecorator(String name, BehaviorTreeNode child) {
    super(name, child);
  }

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    BehaviorTreeStatus status = getChild().tick(context);
    return recordStatus(context, status);
  }
}
