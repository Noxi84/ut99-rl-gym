package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;
import aiplay.ut99webmodel.GameState;

public interface TrainingFeatureJsonToDtoConverter {

    Integer priority();

    GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto);

    /**
     * Populate ALL fields in a single call per frame. Avoids the per-feature-ID loop
     * overhead (player lookup, string parsing, etc.). Converters should override this
     * to do all their work in one pass.
     */
    default GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        return dto;
    }
}
