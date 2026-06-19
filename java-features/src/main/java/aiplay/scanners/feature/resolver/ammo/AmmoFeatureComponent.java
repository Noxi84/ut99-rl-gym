package aiplay.scanners.feature.resolver.ammo;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Ammo-awareness voor het actieve wapen.
 *
 * <p>Eén feature: {@code self_ammo_currWeapon_norm} = {@code weaponAmmo / max(1, weaponMaxAmmo)}
 * geclamped naar [0,1]. Stuurt conservatieve-fire-besluiten (vermijd lege chamber,
 * switch wapen wanneer leeg) en weapon-pickup-prioritisering bij weinig ammo.
 *
 * <p>Per-wapen ammo-features zijn bewust NIET opgenomen — de bot conditioneert
 * sowieso op de {@code WeaponIdentityFeatureComponent} one-hot, dus current-weapon
 * ammo volstaat voor het meeste gedrag. Toevoegen van 14 per-wapen ammo-features
 * bij behoefte is een eenvoudige uitbreiding.
 */
@TrainingFeatureComponent(priority = 10)
public class AmmoFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "self_ammo_currWeapon_norm"
    );

    private final AmmoFeatureValueResolver resolver = new AmmoFeatureValueResolver();

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
        return resolver;
    }
}
