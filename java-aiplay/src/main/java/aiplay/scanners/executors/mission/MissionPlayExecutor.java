package aiplay.scanners.executors.mission;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.dto.GridFrame;
import aiplay.dto.GameStateDto;
import aiplay.logging.SessionRollingLogger;
import aiplay.mission.MissionController;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.scanners.executors.PlayExecutorComponent;
import aiplay.scanners.executors.PlayExecutorLogger;
import aiplay.shared.tactical.TacticalIntentBus;
import aiplay.config.global.GlobalConfigRepository;
import behaviortree.BehaviorTreeContext;
import java.util.List;
import java.util.logging.Logger;

@PlayExecutorComponent(priority = 5)
public class MissionPlayExecutor implements IPlayExecutor {

    private MissionController controller;

    @Override
    public String getExecutorKey() {
        return "mission-planner";
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public int getPredictionFps() {
        return GlobalConfigRepository.shared().mission().missionAnnotatorFps();
    }

    @Override
    public void execute(BehaviorTreeContext context) {
        List<GridFrame> gameStates = context.getBlackboard().get(BlackboardKeys.MISSION_GAMESTATES);
        if (gameStates == null || gameStates.isEmpty()) return;

        if (controller == null) {
            String sessionId = context.getBlackboard().get(BlackboardKeys.SESSION_ID);
            TacticalIntentBus tacticalBus = context.getBlackboard().get(BlackboardKeys.TACTICAL_INTENT_BUS);
            Logger logger = SessionRollingLogger.get(sessionId, "mission");

            controller = new MissionController(tacticalBus, logger);
        }

        GameStateDto latest = gameStates.get(gameStates.size() - 1).state();
        controller.tick(latest);
    }

    @Override
    public PlayExecutorAiController getPlayExecutorAiController() {
        return null;
    }

    @Override
    public PlayExecutorLogger getPlayExecutorLogger() {
        return null;
    }
}
