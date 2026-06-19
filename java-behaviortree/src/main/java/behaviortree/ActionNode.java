package behaviortree;

/**
 * Base class for action nodes — the leaf nodes that actually perform work in the behavior tree.
 *
 * <p>Subclasses implement {@link #execute(BehaviorTreeContext)}. The {@link #tick} wrapper:
 * <ul>
 *   <li>Records {@code RUNNING} before the call and the final status after.</li>
 *   <li>Wraps the call in a try-catch; uncaught exceptions are logged at ERROR level and
 *       translated to {@code FAILURE} so the tree never crashes on a single bad node.</li>
 *   <li>Logs an INFO entry whenever the status transitions from one value to another,
 *       keeping the per-node log free of high-frequency identical-state spam.</li>
 * </ul>
 */
public abstract class ActionNode extends AbstractBehaviorTreeNode {

  protected ActionNode(String name) {
    super(name);
  }

  /**
   * Executes the action.
   *
   * @param context the current execution context; never {@code null}
   * @return the status of this execution; must not be {@code null}
   */
  protected abstract BehaviorTreeStatus execute(BehaviorTreeContext context);

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    BehaviorTreeStatus previous = getLastStatus();
    recordStatus(context, BehaviorTreeStatus.RUNNING);

    BehaviorTreeStatus result;
    try {
      result = execute(context);
    } catch (Exception e) {
      // Log the exception to the per-node buffer and treat it as FAILURE
      log("ERROR", "Exception in execute(): "
          + e.getClass().getSimpleName() + ": " + e.getMessage());
      result = BehaviorTreeStatus.FAILURE;
    }

    BehaviorTreeStatus finalStatus = recordStatus(context, result);

    // Log only on status transitions to avoid flooding the buffer at 20 Hz
    if (previous != finalStatus) {
      log("INFO", (previous == null ? "NEW" : previous.name()) + " → " + finalStatus.name());
    }

    return finalStatus;
  }
}
