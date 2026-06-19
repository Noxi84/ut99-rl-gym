package aiplay.runtime.port;

import ai.onnxruntime.OrtException;
import aiplay.dto.GridFrame;
import behaviortree.BehaviorTreeContext;

import java.util.List;

/**
 * Port for acquiring game state observations. The runtime kernel asks
 * this port for new frames without knowing the transport (HTTP, replay, test stub).
 *
 * <p>Live adapter: wraps {@code AiPlayFacade} (HTTP GET to UT99 webservice).
 * Test adapter: feeds synthetic or recorded frames.</p>
 */
public interface GameStateSource {

    /**
     * Poll for new game state frames at the given FPS.
     * Returns null or empty list if no new state is available.
     */
    List<GridFrame> poll(BehaviorTreeContext context, double fps) throws OrtException;
}
