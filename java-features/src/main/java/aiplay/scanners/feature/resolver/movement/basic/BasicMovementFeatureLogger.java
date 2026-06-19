package aiplay.scanners.feature.resolver.movement.basic;

import aiplay.dto.GameStateDto;
import aiplay.rl.MovementPrimitive;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureLogger;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class BasicMovementFeatureLogger implements TrainingFeatureLogger {

    @Override
    public Set<String> getLogFiles() {
        return Set.of("Movement");
    }

    @Override
    public void onEnrichIncremental(String sessionId, String modelKey, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        if (aiplay.config.global.GlobalConfigRepository.shared().debug().sanityEnabled() && frames != null && !frames.isEmpty()) {
            GameStateDto last = frames.get(frames.size() - 1);

            String primitive = "n/a";
            String bJump = "n/a";
            String bDuck = "n/a";

            try {
                if (last != null && last.playerPawn != null && last.playerPawn.playerPawn != null) {
                    primitive = MovementPrimitive.fromGameState(last).getFeatureId();
                    if (last.playerPawn.playerPawn.bJump != null) bJump = String.valueOf(last.playerPawn.playerPawn.bJump.value_norm);
                }
                if (last != null && last.playerPawn != null && last.playerPawn.bDuck != null) {
                    bDuck = String.valueOf(last.playerPawn.bDuck.value_norm);
                }
            } catch (Exception ignored) {
            }

            String line = modelKey + "-onEnrichIncrementalSanity"
                    + " component=" + (featureComponent == null ? "null" : featureComponent.getClass().getSimpleName())
                    + " frames=" + frames.size()
                    + " primitive=" + primitive
                    + " bJump=" + bJump
                    + " bDuck=" + bDuck
                    + buildFrameExtra(last);

            logLine(sessionId, Level.INFO, line);
        }

        TrainingFeatureLogger.super.onEnrichIncremental(sessionId, modelKey, featureComponent, frames);
    }
}
