package aiplay.runtime;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.rl.ExperienceCollector;
import aiplay.rl.PerModelExperienceRecorder;
import aiplay.rl.RewardComputer;
import aiplay.runtime.port.CommandSink;
import aiplay.runtime.port.GameStateSource;
import aiplay.runtime.port.InferencePort;
import aiplay.runtime.port.RuntimeClock;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayExecutionService;
import aiplay.shared.tactical.TacticalIntentBus;
import aiplay.shared.movement.MovementIntentBus;
import aiplay.shared.movement.PolicyIntentBus;
import aiplay.shared.state.GameStateBus;
import aiplay.shared.shooting.ShootIntentBus;
import aiplay.shared.view.ViewTurnIntentBus;
import aiplay.shared.weapon.WeaponSelectIntentBus;
import behaviortree.Blackboard;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * All per-instance runtime services for one bot. Created by {@link BotRuntimeFactory},
 * owned by {@link BotRuntime}.
 *
 * <p>Infrastructure is accessed only via ports: {@link GameStateSource},
 * {@link CommandSink}, {@link InferencePort}. Live adapters implement these.</p>
 *
 * <p>Thread safety: fields are set once during construction and not mutated afterwards.
 * The buses themselves are internally thread-safe (AtomicReference / ConcurrentHashMap).</p>
 */
public final class InstanceServices {

    // Core coordination
    final GameStateBus gameStateBus;
    final AtomicBoolean running;
    final long startTimeNs;

    // Intent buses
    final MovementIntentBus movementIntentBus;
    final PolicyIntentBus policyIntentBus;
    final ViewTurnIntentBus viewTurnIntentBus;
    final ShootIntentBus shootIntentBus;
    final TacticalIntentBus tacticalIntentBus;
    final WeaponSelectIntentBus weaponSelectIntentBus;

    // Ports (infrastructure behind abstractions)
    final GameStateSource gameStateSource;
    final CommandSink commandSink; // nullable in legacy single-instance mode
    final InferencePort inferencePort;
    final RuntimeClock clock;
    final PlayExecutionService playExecutionService;

    // RL (all nullable — only present when RL is enabled)
    final RewardComputer rewardComputer;
    final ExperienceCollector experienceCollector;

    // Joint VR+shooting experience recorder (nullable — only when RL is enabled).
    final PerModelExperienceRecorder jointPawnRecorder;

    public InstanceServices(GameStateBus gameStateBus,
                     AtomicBoolean running,
                     long startTimeNs,
                     MovementIntentBus movementIntentBus,
                     PolicyIntentBus policyIntentBus,
                     ViewTurnIntentBus viewTurnIntentBus,
                     ShootIntentBus shootIntentBus,
                     TacticalIntentBus tacticalIntentBus,
                     WeaponSelectIntentBus weaponSelectIntentBus,
                     GameStateSource gameStateSource,
                     CommandSink commandSink,
                     InferencePort inferencePort,
                     RuntimeClock clock,
                     PlayExecutionService playExecutionService,
                     RewardComputer rewardComputer,
                     ExperienceCollector experienceCollector,
                     PerModelExperienceRecorder jointPawnRecorder) {
        this.gameStateBus = gameStateBus;
        this.running = running;
        this.startTimeNs = startTimeNs;
        this.movementIntentBus = movementIntentBus;
        this.policyIntentBus = policyIntentBus;
        this.viewTurnIntentBus = viewTurnIntentBus;
        this.shootIntentBus = shootIntentBus;
        this.tacticalIntentBus = tacticalIntentBus;
        this.weaponSelectIntentBus = weaponSelectIntentBus;
        this.gameStateSource = gameStateSource;
        this.commandSink = commandSink;
        this.inferencePort = inferencePort;
        this.clock = clock;
        this.playExecutionService = playExecutionService;
        this.rewardComputer = rewardComputer;
        this.experienceCollector = experienceCollector;
        this.jointPawnRecorder = jointPawnRecorder;
    }

