package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;

import java.util.List;

public interface TrainingFeatureEnricher {

    void enrichBatch(List<GameStateDto> frames);

    void enrichIncremental(String sessionId, List<GameStateDto> frames);
}
