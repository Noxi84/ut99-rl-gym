package aiplay.instance;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import behaviortree.BehaviorTreeContext;

/**
 * Utility to extract InstanceConfig from a BehaviorTreeContext.
 * Returns null if not set (single-instance legacy mode).
 */
public final class InstanceConfigHelper {

  private InstanceConfigHelper() {}

  public static InstanceConfig get(BehaviorTreeContext context) {
    if (context == null || context.getBlackboard() == null) return null;
    try {
      return context.getBlackboard().get(BlackboardKeys.INSTANCE_CONFIG);
    } catch (Exception e) {
      return null;
    }
  }
}
