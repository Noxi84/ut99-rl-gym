package aiplay.scanners.executors;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.runtime.port.InferencePort;
import aiplay.shared.movement.MovementIntentBus;
import behaviortree.BehaviorTreeContext;

public final class PlayContext {

  public final String sessionId;
  public final InferencePort predictor;
  public final MovementIntentBus effectiveMovementIntentBus;
  public IPlayExecutor executor;
  public PlayExecutorLogger executorLogger;

  public PlayContext(String sessionId,
      InferencePort predictor,
      MovementIntentBus effectiveMovementIntentBus) {

    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId null/blank");
    }
    if (predictor == null) {
      throw new IllegalArgumentException("predictor null");
    }
    if (effectiveMovementIntentBus == null) {
      throw new IllegalArgumentException("effectiveMovementIntentBus null");
    }

    this.sessionId = sessionId;
    this.predictor = predictor;
    this.effectiveMovementIntentBus = effectiveMovementIntentBus;
  }

  public PlayContext(BehaviorTreeContext context) {
    this.sessionId = context.getBlackboard().get(BlackboardKeys.SESSION_ID);
    this.predictor = context.getBlackboard().get(BlackboardKeys.GENERIC_PREDICTOR);
    this.effectiveMovementIntentBus = context.getBlackboard().get(BlackboardKeys.MOVEMENT_INTENT_BUS);
  }
}
