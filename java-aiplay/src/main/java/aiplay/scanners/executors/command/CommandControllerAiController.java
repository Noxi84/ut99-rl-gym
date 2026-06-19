package aiplay.scanners.executors.command;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
// CommandController is now in the same package
import aiplay.dto.GameStateDto;
import aiplay.dto.GridFrame;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.runtime.port.CommandSink;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayContext;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.shared.state.GameStateBus;
import aiplay.shared.state.GameStateSnapshot;
import aiplay.shared.movement.MovementIntentBus;
import aiplay.shared.movement.PolicyIntentBus;
import aiplay.shared.shooting.ShootIntentBus;
import aiplay.shared.tactical.TacticalIntentBus;
import aiplay.shared.view.ViewTurnIntentBus;
import aiplay.shared.weapon.WeaponSelectIntentBus;
import behaviortree.BehaviorTreeContext;

import java.util.List;
import java.util.logging.Logger;

/**
 * AI controller for the command executor. Runs at controller rate (60Hz).
 * Reads intent buses and current game state, delegates to CommandController.
 */
public class CommandControllerAiController implements PlayExecutorAiController {

    private CommandController controller;

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void init(PlayContext ctx) {
        // Controller is initialized lazily on first execute() when blackboard is available
    }

    @Override
    public void execute(BehaviorTreeContext context, IPlayExecutor executor) {
        execute(context, null, executor, null, null);
    }

    @Override
    public void execute(BehaviorTreeContext context, String sessionId, IPlayExecutor executor,
                        List<GridFrame> frames, Object extraArg) {
        String resolvedSessionId = sessionId;
        if ((resolvedSessionId == null || resolvedSessionId.isBlank())
                && context.getBlackboard().has(BlackboardKeys.SESSION_ID)) {
            resolvedSessionId = context.getBlackboard().get(BlackboardKeys.SESSION_ID);
        }

        // Lazy init: create CommandController once we have all blackboard dependencies
        if (controller == null) {
            PolicyIntentBus policyBus = context.getBlackboard().get(BlackboardKeys.POLICY_INTENT_BUS);
            MovementIntentBus effectiveBus = context.getBlackboard().get(BlackboardKeys.MOVEMENT_INTENT_BUS);
            ViewTurnIntentBus vrTurnBus = context.getBlackboard().get(BlackboardKeys.VIEWTURN_INTENT_BUS);
            CommandSink sender = context.getBlackboard().get(BlackboardKeys.HEADLESS_COMMAND_SENDER);
            if (policyBus == null || effectiveBus == null || vrTurnBus == null || sender == null) return;
            ShootIntentBus shootBus = context.getBlackboard().has(BlackboardKeys.SHOOT_INTENT_BUS)
                ? context.getBlackboard().get(BlackboardKeys.SHOOT_INTENT_BUS) : null;
            TacticalIntentBus tacticalBus = context.getBlackboard().has(BlackboardKeys.TACTICAL_INTENT_BUS)
                ? context.getBlackboard().get(BlackboardKeys.TACTICAL_INTENT_BUS) : null;
            WeaponSelectIntentBus weaponSelectBus = context.getBlackboard().has(BlackboardKeys.WEAPON_SELECT_INTENT_BUS)
                ? context.getBlackboard().get(BlackboardKeys.WEAPON_SELECT_INTENT_BUS) : null;
            Logger vrMonitorLogger = null;
            if (resolvedSessionId != null && !resolvedSessionId.isBlank()) {
                vrMonitorLogger = SessionRollingLogger.get(
                    resolvedSessionId,
                    SessionLogPaths.featureLog("ViewRotationMonitor"));
            }
            controller = new CommandController(
                policyBus, effectiveBus, vrTurnBus, shootBus, tacticalBus, weaponSelectBus, sender, vrMonitorLogger);
        }

        // Get latest game state — prefer the frames passed to us, else pull from bus
        GameStateDto current = null;
        if (frames != null && !frames.isEmpty()) {
            current = frames.get(frames.size() - 1).state();
        }
        if (current == null) {
            GameStateBus bus = context.getBlackboard().get(BlackboardKeys.GAMESTATE_BUS);
            if (bus != null) {
                GameStateSnapshot snap = bus.latest("live");
                if (snap != null && snap.frames != null && !snap.frames.isEmpty()) {
                    current = snap.frames.get(snap.frames.size() - 1).state();
                }
            }
        }

        if (current != null) {
            controller.tick(current);
        }
    }
}
