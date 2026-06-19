package aiplay.scanners.feature.resolver.projectile;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.TrainingFeatureLogger;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.enemy.EnemySlotFeatureComponent;
import aiplay.scanners.feature.resolver.teammate.TeammateSlotFeatureComponent;

import java.util.HashSet;
import java.util.Set;

/**
 * Dynamic feature component voor projectile slots, gestructureerd per owner:
 * <ul>
 *   <li>{@code self_projectile{M}_*}                      — eigen projectielen</li>
 *   <li>{@code enemy{N}_projectile{M}_*}     (N=0..MAX_ENEMY-1)    — projectielen
 *       afgevuurd door de enemy in slot N</li>
 *   <li>{@code teammate{N}_projectile{M}_*}  (N=0..MAX_TEAMMATE-1) — projectielen
 *       afgevuurd door de teammate in slot N (toekomstig)</li>
 * </ul>
 *
 * <p>{@code M} is de projectile-index per owner (0..MAX_PROJ_PER_OWNER-1).
 *
 * <p>Priority 15 — na Enemy/Teammate (10) want projectiel-attribuutie hangt af
 * van enemy/teammate slot-toewijzing.
 */
@TrainingFeatureComponent(priority = 15)
public class ProjectileSlotFeatureComponent implements ITrainingFeature {

    public static final int MAX_PROJ_PER_OWNER = 7;
    public static final int MAX_ENEMY_OWNERS = EnemySlotFeatureComponent.MAX_SLOTS;
    public static final int MAX_TEAMMATE_OWNERS = TeammateSlotFeatureComponent.MAX_SLOTS;

    static final String[] FEATURE_SUFFIXES = {
        "present",
        "relSin", "relCos",
        "distance_norm",
        "pitchBearing_norm",
        "forwardVelocity_norm", "rightVelocity_norm",
        "speed_norm",
        "timeToImpact_norm",
        // Class one-hots — exact één hiervan is 1.0 wanneer present=1.0,
        // alle overige 0.0 (of allemaal 0 voor onbekende classes).
        "isGrenade",         // flak alt-fire grenade (UTFlakShell / flakslug)
        "isChunk",           // flak primary chunks (UTChunk1..4)
        "isShockBall",       // shock secondary blue ball (ShockProj)
        "isRocket",          // rocket primary rocket (RocketMk2)
        "isRocketGrenade",   // rocket alt-fire grenade (Grenade)
        "isBioBlob",         // bio primary spray glob (UT_BioGel)
        "isBioGlob",         // bio charged alt-fire glob (BioGlob)
        "isPulsePlasma",     // pulse primary plasma chunk (PlasmaSphere)
        "isRazor",           // ripper sawblade (Razor2)
        "isRedeemerMissile", // redeemer warhead (WarShell)
        "isTranslocatorDisc",// translocator target (TranslocatorTarget)
        // Damage / charge signaal
        "damage_norm",       // UC Projectile.Damage / 250, clamped [0,1]
        "chargeScale_norm",  // (UC Actor.DrawScale - 1) / 3, clamped [0,1] — vooral BioGlob
        // Offensieve projectiel↔enemy-relatie (generiek over wapens; vooral self-projectielen).
        // Geeft het model "scheert mijn projectiel langs een enemy + wanneer" — de grootheid die
        // we tot nu toe alleen in de reward berekenden (shock-combo, rocket/flak-splash-timing).
        "enemyClosestApproach_norm", // min 3D miss-margin tot dichtstbijzijnde enemy op huidige baan
        "enemyTimeToClosest_norm",   // tijd tot dat closest-approach-moment, [0,1] @ 2s
    };

    static final String[] BOOLEAN_SUFFIXES = {
        "present",
        "isGrenade", "isChunk", "isShockBall", "isRocket", "isRocketGrenade",
        "isBioBlob", "isBioGlob", "isPulsePlasma", "isRazor", "isRedeemerMissile",
        "isTranslocatorDisc",
    };

    private static final Set<String> CACHED_FEATURE_IDS;
    private static final Set<String> CACHED_BOOLEAN_FEATURES;

    static {
        int totalOwners = 1 + MAX_ENEMY_OWNERS + MAX_TEAMMATE_OWNERS;
        Set<String> ids = new HashSet<>(totalOwners * MAX_PROJ_PER_OWNER * FEATURE_SUFFIXES.length);
        Set<String> bools = new HashSet<>(totalOwners * MAX_PROJ_PER_OWNER * BOOLEAN_SUFFIXES.length);

        // self_projectile{M}_*
        for (int m = 0; m < MAX_PROJ_PER_OWNER; m++) {
            String prefix = "self_projectile" + m + "_";
            for (String s : FEATURE_SUFFIXES) ids.add(prefix + s);
            for (String s : BOOLEAN_SUFFIXES) bools.add(prefix + s);
        }
        // enemy{N}_projectile{M}_*
        for (int n = 0; n < MAX_ENEMY_OWNERS; n++) {
            for (int m = 0; m < MAX_PROJ_PER_OWNER; m++) {
                String prefix = "enemy" + n + "_projectile" + m + "_";
                for (String s : FEATURE_SUFFIXES) ids.add(prefix + s);
                for (String s : BOOLEAN_SUFFIXES) bools.add(prefix + s);
            }
        }
        // teammate{N}_projectile{M}_*
        for (int n = 0; n < MAX_TEAMMATE_OWNERS; n++) {
            for (int m = 0; m < MAX_PROJ_PER_OWNER; m++) {
                String prefix = "teammate" + n + "_projectile" + m + "_";
                for (String s : FEATURE_SUFFIXES) ids.add(prefix + s);
                for (String s : BOOLEAN_SUFFIXES) bools.add(prefix + s);
            }
        }

        CACHED_FEATURE_IDS = Set.copyOf(ids);
        CACHED_BOOLEAN_FEATURES = Set.copyOf(bools);
    }

    private final ProjectileSlotFeatureValueResolver resolver = new ProjectileSlotFeatureValueResolver();
    private final ProjectileSlotRelativeBatchEnricher enricher = new ProjectileSlotRelativeBatchEnricher();

    @Override public Set<String> getFeatureIds() { return CACHED_FEATURE_IDS; }
    @Override public Set<String> getBooleanFeatures() { return CACHED_BOOLEAN_FEATURES; }
    @Override public TrainingFeatureValueResolver getTrainingFeatureValueResolver() { return resolver; }
    @Override public TrainingFeatureEnricher getTrainingFeatureEnricher() { return enricher; }
    @Override public TrainingFeatureJsonToDtoConverter getTrainingFeatureJsonToDtoConverter() { return null; }
    @Override public TrainingFeatureLogger getTrainingFeatureLogger() { return null; }
}
