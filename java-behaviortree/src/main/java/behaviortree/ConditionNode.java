package behaviortree;

/**
 * Base class for condition nodes — leaf nodes that evaluate a predicate and return either
 * {@code SUCCESS} or {@code FAILURE}. Condition nodes never return {@code RUNNING}.
 *
 * <p>The {@link #tick} wrapper:
 * <ul>
 *   <li>Wraps {@link #checkCondition} in a try-catch; uncaught exceptions are logged at ERROR
 *       level and treated as {@code FAILURE}.</li>
 *   <li>Logs an INFO entry on every status transition so the per-node log is not spammed
 *       when the condition is stable.</li>
 * </ul>
 */
public abstract class ConditionNode extends AbstractBehaviorTreeNode {

  protected ConditionNode(String name) {
    super(name);
  }

  /**
   * Evaluates the condition.
   *
   * @param context the current execution context; never {@code null}
   * @return {@code true} if the condition is satisfied, {@code false} otherwise
   */
  protected abstract boolean checkCondition(BehaviorTreeContext context);

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    BehaviorTreeStatus previous = getLastStatus();

    BehaviorTreeStatus result;
    try {
      result = checkCondition(context)
          ? BehaviorTreeStatus.SUCCESS
          : BehaviorTreeStatus.FAILURE;
    } catch (Exception e) {
      // Log the exception to the per-node buffer and treat it as FAILURE
      log("ERROR", "Exception in checkCondition(): "
          + e.getClass().getSimpleName() + ": " + e.getMessage());
      result = BehaviorTreeStatus.FAILURE;
    }

    BehaviorTreeStatus finalStatus = recordStatus(context, result);

    // Log only on status transitions
    if (previous != finalStatus) {
      log("INFO", (previous == null ? "NEW" : previous.name()) + " → " + finalStatus.name());
    }

    return finalStatus;
  }
}
