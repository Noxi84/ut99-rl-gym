package behaviortree.composites;

import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeNode;
import behaviortree.BehaviorTreeStatus;
import behaviortree.CompositeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A parallel node executes all its children concurrently using virtual threads.
 *
 * IMPORTANT:
 * - This implementation returns RUNNING while ANY child is still executing.
 * - It only returns SUCCESS/FAILURE once ALL children have completed.
 *
 * Success policy determines when the parallel node returns SUCCESS (after all are done):
 * - REQUIRE_ALL: All children must succeed
 * - REQUIRE_ONE: At least one child must succeed
 *
 * Failure policy determines when the parallel node returns FAILURE (after all are done):
 * - REQUIRE_ALL: All children must fail
 * - REQUIRE_ONE: At least one child must fail
 *
 * Thread-safety note:
 * - Children run concurrently and share the same BehaviorTreeContext.
 * - Ensure your context/blackboard mutations are thread-safe OR your children do not conflict.
 */
public class ParallelNode extends CompositeNode {

  public enum Policy {
    REQUIRE_ONE,
    REQUIRE_ALL
  }

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private final Policy successPolicy;
  private final Policy failurePolicy;

  // State that persists across ticks
  private List<Future<BehaviorTreeStatus>> runningFutures;

  public ParallelNode(String name, Policy successPolicy, Policy failurePolicy) {
    super(name);
    this.successPolicy = successPolicy;
    this.failurePolicy = failurePolicy;
  }

  /**
   * Creates a parallel node with default policies (REQUIRE_ALL for success, REQUIRE_ONE for failure).
   */
  public ParallelNode(String name) {
    this(name, Policy.REQUIRE_ALL, Policy.REQUIRE_ONE);
  }

  @Override
  public BehaviorTreeStatus tick(BehaviorTreeContext context) {
    if (children.isEmpty()) {
      return recordStatus(context, BehaviorTreeStatus.SUCCESS);
    }

    // First tick: start all children concurrently
    if (runningFutures == null) {
      runningFutures = new ArrayList<Future<BehaviorTreeStatus>>(children.size());

      for (BehaviorTreeNode child : children) {
        Callable<BehaviorTreeStatus> task = new Callable<BehaviorTreeStatus>() {
          @Override
          public BehaviorTreeStatus call() {
            try {
              return child.tick(context);
            } catch (Exception e) {
              // Treat any exception as FAILURE
              return BehaviorTreeStatus.FAILURE;
            }
          }
        };

        Future<BehaviorTreeStatus> future = EXECUTOR.submit(task);
        runningFutures.add(future);
      }
    }

    // While any child is still running, we are RUNNING
    for (Future<BehaviorTreeStatus> future : runningFutures) {
      if (!future.isDone()) {
        return recordStatus(context, BehaviorTreeStatus.RUNNING);
      }
    }

    // All done: collect results and apply policies
    int successCount = 0;
    int failureCount = 0;
    int runningCount = 0; // should be 0 here, but kept for completeness

    for (Future<BehaviorTreeStatus> future : runningFutures) {
      BehaviorTreeStatus status;
      try {
        status = future.get(); // safe: all futures are done
      } catch (Exception e) {
        status = BehaviorTreeStatus.FAILURE;
      }

      if (status == BehaviorTreeStatus.SUCCESS) {
        successCount++;
      } else if (status == BehaviorTreeStatus.FAILURE) {
        failureCount++;
      } else {
        runningCount++;
      }
    }

    // Reset for next run (important!)
    runningFutures = null;

    // Evaluate failure first (like your original code)
    if (failurePolicy == Policy.REQUIRE_ONE && failureCount > 0) {
      return recordStatus(context, BehaviorTreeStatus.FAILURE);
    }
    if (failurePolicy == Policy.REQUIRE_ALL && failureCount == children.size()) {
      return recordStatus(context, BehaviorTreeStatus.FAILURE);
    }

    // Evaluate success
    if (successPolicy == Policy.REQUIRE_ONE && successCount > 0) {
      return recordStatus(context, BehaviorTreeStatus.SUCCESS);
    }
    if (successPolicy == Policy.REQUIRE_ALL && successCount == children.size()) {
      return recordStatus(context, BehaviorTreeStatus.SUCCESS);
    }
    return recordStatus(context, BehaviorTreeStatus.RUNNING);
  }
}
