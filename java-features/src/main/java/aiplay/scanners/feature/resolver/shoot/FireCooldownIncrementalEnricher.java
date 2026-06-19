package aiplay.scanners.feature.resolver.shoot;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental enricher that tracks fire + altFire state transitions to derive
 * fireActive/fireCooldown and altFireActive/altFireCooldown features.
 * Analogous to dodge state tracking.
 *
 * <p>fireActive = bFire is currently 1 (weapon is firing this frame)
 * <p>fireCooldown = bFire was 1 in the previous frame but is 0 now (weapon reloading)
 * <p>altFireActive / altFireCooldown: same for bAltFire.
 */
public class FireCooldownIncrementalEnricher implements TrainingFeatureEnricher {

    private final ConcurrentHashMap<String, boolean[]> prev = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        boolean prevFire = false;
        boolean prevAlt = false;
        for (GameStateDto f : frames) {
            if (f != null && f.playerPawn != null) {
                boolean curFire = f.playerPawn.bFire != null && f.playerPawn.bFire.value_norm > 0.5f;
                boolean curAlt = f.playerPawn.bAltFire != null && f.playerPawn.bAltFire.value_norm > 0.5f;
                f.playerPawn.fireActive = curFire;
                f.playerPawn.fireCooldown = !curFire && prevFire;
                f.playerPawn.altFireActive = curAlt;
                f.playerPawn.altFireCooldown = !curAlt && prevAlt;
                prevFire = curFire;
                prevAlt = curAlt;
            }
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        boolean[] state = prev.computeIfAbsent(sessionId, k -> new boolean[2]);
        for (GameStateDto f : frames) {
            if (f == null || f.playerPawn == null) continue;

            boolean curFire = f.playerPawn.bFire != null && f.playerPawn.bFire.value_norm > 0.5f;
            boolean curAlt = f.playerPawn.bAltFire != null && f.playerPawn.bAltFire.value_norm > 0.5f;

            f.playerPawn.fireActive = curFire;
            f.playerPawn.fireCooldown = !curFire && state[0];
            f.playerPawn.altFireActive = curAlt;
            f.playerPawn.altFireCooldown = !curAlt && state[1];

            state[0] = curFire;
            state[1] = curAlt;
        }
    }
}
