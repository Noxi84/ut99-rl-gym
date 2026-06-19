package aiplay.scanners.feature.resolver.mission;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.Set;

/**
 * Registers {@link MissionAnnotationFeatureEnricher} in the auto-discovered enricher pipeline.
 *
 * <p>Owns no feature IDs zelf — de enricher zet alleen {@code annotatedMission},
 * {@code annotatedEngagement}, {@code annotatedAttentionTarget} en tactical/spatial
 * fact fields op {@link aiplay.dto.GameStateDto}. Geen huidige feature-component leest
 * die nog (mission/engagement/spatial/tactical feature-outputs zijn verwijderd
 * 2026-05-23) maar runtime-consumers zoals {@code MovementConstraintApplier}
 * (via {@code TacticalIntentBus}) en CSV-state-bucketing
 * ({@code missionBucket}/{@code engagementBucket}) blijven afhankelijk.
 *
 * <p>Priority 3: moet vroeg lopen zodat downstream consumers data hebben.
 */
@TrainingFeatureComponent(priority = 3)
public class MissionAnnotationFeatureComponent implements ITrainingFeature {

    private final MissionAnnotationFeatureEnricher enricher = new MissionAnnotationFeatureEnricher();

    @Override
    public Set<String> getFeatureIds() {
        return Set.of();
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
