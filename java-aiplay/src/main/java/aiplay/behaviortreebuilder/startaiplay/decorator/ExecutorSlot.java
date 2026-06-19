package aiplay.behaviortreebuilder.startaiplay.decorator;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;

/**
 * Identifies one executor lane in the parallel AI-play subtree and bundles the two
 * blackboard coordinates that lane needs plus its display name. Replaces the previous
 * dispatch-on-display-name in {@link ExecutorFpsDecorator}: the decorator now reads its
 * {@link aiplay.scanners.executors.IPlayExecutor} and publishes its game-state frames via
 * the explicit keys carried here, so renaming a node for the UI can no longer break wiring.
 */
public enum ExecutorSlot {

  MISSION_PLANNER("Mission Skill Planner", BlackboardKeys.MISSION_EXECUTOR, BlackboardKeys.MISSION_GAMESTATES),
  RLPAWN_POLICY("RLPawn Policy", BlackboardKeys.VR_SHOOT_EXECUTOR, BlackboardKeys.VR_SHOOT_GAMESTATES),
  WEAPON_PLANNER("Weapon Planner", BlackboardKeys.WEAPON_EXECUTOR, BlackboardKeys.WEAPON_GAMESTATES),
  COMMAND_CONTROLLER("Command Controller", BlackboardKeys.COMMAND_EXECUTOR, BlackboardKeys.COMMAND_GAMESTATES);

  private final String displayName;
  private final String executorKey;
  private final String gameStatesKey;

  ExecutorSlot(String displayName, String executorKey, String gameStatesKey) {
    this.displayName = displayName;
    this.executorKey = executorKey;
    this.gameStatesKey = gameStatesKey;
  }

  /** Human-readable node name shown in the web UI / logs. */
  public String displayName() {
    return displayName;
  }

  /** Blackboard key under which {@link aiplay.runtime.BotRuntime} publishes the executor instance. */
  public String executorKey() {
    return executorKey;
  }

  /** Blackboard key under which this lane's latest game-state frames are published for its child to consume. */
  public String gameStatesKey() {
    return gameStatesKey;
  }
}
