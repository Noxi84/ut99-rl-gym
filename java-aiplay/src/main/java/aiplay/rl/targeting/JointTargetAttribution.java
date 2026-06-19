package aiplay.rl.targeting;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

import java.util.List;

/**
 * Pure-static utility voor de target_index aux head supervision die de joint
 * VR+shooting pipeline gebruikt. Consolideert kill-attribution + closest-visible
 * heuristieken die voorheen in drie plaatsen gedupliceerd stonden:
 *
 * <ul>
 *   <li>{@code RLPawnTargetProjector} — CSV BC labels voor het joint
 *       movement+VR+shooting model.</li>
 *   <li>{@code PerModelExperienceRecorder} — live RL target supervision +
 *       retro-fill bij frag events.</li>
 * </ul>
 *
 * <p>Geen Spring, geen FeatureService dependency — alleen GameStateDto inputs
 * en eenvoudige primitieve helpers. Mock-vrij testbaar.</p>
 *
 * <h2>Confidence schema (sectie 4.3 van vr-shooting-sac-merge.md)</h2>
 * <ul>
 *   <li>{@link #CONF_HIT} = 1.0 — kill bevestigd binnen lookahead window of
 *       retro-fill via frag event.</li>
 *   <li>{@link #CONF_FIRE_NO_HIT} = 0.3 — fire-edge frame zonder geattribueerde
 *       kill (zwakker signaal, geeft de policy ruimte voor exploratie).</li>
 *   <li>{@link #CONF_NON_FIRE} = 0.1 — niet-fire frame, implicit tracking
 *       target (closest-visible enemy).</li>
 *   <li>{@link #CONF_MASKED} = 0.0 — geen enemies aanwezig, supervisie
 *       gemaskeerd.</li>
 * </ul>
 */
public final class JointTargetAttribution {

    public static final float CONF_HIT = 1.0f;
    public static final float CONF_FIRE_NO_HIT = 0.3f;
    public static final float CONF_NON_FIRE = 0.1f;
    public static final float CONF_MASKED = 0.0f;

    public static final int MAX_ENEMY_SLOTS = 5;

    /** Lookahead window voor kill attribution op 30 Hz CSV. ~1 s — voldoende
     *  voor flak shells en rocket projectiles. Matches de K-tick ringbuffer
     *  in {@code PerModelExperienceRecorder}. */
    public static final int KILL_ATTRIBUTION_WINDOW_TICKS = 30;

    /** Label record: slot (0..MAX_ENEMY_SLOTS-1, of -1 wanneer gemaskeerd) en
     *  confidence (zie {@link JointTargetAttribution} class-Javadoc). */
    public record TargetLabel(int slot, float confidence) {
        public static final TargetLabel MASKED = new TargetLabel(-1, CONF_MASKED);
    }

    private JointTargetAttribution() {}

    /**
     * Compute het provisionele target_label voor een tick zonder lookahead —
     * gebruikt door de live recorder waar het venster nog niet beschikbaar is
     * (retro-fill loopt los daarvan). Combineert closest-visible heuristiek met
     * fire-edge confidence-bump.
     *
     * @param current     frame waarop het label van toepassing is
     * @param fireEdge    true als {@code prev → current} een fire-onset bevat
     * @return non-null TargetLabel; {@link TargetLabel#MASKED} wanneer geen
     *         enemies aanwezig zijn op {@code current}
     */
    public static TargetLabel provisional(GameStateDto current, boolean fireEdge) {
        if (current == null || current.enemies == null || current.enemies.length == 0
                || !anyEnemyPresent(current)) {
            return TargetLabel.MASKED;
        }
        int slot = findClosestVisibleEnemySlot(current);
        if (slot < 0) {
            slot = findClosestEnemySlot(current);
        }
        if (slot < 0) {
            return TargetLabel.MASKED;
        }
        return new TargetLabel(slot, fireEdge ? CONF_FIRE_NO_HIT : CONF_NON_FIRE);
    }

