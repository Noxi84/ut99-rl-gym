package aiplay.scanners.feature.resolver.weaponstate;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Resolver voor de {@code self_weapon_*} features. Leest direct uit
 * {@code playerPawn.weapon{Charge,MultiCount,GrenadeMode,Sniping}}.
 */
public class WeaponStateFeatureValueResolver implements TrainingFeatureValueResolver {

    private final float bioChargeMax;
    private final float eightballMultiMax;

    public WeaponStateFeatureValueResolver(float bioChargeMax, float eightballMultiMax) {
        this.bioChargeMax = bioChargeMax;
        this.eightballMultiMax = eightballMultiMax;
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        PlayerDto pawn = (f != null) ? f.playerPawn : null;
        if (pawn == null) return 0.0f;
        return switch (featureId) {
            case "self_weapon_chargeLevel_norm" -> normalize(pawn.weaponChargeAmount, bioChargeMax);
            case "self_weapon_multiCount_norm" -> normalize(pawn.weaponMultiCount, eightballMultiMax);
            case "self_weapon_grenadeMode"     -> pawn.weaponGrenadeMode ? 1.0f : 0.0f;
            case "self_weapon_sniping"         -> pawn.weaponSniping ? 1.0f : 0.0f;
            case "self_weapon_tightWad"        -> pawn.weaponTightWad ? 1.0f : 0.0f;
            default -> null;
        };
    }

    private static float normalize(int raw, float max) {
        if (max <= 0) return 0.0f;
        float v = raw / max;
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }
}
