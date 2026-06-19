package aiplay.scanners.feature.resolver.translocator;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Translocator-disc awareness. UT99's translocator gooit een
 * {@code Botpack.TranslocatorTarget} actor (Decoration, geen Projectile);
 * de speler kan vervolgens primary-fire indrukken om naar de disc te
 * teleporteren. Het model moet weten waar zijn disc ligt en hoe lang
 * geleden hij geworpen werd om "wanneer teleporteer ik" goed te leren.
 *
 * <p>Features (alle self-only — enemy disc-tracking heeft geen aim/movement
 * waarde; bot kan niet zomaar enemy-disc onderscheppen):
 * <ul>
 *   <li>{@code self_disc_present} — 1.0 wanneer disc actief is.</li>
 *   <li>{@code self_disc_relSin} / {@code self_disc_relCos} — egocentric bearing.</li>
 *   <li>{@code self_disc_distance_norm} — 3D-afstand bot → disc.</li>
 *   <li>{@code self_disc_pitchBearing_norm} — verticale bearing eye → disc.</li>
 *   <li>{@code self_disc_rel_z_norm} — z-offset bot → disc, ±500 UU normalized.</li>
 *   <li>{@code self_disc_timeSinceThrow_norm} — 0..1, normaliseerd 1.0 sec
 *       (translocator throw → teleport-besluit is meestal &lt; 1s).</li>
 * </ul>
 *
 * <p>Bron-DTO-velden: {@code playerPawn.discPresent} + {@code playerPawn.discLocation}
 * (gevuld in {@code PlayerPawnBasicFeatureJsonToDtoConverter}) +
 * {@code playerPawn.discTimeSinceThrow_norm} (gevuld door
 * {@link TranslocatorDiscTrackingEnricher}).
 *
 * <p>Priority 5: enricher moet voor de feature-resolve klaar zijn.
 */
@TrainingFeatureComponent(priority = 5)
public class TranslocatorDiscFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "self_disc_present",
        "self_disc_relSin",
        "self_disc_relCos",
        "self_disc_distance_norm",
        "self_disc_pitchBearing_norm",
        "self_disc_rel_z_norm",
        "self_disc_timeSinceThrow_norm"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
        "self_disc_present"
    );

    private final TranslocatorDiscFeatureValueResolver resolver =
        new TranslocatorDiscFeatureValueResolver();
    private final TranslocatorDiscTrackingEnricher enricher =
        new TranslocatorDiscTrackingEnricher();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        return BOOLEAN_FEATURES;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }

    @Override
    public TrainingFeatureEnricher getTrainingFeatureEnricher() {
        return enricher;
    }
}
