package aiplay.scanners.feature.resolver.shootingtarget;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.shared.shooting.ShootingTargetIndexBus;

import java.util.List;

/**
 * Phase 2e: populates {@code frame.annotatedShootingTargetIndex} for both
 * runtime inference (read from {@link ShootingTargetIndexBus}) and CSV-writer
 * training (computed via post-hoc kill attribution + closest-cosine fallback,
 * mirroring the labels {@code RLPawnTargetProjector} uses for joint
 * BC training).
 *
 * <p>VR's input feature group {@code target_index_onehot_0..4} reads this
 * field via {@code ShootingTargetIndexFeatureValueResolver} so VR sees the
 * same enemy slot the shooting model picks, instead of having to infer it
 * indirectly via reward attribution.</p>
 *
 * <p>The two paths diverge:
 * <ul>
 *   <li><b>Runtime ({@link #enrichIncremental})</b>: target_index = bus.latest(sessionId).
 *       The shooting executor publishes the model's argmax (deterministic) or
 *       sampled (stochastic training) choice every tick. Bus.ABSENT (-1) sentinel
 *       maps to {@code annotatedShootingTargetIndex = -1} → all 5 onehot features
 *       output 0.0 (matches "no choice yet" prior).</li>
 *   <li><b>CSV training ({@link #enrichBatch})</b>: target_index from K-tick
 *       lookahead kill attribution. We don't have a sessionId at training time
 *       (recordings predate Phase 2). The kill attribution gives the same
 *       semantic signal that BC shooting trains its target_head against, so
 *       VR BC sees aligned labels.</li>
 * </ul></p>
 */
public class ShootingTargetIndexEnricher implements TrainingFeatureEnricher {

    /** Lookahead window for CSV-time kill attribution; matches the shooting projector. */
    private static final int KILL_ATTRIBUTION_WINDOW = 30;
    private static final int MAX_ENEMY_SLOTS = 5;

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        for (int i = 0; i < frames.size(); i++) {
            GameStateDto frame = frames.get(i);
            if (frame == null) continue;
            frame.annotatedShootingTargetIndex = computeForCsvFrame(frames, i);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        int latest = ShootingTargetIndexBus.latest(sessionId);
        for (GameStateDto frame : frames) {
            if (frame == null) continue;
            frame.annotatedShootingTargetIndex = latest;
        }
    }

    /** Post-hoc kill attribution at frame index in a recorded batch.
     *  Returns the enemy slot with the largest HP loss within
     *  {@link #KILL_ATTRIBUTION_WINDOW} ticks, or closest-visible fallback. */
    private static int computeForCsvFrame(List<GameStateDto> frames, int idx) {
        GameStateDto current = frames.get(idx);
        if (current == null || current.enemies == null || current.enemies.length == 0) {
            return -1;
        }

        int hitSlot = findHitSlotByLookahead(frames, idx);
        if (hitSlot >= 0) return hitSlot;

        int closestVisible = findClosestVisibleEnemySlot(current);
        if (closestVisible >= 0) return closestVisible;
        return findClosestEnemySlot(current);
    }

    private static int findHitSlotByLookahead(List<GameStateDto> frames, int idx) {
        int endIdx = Math.min(idx + KILL_ATTRIBUTION_WINDOW, frames.size() - 1);
        if (endIdx <= idx) return -1;
        GameStateDto current = frames.get(idx);
        if (current.enemies == null) return -1;

        int n = Math.min(current.enemies.length, MAX_ENEMY_SLOTS);
        int[] worstHp = new int[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            PlayerDto e = current.enemies[i];
            if (e == null || e.health <= 0 || e.name == null) {
                valid[i] = false;
                worstHp[i] = Integer.MIN_VALUE;
            } else {
                valid[i] = true;
                worstHp[i] = e.health;
            }
        }

        for (int t = idx + 1; t <= endIdx; t++) {
            GameStateDto frame = frames.get(t);
            if (frame == null || frame.enemies == null) continue;
            for (int slot = 0; slot < n; slot++) {
                if (!valid[slot]) continue;
                PlayerDto cur = current.enemies[slot];
                PlayerDto match = findByName(frame.enemies, cur.name);
                if (match == null) {
                    worstHp[slot] = 0;  // disappeared = killed
                } else if (match.health < worstHp[slot]) {
                    worstHp[slot] = match.health;
                }
            }
        }

        int bestSlot = -1, bestLoss = 0;
        for (int slot = 0; slot < n; slot++) {
            if (!valid[slot]) continue;
            int loss = current.enemies[slot].health - worstHp[slot];
            if (loss > bestLoss) {
                bestLoss = loss;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static int findClosestVisibleEnemySlot(GameStateDto frame) {
        if (frame.enemies == null || frame.playerPawn == null || frame.playerPawn.location == null) return -1;
        int n = Math.min(frame.enemies.length, MAX_ENEMY_SLOTS);
        int bestSlot = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            PlayerDto e = frame.enemies[i];
            if (e == null || e.health <= 0 || e.location == null || !e.enemyVisible) continue;
            double dx = e.location.x - frame.playerPawn.location.x;
            double dy = e.location.y - frame.playerPawn.location.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestDist) { bestDist = d; bestSlot = i; }
        }
        return bestSlot;
    }

    private static int findClosestEnemySlot(GameStateDto frame) {
        if (frame.enemies == null || frame.playerPawn == null || frame.playerPawn.location == null) return -1;
        int n = Math.min(frame.enemies.length, MAX_ENEMY_SLOTS);
        int bestSlot = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            PlayerDto e = frame.enemies[i];
            if (e == null || e.health <= 0 || e.location == null) continue;
            double dx = e.location.x - frame.playerPawn.location.x;
            double dy = e.location.y - frame.playerPawn.location.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestDist) { bestDist = d; bestSlot = i; }
        }
        return bestSlot;
    }

    private static PlayerDto findByName(PlayerDto[] enemies, String name) {
        if (enemies == null || name == null) return null;
        for (PlayerDto e : enemies) if (e != null && name.equals(e.name)) return e;
        return null;
    }
}
