package aiplay.behaviortreebuilder.startaiplay.action.rlpawn;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.VR_SHOOT_EXECUTOR;

import aiplay.scanners.executors.IPlayExecutor;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;

/**
 * BT-action die het joint VR+shooting executor één tick laat draaien. Volgt het
 * patroon van {@link aiplay.behaviortreebuilder.startaiplay.action.mission.EvaluateMissionAction}
 * en {@link aiplay.behaviortreebuilder.startaiplay.action.command.ExecuteCommandControllerAction}.
 */
public class ExecuteRLPawnAction extends ActionNode {

    public ExecuteRLPawnAction(String name) {
        super(name);
    }

    @Override
    protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
        final IPlayExecutor playExecutor = context.getBlackboard().get(VR_SHOOT_EXECUTOR);
        playExecutor.execute(context);
        return BehaviorTreeStatus.RUNNING;
    }
}
