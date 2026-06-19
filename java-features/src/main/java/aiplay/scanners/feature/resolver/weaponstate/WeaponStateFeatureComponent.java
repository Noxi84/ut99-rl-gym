package aiplay.scanners.feature.resolver.weaponstate;

import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.Set;

/**
 * Wapen-specifieke runtime-state die niet uit {@code weaponClass} af te leiden is.
 * Zorgt ervoor dat het joint-model conditioneel-gedrag kan leren per wapen-mode:
 *
 * <ul>
 *   <li>{@code self_weapon_chargeLevel_norm} — Bio-rifle alt-fire charge (0..1).
 *       Bot kan strategisch oppakken: groter blob = meer damage, max ~10 ticks.</li>
 *   <li>{@code self_weapon_multiCount_norm} — UT_Eightball loadcount (0..6 → 0..1).
 *       Bot kan beslissen "1 rocket nu of opladen tot 6 voor groep-target".</li>
 *   <li>{@code self_weapon_grenadeMode} — UT_Eightball alt-fire grenade-mode.
 *       Onderscheidt rockets vs grenades in dezelfde MultiCount-load.</li>
 *   <li>{@code self_weapon_sniping} — Sniper scope-zoom actief (FOV reduced).
 *       Belangrijk voor view-rotation policy: scope = trage micro-aim,
 *       no-scope = vrije pan.</li>
 *   <li>{@code self_weapon_tightWad} — Eightball altFire-tijdens-load flag.
 *       Onderscheidt tight-wad (focused beam) van spread (horizontaal)
 *       rocket-firing mode. Critical state-bit voor rocket-launcher policy.</li>
 * </ul>
 *
 * <p>Velden worden gevuld door {@code RLUdpStateSender.WriteWeapon} →
 * {@code udpstate.model.Weapon} → {@code Player.bIsDual/bSniping/bGrenadeMode/
 * MultiCount/ChargeAmount} → {@code PlayerDto.weaponMultiCount/...}.
 *
 * <p>{@code bIsDual} is NIET in deze component — die wordt via een aparte
 * one-hot bit per actief weapon-class verwerkt door
 * {@code WeaponIdentityFeatureComponent}.
 */
@TrainingFeatureComponent(priority = 10)
public class WeaponStateFeatureComponent implements ITrainingFeature {

    /** Bio rifle stock max ChargeSize is 10. Normaliseer naar 0..1. */
    private static final float BIO_CHARGE_MAX = 10.0f;
    /** UT_Eightball MultiCount caps op 6 (max 6 loaded rockets/grenades). */
    private static final float EIGHTBALL_MULTI_MAX = 6.0f;

    private static final Set<String> FEATURE_IDS = Set.of(
        "self_weapon_chargeLevel_norm",
        "self_weapon_multiCount_norm",
        "self_weapon_grenadeMode",
        "self_weapon_sniping",
        "self_weapon_tightWad"
    );

    private static final Set<String> BOOLEAN_FEATURES = Set.of(
        "self_weapon_grenadeMode",
        "self_weapon_sniping",
        "self_weapon_tightWad"
    );

    private final WeaponStateFeatureValueResolver resolver =
        new WeaponStateFeatureValueResolver(BIO_CHARGE_MAX, EIGHTBALL_MULTI_MAX);

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
}
