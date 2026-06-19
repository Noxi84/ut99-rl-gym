package aiplay.scanners.executors;

import aiplay.dto.GridFrame;

import behaviortree.BehaviorTreeContext;
import java.util.List;

public interface PlayExecutorAiController {

  boolean isEnabled();

  void init(PlayContext ctx);

  void execute(BehaviorTreeContext context, IPlayExecutor executor);

  void execute(BehaviorTreeContext context, String sessionId, IPlayExecutor executor, List<GridFrame> frames, Object extraArg);
}
