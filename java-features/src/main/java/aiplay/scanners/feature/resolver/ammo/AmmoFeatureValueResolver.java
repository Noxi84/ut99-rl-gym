package aiplay.scanners.feature.resolver.ammo;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

/**
 * Resolves {@code self_ammo_currWeapon_norm} uit {@code playerPawn.weaponAmmo /
 * playerPawn.weaponMaxAmmo}. Velden worden gevuld door
 * {@code PlayerPawnBasicFeatureJsonToDtoConverter} vanuit het UC weapon-block.
 */
public class AmmoFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        if (!"self_ammo_currWeapon_norm".equals(featureId)) return null;
        PlayerDto pawn = (f != null) ? f.playerPawn : null;
        if (pawn == null) return 0.0f;
        int max = pawn.weaponMaxAmmo;
        if (max <= 0) return 0.0f;
        int ammo = pawn.weaponAmmo;
        if (ammo < 0) ammo = 0;
        if (ammo > max) ammo = max;
        return (float) ammo / (float) max;
    }
}