    /**
     * Compute het label voor een offline pipeline waar de hele session-window
     * beschikbaar is. Probeer eerst kill-attribution via HP-drop lookahead;
     * fall back op closest-visible + fire-edge confidence.
     *
     * @param frames        complete sessie-frames in chronologische volgorde
     * @param currentIndex  index van het frame waarvoor het label berekend wordt
     * @param windowTicks   lookahead grootte (≤ {@link #KILL_ATTRIBUTION_WINDOW_TICKS}
     *                     voor consistentie met de live retro-fill); negatieve
     *                     waarden vallen terug op het default window
     */
    public static TargetLabel offline(List<GameStateDto> frames, int currentIndex, int windowTicks) {
        if (frames == null || currentIndex < 0 || currentIndex >= frames.size()) {
            return TargetLabel.MASKED;
        }
        GameStateDto current = frames.get(currentIndex);
        if (current == null || current.enemies == null || current.enemies.length == 0
                || !anyEnemyPresent(current)) {
            return TargetLabel.MASKED;
        }

        int effectiveWindow = (windowTicks > 0) ? windowTicks : KILL_ATTRIBUTION_WINDOW_TICKS;
        int hitSlot = findHitSlotByLookahead(frames, currentIndex, effectiveWindow);
        if (hitSlot >= 0) {
            return new TargetLabel(hitSlot, CONF_HIT);
        }

        boolean fireEdge = currentIndex > 0
                && isFireEdge(frames.get(currentIndex - 1), current);
        int slot = findClosestVisibleEnemySlot(current);
        if (slot < 0) {
            slot = findClosestEnemySlot(current);
        }
        if (slot < 0) {
            return TargetLabel.MASKED;
        }
        return new TargetLabel(slot, fireEdge ? CONF_FIRE_NO_HIT : CONF_NON_FIRE);
    }

    /** True wanneer {@code prev → curr} een fire- of altFire-onset bevat
     *  (transitie van inactief naar actief op één van beide vuurknoppen). */
    public static boolean isFireEdge(GameStateDto prev, GameStateDto curr) {
        if (prev == null || curr == null
                || prev.playerPawn == null || curr.playerPawn == null) {
            return false;
        }
        boolean firePressed = !prev.playerPawn.fireActive && curr.playerPawn.fireActive;
        boolean altFirePressed = !prev.playerPawn.altFireActive && curr.playerPawn.altFireActive;
        return firePressed || altFirePressed;
    }

