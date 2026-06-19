package aiplay.behaviortreebuilder.startaiplay.action.weapon;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.WEAPON_EXECUTOR;

import aiplay.scanners.executors.IPlayExecutor;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;

public class EvaluateWeaponAction extends ActionNode {

  public EvaluateWeaponAction(String name) {
    super(name);
  }

  @Override
  protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
    final IPlayExecutor executor = context.getBlackboard().get(WEAPON_EXECUTOR);
    executor.execute(context);
    return BehaviorTreeStatus.RUNNING;
  }
}
