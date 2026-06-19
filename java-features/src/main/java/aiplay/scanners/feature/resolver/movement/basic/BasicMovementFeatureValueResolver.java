package aiplay.scanners.feature.resolver.movement.basic;

import aiplay.rl.MovementPrimitive;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.dto.GameStateDto;

public class BasicMovementFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        return switch (featureId) {
            case "moveIdle" -> MovementPrimitive.fromGameState(f) == MovementPrimitive.IDLE ? 1.0f : 0.0f;
            case "bDuck" -> (f.playerPawn != null && f.playerPawn.bDuck != null) ? f.playerPawn.bDuck.value_norm : 0.0f;
            case "bJump" -> (f.playerPawn != null && f.playerPawn.playerPawn != null && f.playerPawn.playerPawn.bJump != null)
                    ? f.playerPawn.playerPawn.bJump.value_norm : 0.0f;
            default -> null;
        };
    }
}
