package aiplay.behaviortreebuilder.startaiplay.action.mission;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.MISSION_EXECUTOR;

import aiplay.scanners.executors.IPlayExecutor;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;

public class EvaluateMissionAction extends ActionNode {

  public EvaluateMissionAction(String name) {
    super(name);
  }

  @Override
  protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
    final IPlayExecutor executor = context.getBlackboard().get(MISSION_EXECUTOR);
    executor.execute(context);
    return BehaviorTreeStatus.RUNNING;
  }
}
