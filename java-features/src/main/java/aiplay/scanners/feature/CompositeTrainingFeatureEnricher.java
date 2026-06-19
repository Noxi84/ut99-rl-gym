package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;

import java.util.List;

/**
 * Composes multiple {@link TrainingFeatureEnricher} delegates into a single one,
 * invoking them in declaration order on the same frame list.
 *
 * <p>Useful when a feature component owns several independent state-tracking
 * enrichers (e.g. dodge cooldown + idle duration) but the {@link ITrainingFeature}
 * contract exposes only a single enricher.</p>
 */
public final class CompositeTrainingFeatureEnricher implements TrainingFeatureEnricher {

    private final List<TrainingFeatureEnricher> delegates;

    public CompositeTrainingFeatureEnricher(TrainingFeatureEnricher... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        for (TrainingFeatureEnricher d : delegates) {
            d.enrichBatch(frames);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        for (TrainingFeatureEnricher d : delegates) {
            d.enrichIncremental(sessionId, frames);
        }
    }
}
