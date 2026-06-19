package aiplay.runtime.adapter.live;

import ai.onnxruntime.OrtException;
import aiplay.dto.GridFrame;
import aiplay.play.AiPlayFacade;
import aiplay.runtime.port.GameStateSource;
import behaviortree.BehaviorTreeContext;

import java.util.List;

/**
 * Live adapter: acquires game state from UT99 via HTTP GET through {@link AiPlayFacade}.
 * Handles resampling, buffering, and death detection internally.
 */
public final class LiveGameStateSource implements GameStateSource {

    private final AiPlayFacade aiPlayFacade = new AiPlayFacade();

    @Override
    public List<GridFrame> poll(BehaviorTreeContext context, double fps) throws OrtException {
        return aiPlayFacade.getNewGameStatus(context, fps);
    }
}
