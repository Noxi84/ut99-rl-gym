package aiplay.shared.shooting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 2: per-bot store for the shooting model's target_index output (argmax
 * over enemy slot logits). Static ConcurrentHashMap keyed by sessionId because
 * the shooting executor is a shared component (one per JVM) but per-bot state
 * is needed — same pattern as AimTargetEnricher.
 *
 * <p>Written by {@code ShootingExecutorAiController} after each inference (via
 * {@link #publish(String, int)}); read by reward computers ({@code CombatEventReward},
 * {@code ViewAlignmentReward} via {@link aiplay.rl.rewards.core.LeadAimUtils}) so
 * that aim-score / lead-aim use the model's own target choice.</p>
 *
 * <p>Sentinel value {@link #ABSENT} ({@code -1}) means "no model choice yet"
 * — readers fall back to the engagement-aware rule-based target. Once the
 * shooting model produces target_logits the value moves to [0, 4].</p>
 */
public final class ShootingTargetIndexBus {

    public static final int ABSENT = -1;

    private static final ConcurrentHashMap<String, AtomicInteger> PER_SESSION = new ConcurrentHashMap<>();

    private ShootingTargetIndexBus() {}

    /** Write the latest argmax target_index for a bot. Called per-tick from
     *  ShootingExecutorAiController after inference. */
    public static void publish(String sessionId, int targetIndex) {
        if (sessionId == null) return;
        PER_SESSION.computeIfAbsent(sessionId, k -> new AtomicInteger(ABSENT)).set(targetIndex);
    }

    /** Read the latest argmax target_index for a bot, or {@link #ABSENT} if
     *  none has been published yet (single-output ONNX, or pre-Phase-2). */
    public static int latest(String sessionId) {
        if (sessionId == null) return ABSENT;
        AtomicInteger v = PER_SESSION.get(sessionId);
        return v != null ? v.get() : ABSENT;
    }

    /** Clear per-session state at bot shutdown to prevent memory leaks. */
    public static void unregisterSession(String sessionId) {
        if (sessionId == null) return;
        PER_SESSION.remove(sessionId);
    }
}
