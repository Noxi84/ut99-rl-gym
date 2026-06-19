package aiplay.behaviortreebuilder.startaiplay.condition;

import aiplay.dto.GridFrame;
import aiplay.dto.PlayerDto;
import behaviortree.BehaviorTreeContext;
import behaviortree.ConditionNode;
import java.util.List;

public class IsAiPlayerAliveCondition extends ConditionNode {

  private final String gameStatesKey;

  public IsAiPlayerAliveCondition(String name, String gameStatesKey) {
    super(name);
    this.gameStatesKey = gameStatesKey;
  }

  @Override
  protected boolean checkCondition(BehaviorTreeContext context) {
    final List<GridFrame> gameStates = context.getBlackboard().get(gameStatesKey);
    if (gameStates == null || gameStates.isEmpty()) {
      return false;
    }

    PlayerDto player = gameStates.getLast().state().playerPawn;
    return player != null && player.health > 0;
  }
}
