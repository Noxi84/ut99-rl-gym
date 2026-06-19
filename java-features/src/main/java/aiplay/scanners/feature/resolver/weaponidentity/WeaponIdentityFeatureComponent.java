package aiplay.scanners.feature.resolver.weaponidentity;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Wapen-identity one-hot voor 14 UT99 wapens. Per tick is exact één bit aan
 * (de canonical name van {@code PlayerDto.weaponClass}), of alle bits 0 als
 * de bot geen wapen draagt.
 *
 * <p>Bron: {@code PlayerDto.weaponClass} — al beschikbaar uit de bestaande UC
 * binary frame, geen wijziging in {@code RLUdpStateSender.WriteWeapon} nodig.
 * De resolver canonicaliseert raw class-namen ({@code "Botpack.PulseGun"},
 * {@code "NeuralNetWebserver.RLPulseGun"}, {@code "Botpack.UT_Eightball"})
 * naar dezelfde keys als de Python {@code _canonical_weapon} voor consistente
 * per-weapon attribution tussen probe-strata (validation-time) en DeltaGate
 * baselines (in-game KPI eval).
 *
 * <p>Toevoeging aan {@code features.json} verandert de input-dimensie van
 * het model met 14 features → BC en SAC moeten beide vanaf scratch. De
 * trainings-pipeline accepteert dit zodra {@code features.json} en de Java
 * emission consistent zijn (config-driven, geen hardcoded sizes).
 */
@TrainingFeatureComponent(priority = 10)
public class WeaponIdentityFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS =
        WeaponIdentityFeatureValueResolver.FEATURE_TO_WEAPON.keySet();

    private final WeaponIdentityFeatureValueResolver resolver =
        new WeaponIdentityFeatureValueResolver();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Set<String> getBooleanFeatures() {
        // Alle 14 one-hot bits zijn binair (0.0 of 1.0).
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