    public GameStateBus getGameStateBus() { return gameStateBus; }
    public AtomicBoolean getRunning() { return running; }
    public long getStartTimeNs() { return startTimeNs; }
    public MovementIntentBus getMovementIntentBus() { return movementIntentBus; }
    public PolicyIntentBus getPolicyIntentBus() { return policyIntentBus; }
    public ViewTurnIntentBus getViewTurnIntentBus() { return viewTurnIntentBus; }
    public ShootIntentBus getShootIntentBus() { return shootIntentBus; }
    public TacticalIntentBus getTacticalIntentBus() { return tacticalIntentBus; }
    public WeaponSelectIntentBus getWeaponSelectIntentBus() { return weaponSelectIntentBus; }
    public GameStateSource getGameStateSource() { return gameStateSource; }
    public CommandSink getCommandSink() { return commandSink; }
    public InferencePort getInferencePort() { return inferencePort; }
    public RuntimeClock getClock() { return clock; }
    public PlayExecutionService getPlayExecutionService() { return playExecutionService; }
    public RewardComputer getRewardComputer() { return rewardComputer; }
    public ExperienceCollector getExperienceCollector() { return experienceCollector; }
    public PerModelExperienceRecorder getJointPawnRecorder() { return jointPawnRecorder; }

    /**
     * Populates a blackboard with all services so that existing BT nodes
     * and executors can read them without changes.
     */
    void populateBlackboard(Blackboard bb) {
        bb.set(BlackboardKeys.GAMESTATE_BUS, gameStateBus);
        bb.set(BlackboardKeys.RUNNING_ATOMIC_BOOLEAN, running);
        bb.set(BlackboardKeys.START_TIME_NS, startTimeNs);

        bb.set(BlackboardKeys.MOVEMENT_INTENT_BUS, movementIntentBus);
        bb.set(BlackboardKeys.POLICY_INTENT_BUS, policyIntentBus);
        bb.set(BlackboardKeys.VIEWTURN_INTENT_BUS, viewTurnIntentBus);
        if (shootIntentBus != null) {
            bb.set(BlackboardKeys.SHOOT_INTENT_BUS, shootIntentBus);
        }
        bb.set(BlackboardKeys.TACTICAL_INTENT_BUS, tacticalIntentBus);
        bb.set(BlackboardKeys.WEAPON_SELECT_INTENT_BUS, weaponSelectIntentBus);

        if (inferencePort != null) {
            bb.set(BlackboardKeys.GENERIC_PREDICTOR, inferencePort);
        }
        if (clock != null) {
            bb.set(BlackboardKeys.RUNTIME_CLOCK, clock);
        }

        if (commandSink != null) {
            bb.set(BlackboardKeys.HEADLESS_COMMAND_SENDER, commandSink);
        }

        bb.set(BlackboardKeys.MODEL_CONFIG_REPOSITORY, aiplay.config.ModelConfigRepository.shared());

        if (rewardComputer != null) {
            bb.set(BlackboardKeys.REWARD_COMPUTER, rewardComputer);
        }
        if (experienceCollector != null) {
            bb.set(BlackboardKeys.EXPERIENCE_COLLECTOR, experienceCollector);
        }
        if (jointPawnRecorder != null) {
            bb.set(BlackboardKeys.JOINT_PAWN_EXPERIENCE_RECORDER, jointPawnRecorder);
        }

        // Executor references (looked up from PlayExecutionService, if available)
        if (playExecutionService != null) {
            setExecutorIfPresent(bb, "command-controller", BlackboardKeys.COMMAND_EXECUTOR);
            setExecutorIfPresent(bb, "vr-shoot-executor", BlackboardKeys.VR_SHOOT_EXECUTOR);
            setExecutorIfPresent(bb, "mission-planner", BlackboardKeys.MISSION_EXECUTOR);
            setExecutorIfPresent(bb, "weapon-planner", BlackboardKeys.WEAPON_EXECUTOR);
        }
    }

    private void setExecutorIfPresent(Blackboard bb, String executorKey, String bbKey) {
        try {
            IPlayExecutor exec = playExecutionService.getExecutor(executorKey);
            bb.set(bbKey, exec);
        } catch (IllegalArgumentException ignored) {
            // executor not present (inactive) — skip
        }
    }
}
