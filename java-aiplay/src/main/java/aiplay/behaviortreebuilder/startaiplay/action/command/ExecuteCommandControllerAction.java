package aiplay.behaviortreebuilder.startaiplay.action.command;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.COMMAND_EXECUTOR;

import aiplay.scanners.executors.IPlayExecutor;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;

public class ExecuteCommandControllerAction extends ActionNode {

  public ExecuteCommandControllerAction(String name) {
    super(name);
  }

  @Override
  protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
    final IPlayExecutor playExecutor = context.getBlackboard().get(COMMAND_EXECUTOR);
    playExecutor.execute(context);
    return BehaviorTreeStatus.RUNNING;
  }
}
