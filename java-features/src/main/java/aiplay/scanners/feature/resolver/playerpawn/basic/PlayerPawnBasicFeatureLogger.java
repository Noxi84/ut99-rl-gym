package aiplay.scanners.feature.resolver.playerpawn.basic;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureLogger;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class PlayerPawnBasicFeatureLogger implements TrainingFeatureLogger {

    @Override
    public Set<String> getLogFiles() {
        return Set.of("PlayerPawn");
    }

    @Override
    public void onEnrichIncremental(String sessionId, String modelKey, ITrainingFeature featureComponent, List<GameStateDto> frames) {
        // Extra “sanity” lijnen om meteen te zien of dtos wel gevuld worden tijdens realtime.
        if (aiplay.config.global.GlobalConfigRepository.shared().debug().sanityEnabled() && frames != null && !frames.isEmpty()) {
            GameStateDto last = frames.get(frames.size() - 1);

            String hasPawn = (last != null && last.playerPawn != null) ? "Y" : "N";
            String hasLoc = (last != null && last.playerPawn != null && last.playerPawn.location != null) ? "Y" : "N";
            String hasVr = (last != null && last.playerPawn != null && last.playerPawn.viewRotation != null) ? "Y" : "N";
            String hasCol = (last != null && last.playerPawn != null && last.playerPawn.collisions != null) ? "Y" : "N";

            String line = modelKey + "-onEnrichIncrementalSanity"
                    + " component=" + (featureComponent == null ? "null" : featureComponent.getClass().getSimpleName())
                    + " frames=" + frames.size()
                    + " pawn=" + hasPawn
                    + " loc=" + hasLoc
                    + " vr=" + hasVr
                    + " col=" + hasCol
                    + buildFrameExtra(last);

            logLine(sessionId, Level.INFO, line);
        }

        TrainingFeatureLogger.super.onEnrichIncremental(sessionId, modelKey, featureComponent, frames);
    }
}
