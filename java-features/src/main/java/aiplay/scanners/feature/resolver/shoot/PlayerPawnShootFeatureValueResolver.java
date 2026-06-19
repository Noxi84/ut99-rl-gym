package aiplay.scanners.feature.resolver.shoot;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

public class PlayerPawnShootFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        switch (featureId) {
            case "bFire":
                return (f.playerPawn != null && f.playerPawn.bFire != null) ? f.playerPawn.bFire.value_norm : 0.0f;
            case "bAltFire":
                return (f.playerPawn != null && f.playerPawn.bAltFire != null) ? f.playerPawn.bAltFire.value_norm : 0.0f;
            case "fireActive":
                return (f.playerPawn != null && f.playerPawn.fireActive) ? 1.0f : 0.0f;
            case "fireCooldown":
                return (f.playerPawn != null && f.playerPawn.fireCooldown) ? 1.0f : 0.0f;
            case "altFireActive":
                return (f.playerPawn != null && f.playerPawn.altFireActive) ? 1.0f : 0.0f;
            case "altFireCooldown":
                return (f.playerPawn != null && f.playerPawn.altFireCooldown) ? 1.0f : 0.0f;
        }
        return null;
    }
}