    /**
     * HP-loss attribution: zoek welk enemy-slot het meeste health verloor
     * tussen {@code currentIndex} en {@code currentIndex + windowTicks}.
     * Enemy-matching gebeurt op naam zodat slot-reshuffles (bij gesorteerde
     * enemy-arrays) het venster niet breken.
     *
     * @return slot index (0..MAX_ENEMY_SLOTS-1) of -1 wanneer geen kill /
     *         significante HP-loss gedetecteerd is binnen het venster
     */
    public static int findHitSlotByLookahead(List<GameStateDto> frames, int currentIndex, int windowTicks) {
        if (frames == null || currentIndex < 0 || currentIndex >= frames.size()) {
            return -1;
        }
        GameStateDto current = frames.get(currentIndex);
        if (current == null || current.enemies == null) return -1;

        int endIdx = Math.min(currentIndex + windowTicks, frames.size() - 1);
        if (endIdx <= currentIndex) return -1;

        int n = Math.min(current.enemies.length, MAX_ENEMY_SLOTS);
        int[] worstHpSeen = new int[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            PlayerDto e = current.enemies[i];
            if (e == null || e.health <= 0 || e.name == null) {
                worstHpSeen[i] = Integer.MIN_VALUE;
                valid[i] = false;
            } else {
                worstHpSeen[i] = e.health;
                valid[i] = true;
            }
        }

        for (int t = currentIndex + 1; t <= endIdx; t++) {
            GameStateDto frame = frames.get(t);
            if (frame == null || frame.enemies == null) continue;
            for (int slot = 0; slot < n; slot++) {
                if (!valid[slot]) continue;
                PlayerDto cur = current.enemies[slot];
                PlayerDto matched = findByName(frame.enemies, cur.name);
                if (matched == null) {
                    worstHpSeen[slot] = 0;
                    continue;
                }
                if (matched.health < worstHpSeen[slot]) {
                    worstHpSeen[slot] = matched.health;
                }
            }
        }

        int bestSlot = -1;
        int bestLoss = 0;
        for (int slot = 0; slot < n; slot++) {
            if (!valid[slot]) continue;
            int loss = current.enemies[slot].health - worstHpSeen[slot];
            if (loss > bestLoss) {
                bestLoss = loss;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * Identificeer welke enemy in de transitie {@code prev → curr} is omgekomen.
     * Pad 1: HP &gt; 0 → ≤ 0 met onze slot als {@code lastDamageInstigatorSlot}
     * (UC engine attribution). Pad 2: HP &gt; 0 → ≤ 0 zonder instigator
     * bevestiging. Pad 3: slot met de grootste HP-drop (≥ 1).
     *
     * @return slot index (0..MAX_ENEMY_SLOTS-1) of -1 wanneer geen kill
     *         gedetecteerd kon worden tussen de twee frames
     */
    public static int identifyKilledEnemySlot(GameStateDto prev, GameStateDto curr) {
        if (prev == null || curr == null) return -1;
        PlayerDto[] prevE = prev.enemies;
        PlayerDto[] currE = curr.enemies;
        if (prevE == null || currE == null) return -1;
        int n = Math.min(Math.min(prevE.length, currE.length), MAX_ENEMY_SLOTS);
        int ownSlot = (curr.playerPawn != null) ? curr.playerPawn.slot : -1;

        for (int s = 0; s < n; s++) {
            PlayerDto pe = prevE[s];
            PlayerDto ce = currE[s];
            if (pe == null || ce == null) continue;
            if (pe.health > 0 && ce.health <= 0
                    && ownSlot >= 0 && ce.lastDamageInstigatorSlot == ownSlot) {
                return s;
            }
        }

        for (int s = 0; s < n; s++) {
            PlayerDto pe = prevE[s];
            PlayerDto ce = currE[s];
            if (pe == null || ce == null) continue;
            if (pe.health > 0 && ce.health <= 0) {
                return s;
            }
        }

        int bestSlot = -1;
        int bestDrop = 0;
        for (int s = 0; s < n; s++) {
            PlayerDto pe = prevE[s];
            PlayerDto ce = currE[s];
            if (pe == null || ce == null) continue;
            int drop = pe.health - Math.max(0, ce.health);
            if (drop > bestDrop) {
                bestDrop = drop;
                bestSlot = s;
            }
        }
        return bestSlot;
    }

    /** Slot van de dichtstbijzijnde levende en zichtbare enemy in {@code frame}.
     *  -1 wanneer er geen zichtbare enemies zijn (val terug op
     *  {@link #findClosestEnemySlot}). */
    public static int findClosestVisibleEnemySlot(GameStateDto frame) {
        if (frame == null || frame.enemies == null
                || frame.playerPawn == null || frame.playerPawn.location == null) {
            return -1;
        }
        int n = Math.min(frame.enemies.length, MAX_ENEMY_SLOTS);
        int best = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            PlayerDto e = frame.enemies[i];
            if (e == null || e.health <= 0 || e.location == null || !e.enemyVisible) continue;
            double dx = e.location.x - frame.playerPawn.location.x;
            double dy = e.location.y - frame.playerPawn.location.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Slot van de dichtstbijzijnde levende enemy ongeacht zichtbaarheid. */
    public static int findClosestEnemySlot(GameStateDto frame) {
        if (frame == null || frame.enemies == null
                || frame.playerPawn == null || frame.playerPawn.location == null) {
            return -1;
        }
        int n = Math.min(frame.enemies.length, MAX_ENEMY_SLOTS);
        int best = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            PlayerDto e = frame.enemies[i];
            if (e == null || e.health <= 0 || e.location == null) continue;
            double dx = e.location.x - frame.playerPawn.location.x;
            double dy = e.location.y - frame.playerPawn.location.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static boolean anyEnemyPresent(GameStateDto frame) {
        if (frame.enemies == null) return false;
        for (PlayerDto e : frame.enemies) {
            if (e != null && e.health > 0 && e.location != null) return true;
        }
        return false;
    }

    private static PlayerDto findByName(PlayerDto[] enemies, String name) {
        if (enemies == null || name == null) return null;
        for (PlayerDto e : enemies) {
            if (e != null && name.equals(e.name)) return e;
        }
        return null;
    }
}
