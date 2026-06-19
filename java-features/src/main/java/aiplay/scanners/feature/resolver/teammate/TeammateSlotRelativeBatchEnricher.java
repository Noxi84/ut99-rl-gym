package aiplay.scanners.feature.resolver.teammate;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.resolver.PlayerSlotDodgeTracker;
import aiplay.scanners.feature.resolver.enemy.EnemySlotRelativeBatchEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enriches frames with relative features + dodge tracking for ALL teammate slots.
 * Mirrors {@link EnemySlotRelativeBatchEnricher} but reads from dto.teammates[].
 */
public class TeammateSlotRelativeBatchEnricher implements TrainingFeatureEnricher {

    private static final int MAX_TEAMMATE_SLOTS = TeammateSlotFeatureComponent.MAX_SLOTS;
    private static final int CSV_FPS = 30;

    private final ConcurrentHashMap<String, PlayerSlotDodgeTracker.IncrementalState[]> sessions =
        new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;

        PlayerSlotDodgeTracker.BatchState dodgeState =
            new PlayerSlotDodgeTracker.BatchState(MAX_TEAMMATE_SLOTS);
        double frameDurationMs = 1000.0 / CSV_FPS;

        for (int i = 0; i < frames.size(); i++) {
            GameStateDto f = frames.get(i);
            if (f == null) continue;
            enrichRelativePositions(f);
            PlayerSlotDodgeTracker.updateBatch(f.teammates, i, dodgeState, frameDurationMs,
                EnemySlotRelativeBatchEnricher.DODGE_COOLDOWN_MS);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        PlayerSlotDodgeTracker.IncrementalState[] slots = sessions.computeIfAbsent(
            sessionId, k -> PlayerSlotDodgeTracker.createIncrementalSlots(MAX_TEAMMATE_SLOTS));
        long now = System.currentTimeMillis();
        for (GameStateDto f : frames) {
            if (f == null) continue;
            enrichRelativePositions(f);
            PlayerSlotDodgeTracker.updateIncremental(f.teammates, slots, now,
                EnemySlotRelativeBatchEnricher.DODGE_COOLDOWN_MS);
        }
    }

    private void enrichRelativePositions(GameStateDto f) {
        PlayerDto self = f.playerPawn;
        if (self == null || self.location == null || self.viewRotation == null) return;

        int numSlots = (f.teammates != null) ? f.teammates.length : 0;
        if (numSlots == 0) {
            self.enrichments.teammateRels = null;
            return;
        }

        if (self.enrichments.teammateRels == null || self.enrichments.teammateRels.length != numSlots) {
            self.enrichments.teammateRels = new PlayerRelationDto[numSlots];
        }

        final int viewX = self.viewRotation.x & 0xFFFF;

        for (int i = 0; i < numSlots; i++) {
            PlayerDto teammate = f.teammates[i];
            if (teammate == null || teammate.health <= 0 || teammate.location == null) {
                self.enrichments.teammateRels[i] = null;
            } else {
                PlayerRelationDto rel = EnemySlotRelativeBatchEnricher.buildRelation(
                    self.location, viewX, teammate.location);
                rel.pitchBearing_norm = EnemySlotRelativeBatchEnricher.computePitchBearingNorm(
                    self.location, self.baseEyeHeight, teammate.location, teammate.baseEyeHeight);
                rel.relZ_norm = EnemySlotRelativeBatchEnricher.computeRelZNorm(
                    self.location, teammate.location);
                EnemySlotRelativeBatchEnricher.applyRelativeVelocity(rel, viewX,
                    teammate.velocityX_norm, teammate.velocityY_norm, teammate.velocityZ_norm);
                self.enrichments.teammateRels[i] = rel;
            }
        }
    }
}
