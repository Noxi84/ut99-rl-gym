package aiplay.behaviortreebuilder;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.behaviortreebuilder.startaiplay.action.command.ExecuteCommandControllerAction;
import aiplay.behaviortreebuilder.startaiplay.action.mission.EvaluateMissionAction;
import aiplay.behaviortreebuilder.startaiplay.action.rlpawn.ExecuteRLPawnAction;
import aiplay.behaviortreebuilder.startaiplay.action.weapon.EvaluateWeaponAction;
import aiplay.behaviortreebuilder.startaiplay.condition.IsAiPlayerAliveCondition;
import aiplay.behaviortreebuilder.startaiplay.decorator.ExecutorFpsDecorator;
import aiplay.behaviortreebuilder.startaiplay.decorator.ExecutorSlot;
import aiplay.behaviortreebuilder.startut99.action.RunUT99ServerActionNode;
import aiplay.behaviortreebuilder.startut99.action.WaitForNeuralNetWebserviceUpActionNode;
import aiplay.behaviortreebuilder.startut99.condition.IsNeuralNetWebserviceUpCondition;
import aiplay.config.global.BotConfig;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import behaviortree.BehaviorTree;
import behaviortree.composites.ParallelNode;
import behaviortree.composites.SelectorNode;
import behaviortree.composites.SequenceNode;

/**
 * Headless behavior tree: no UT99 client, no Xvfb, no xdotool. Only starts the dedicated server (ucc-bin) and plays via HTTP POST commands.
 */
public class UT99HeadlessBehaviorTreeBuilder {

  public BehaviorTree build(String sessionId, BotConfig botConfig) {
    ProducerFpsDecorator root = new ProducerFpsDecorator("Launcher", new SequenceNode("Launcher")
        // Ensure dedicated server is running
        .addChild(new SelectorNode("Ensure UT99 server/webservice is running")
            .addChild(new IsNeuralNetWebserviceUpCondition("Is NeuralNet webservice up?"))
            .addChild(new SequenceNode("Start server and wait for webservice")
                .addChild(new RunUT99ServerActionNode("Run UT99 dedicated server"))
                .addChild(new WaitForNeuralNetWebserviceUpActionNode("Wait for NeuralNet webservice", 15_000L))
            )
        )
        // Play loop
        .addChild(new SelectorNode("Start AI Play")
            .addChild(buildParallelAiPlay(botConfig))
        )
    );

    BehaviorTree behaviorTree = new BehaviorTree(root);

    behaviorTree.getContext().getBlackboard().set(BlackboardKeys.SESSION_ID, sessionId);

    return behaviorTree;
  }

  private static ParallelNode buildParallelAiPlay(BotConfig botConfig) {
    ParallelNode parallel = new ParallelNode("Parallel AI Play");
    ModelRoleRegistry registry = ModelRoleRegistry.shared();
    boolean policyActive = registry.isRoleActiveForBot(ModelRole.PAWN_POLICY, botConfig);

    // Mission/Skill evaluation at 5Hz
    parallel.addChild(new ExecutorFpsDecorator(ExecutorSlot.MISSION_PLANNER,
        new SequenceNode("Evaluate Mission and Skill")
            .addChild(new IsAiPlayerAliveCondition(
                "Mission: Is AI player alive?",
                BlackboardKeys.MISSION_GAMESTATES))
            .addChild(new EvaluateMissionAction("Evaluate mission and skill"))
    ));

    // Full-joint movement+VR+shooting. Deze executor publiceert movement,
    // view-turn en shooting intents — de enige low-level policy in productie.
    if (policyActive) {
      parallel.addChild(new ExecutorFpsDecorator(ExecutorSlot.RLPAWN_POLICY,
          new SequenceNode("Execute RLPawn")
              .addChild(new IsAiPlayerAliveCondition(
                  "RLPawn: Is AI player alive?",
                  BlackboardKeys.VR_SHOOT_GAMESTATES))
              .addChild(new ExecuteRLPawnAction("Execute RL VR+shooting"))
      ));
    }

    // Weapon planner: low-Hz lane that decides which weapon the bot should hold
    // (preferred_weapon, next-best-with-ammo on fallback). Publishes a
    // WeaponSelectIntent; the command controller edge-triggers the actual switch.
    // Independent of the policy lane — runs for every RL bot.
    parallel.addChild(new ExecutorFpsDecorator(ExecutorSlot.WEAPON_PLANNER,
        new SequenceNode("Evaluate Weapon Choice")
            .addChild(new IsAiPlayerAliveCondition(
                "Weapon: Is AI player alive?",
                BlackboardKeys.WEAPON_GAMESTATES))
            .addChild(new EvaluateWeaponAction("Evaluate preferred weapon"))
    ));

    // Command controller: reads intents from all buses, sends HTTP POST
    parallel.addChild(new ExecutorFpsDecorator(ExecutorSlot.COMMAND_CONTROLLER,
        new SequenceNode("Execute Command Controller")
            .addChild(new IsAiPlayerAliveCondition("CmdCtrl: Is AI player alive?", BlackboardKeys.COMMAND_GAMESTATES))
            .addChild(new ExecuteCommandControllerAction("Execute command controller"))
    ));

    return parallel;
  }
}
