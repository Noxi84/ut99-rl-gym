package aiplay.scanners.executors;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.GAMESTATE_BUS;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.SESSION_ID;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.dto.GridFrame;
import aiplay.shared.state.GameStateBus;
import behaviortree.BehaviorTreeContext;
import java.util.List;

public interface IPlayExecutor {

  String getExecutorKey();

  default void init(PlayContext ctx) {
    if (getPlayExecutorAiController() != null) {
      getPlayExecutorAiController().init(ctx);
    }
  }

  default void execute(BehaviorTreeContext context) {
    final String sessionId = context.getBlackboard().get(SESSION_ID);
    final GameStateBus bus = context.getBlackboard().get(GAMESTATE_BUS);

    List<GridFrame> gameStates = null;
    if (getExecutorKey().equalsIgnoreCase("vr-shoot-executor")) {
      gameStates = context.getBlackboard().get(BlackboardKeys.VR_SHOOT_GAMESTATES);
    } else if (getExecutorKey().equalsIgnoreCase("command-controller")) {
      gameStates = context.getBlackboard().get(BlackboardKeys.COMMAND_GAMESTATES);
    }

    if (getPlayExecutorAiController() != null && getPlayExecutorAiController().isEnabled()) {
      getPlayExecutorAiController().execute(context, sessionId, this, gameStates, bus);
    }
  }

  default void execute(BehaviorTreeContext context, String sessionId, IPlayExecutor executor, List<GridFrame> frames, Object extraArg) {
    if (getPlayExecutorAiController() != null && getPlayExecutorAiController().isEnabled()) {
      getPlayExecutorAiController().execute(context, sessionId, executor, frames, extraArg);
    }
  }

  boolean isActive();

  int getPredictionFps();

  PlayExecutorAiController getPlayExecutorAiController();

  PlayExecutorLogger getPlayExecutorLogger();
}
