package aiplay.scanners.executors.rlpawn.movement;

import aiplay.rl.MovementPrimitive;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decodes raw model logits into a {@link MovementOutput}: tanh-squashed
 * (sin, cos) world direction → 8-sector {@link MovementPrimitive}, plus a
 * binary dodge signal and optional aux actions (jump/duck/fire/altFire).
 *
 * <p>Thread-confined to the executor thread that owns this decoder.
 *
 * <p>Wanneer {@code stochastic = true} worden de binaire dims (dodge/jump/duck)
 * Bernoulli-gesampled op {@code sigmoid(logit)} ipv een pure {@code logit &gt; 0}
 * threshold. Dat geeft variation in de replay buffer zodat SAC's critic
 * counterfactual data ziet (a=0 EN a=1 voor dezelfde state) — zonder
 * sampling is het buffer een 100% deterministic policy en kan de critic
 * dodge=1 vs dodge=0 niet leren. fire/altFire blijven uit deze decoder
 * (overschreven door {@code RLPawnFireDecisionProcessor}, dat al zijn
 * eigen Bernoulli sampling doet).
 */
public final class MovementActionDecoder {

    /** Previous continuous sector for hysteresis (avoids flipping at sector boundaries). */
    private MovementPrimitive prevContinuousSector = null;

    /** Previous idle decision for sigmoid hysteresis: enter idle at enterThreshold,
     *  remain idle until sigmoid drops below exitThreshold. Prevents flip-flopping
     *  around the threshold. */
    private boolean prevIdle = false;

    /** When true, dodge/jump/duck are Bernoulli-sampled op sigmoid(logit) ipv
     *  threshold-aan-0. Geeft counterfactual variation in de replay buffer. */
    private final boolean stochastic;

    /** Pre-tanh Gaussiaanse exploratie-std op moveDir_sin/cos tijdens collectie (0 = uit). */
    private final double headingExplorationStd;
    /** Edge-aware exploratie: drempel op edgeDropAmount [0,1] waarboven de heading-ruis begint te dempen,
     *  en de schaal waarnaar hij dempt bij volledige void (edge_scale=1.0 = gating uit). */
    private final double edgeDropThreshold;
    private final double edgeScale;

    public MovementActionDecoder() {
        this(false, 0.0, 0.6, 1.0);
    }

    public MovementActionDecoder(boolean stochastic) {
        this(stochastic, 0.0, 0.6, 1.0);
    }

    public MovementActionDecoder(boolean stochastic, double headingExplorationStd) {
        this(stochastic, headingExplorationStd, 0.6, 1.0);
    }

    public MovementActionDecoder(boolean stochastic, double headingExplorationStd,
                                 double edgeDropThreshold, double edgeScale) {
        this.stochastic = stochastic;
        this.headingExplorationStd = headingExplorationStd;
        this.edgeDropThreshold = edgeDropThreshold;
        this.edgeScale = edgeScale;
    }

    /** 8-sector boundaries: each sector spans 45° centered on the primitive's canonical direction. */
    private static final int SECTOR_HALF_WIDTH_UT = 4096; // 22.5° in UT units
    /** Hysteresis: stay in current sector until angle exceeds sector center + this margin. */
    private static final int HYSTERESIS_UT = 1500; // ~8.2° extra dead zone

    private static final int[] SECTOR_CENTERS_UT = {
        0,      // FORWARD
        -8192,  // FORWARD_RIGHT (-45°)
        -16384, // STRAFE_RIGHT  (-90°)
        -24576, // BACK_RIGHT    (-135°)
        32768,  // BACK          (±180°)
        24576,  // BACK_LEFT     (+135°)
        16384,  // STRAFE_LEFT   (+90°)
        8192,   // FORWARD_LEFT  (+45°)
    };

    private static final MovementPrimitive[] SECTOR_PRIMITIVES = {
        MovementPrimitive.FORWARD,
        MovementPrimitive.FORWARD_RIGHT,
        MovementPrimitive.STRAFE_RIGHT,
        MovementPrimitive.BACK_RIGHT,
        MovementPrimitive.BACK,
        MovementPrimitive.BACK_LEFT,
        MovementPrimitive.STRAFE_LEFT,
        MovementPrimitive.FORWARD_LEFT,
    };

    public MovementOutput decode(float[] logits, MovementActionSchema spec, int viewYawUt) {
        return decode(logits, spec, viewYawUt, 0.0);
    }

    /**
     * @param edgeDropAmount max drop-amount [0,1] over de 8 floor-richtingen (-tanh(floorDelta/64)):
     *     0 = vlakke grond → volledige heading-exploratie; →1 = rand/void nabij → ruis gedempt naar
     *     edgeScale zodat de edge-bewuste policy-mean de bot niet van de smalle brug laat duwen.
     */
    public MovementOutput decode(float[] logits, MovementActionSchema spec, int viewYawUt,
                                 double edgeDropAmount) {
        float rawSin = logits[spec.sinIndex()];
        float rawCos = logits[spec.cosIndex()];

        // Looprichting-exploratie (STRUCTUREEL, 2026-06-13): tijdens experience-collectie (stochastic)
        // Gaussiaanse ruis op de pre-tanh sin/cos zodat de policy ANDERE bewegingssectoren probeert dan
        // z'n deterministische keuze. Vóór dit werden alleen de binaire dims (vuren/dodge/jump/duck)
        // gesampled; de continue looprichting was de deterministische mean → de buffer bevatte GEEN
        // sector-variatie → de SAC-critic kon Q(s, andere-sector) niet leren → de movement-policy zat
        // vast en kon de niet-vallende flag-route niet ontdekken. De hysteresis (runtime-smoothing) wordt
        // tijdens exploratie overgeslagen, anders dempt die juist de sector-wissels die we WILLEN. De
        // gesample'de pre-tanh waarden worden hieronder gerecord (actions[sin/cosIndex]) zodat de buffer
        // de daadwerkelijk uitgevoerde, geexploreerde actie bevat — exact wat SAC off-policy nodig heeft.
        boolean exploreHeading = stochastic && headingExplorationStd > 0.0;
        if (exploreHeading) {
            // Edge-aware demping: severity 0 onder de drempel → 1 bij volledige void; de effectieve std
            // schaalt van headingExplorationStd (vlak) naar headingExplorationStd*edgeScale (rand). Enkel
            // de heading-ruis-magnitude wordt geschaald; idle-sampling + hysteresis-skip blijven ongemoeid.
            double denom = Math.max(1e-6, 1.0 - edgeDropThreshold);
            double severity = Math.max(0.0, Math.min(1.0, (edgeDropAmount - edgeDropThreshold) / denom));
            double effectiveStd = headingExplorationStd * (1.0 - severity * (1.0 - edgeScale));
            java.util.concurrent.ThreadLocalRandom rng = ThreadLocalRandom.current();
            rawSin += (float) (rng.nextGaussian() * effectiveStd);
            rawCos += (float) (rng.nextGaussian() * effectiveStd);
        }

        float sin = tanh(rawSin);
        float cos = tanh(rawCos);

        double worldAngleRad = Math.atan2(sin, cos);
        if (worldAngleRad < 0) worldAngleRad += 2.0 * Math.PI;
        int worldAngleUt = (int) Math.round(worldAngleRad / (2.0 * Math.PI) * 65536.0) & 0xFFFF;

        // Relative to view (signed shortest arc)
        int relativeUt = ((worldAngleUt - viewYawUt + 32768) & 0xFFFF) - 32768;

        MovementPrimitive movingSector = exploreHeading
            ? relativeUtToMovementPrimitive(relativeUt)
            : relativeUtToMovementPrimitiveWithHysteresis(relativeUt);

        // Actions for experience recording: raw pre-tanh logits
        float[] actions = new float[spec.targetOrder().size()];
        actions[spec.sinIndex()] = rawSin;
        actions[spec.cosIndex()] = rawCos;

        // Idle decision (with sigmoid hysteresis). When the model has no idle output
        // (legacy 5-output spec), idleIndex < 0 → never idle.
        //
        // Tijdens exploratie wordt óók de move-vs-idle keuze gesampled (Bernoulli, geen hysteresis):
        // de policy convergeerde naar idle (stall-optimum, want bewegen gaf −objProgress). Zonder
        // idle-exploratie zou de bot gewoon blijven idlen om de geruiste beweging te mijden → de
        // looprichting-exploratie wordt dan nooit toegepast en de buffer blijft sector-variatie-loos.
        // Met Bernoulli-idle beweegt de bot soms tóch → de sin/cos-ruis genereert directionele
        // variatie → de critic leert welke richting wél veilig oprukt i.p.v. valt/terugtrekt.
        boolean idle;
        if (exploreHeading && spec.idleIndex() >= 0) {
            float pIdle = sigmoid(logits[spec.idleIndex()]);
            idle = ThreadLocalRandom.current().nextFloat() < pIdle;
        } else {
            idle = decideIdleWithHysteresis(logits, spec);
        }
        if (spec.idleIndex() >= 0) {
            actions[spec.idleIndex()] = idle ? 1.0f : 0.0f;
        }

        // Dodge: binary output via Bernoulli (stochastic) or threshold (deterministic).
        // Bernoulli geeft counterfactual variation in de replay buffer zodat SAC's
        // critic Q(s, dodge=0) vs Q(s, dodge=1) kan leren.
        decideBinaryAction(logits, actions, spec.dodgeIndex());
        // When idle, suppress dodge (dodge implies a movement direction).
        int dodgeDir = (!idle && isActionOn(actions, spec.dodgeIndex()))
            ? movementPrimitiveToDodgeDir(movingSector)
            : 0;
        if (idle && spec.dodgeIndex() >= 0) {
            actions[spec.dodgeIndex()] = 0.0f;
        }

        decideBinaryAction(logits, actions, spec.jumpIndex());
        decideBinaryAction(logits, actions, spec.duckIndex());
        // fire/altFire blijven threshold hier — worden later door
        // RLPawnFireDecisionProcessor overschreven met zijn eigen
        // (state-machine + Bernoulli) decision.
        thresholdBernoulliAction(logits, actions, spec.fireIndex());
        thresholdBernoulliAction(logits, actions, spec.altFireIndex());

        MovementPrimitive loco = idle ? MovementPrimitive.IDLE : movingSector;

        return new MovementOutput(
            actions,
            logits,
            loco,
            isActionOn(actions, spec.jumpIndex()),
            isActionOn(actions, spec.duckIndex()),
            isActionOn(actions, spec.fireIndex()),
            isActionOn(actions, spec.altFireIndex()),
            sin,
            cos,
            dodgeDir,
            idle,
            relativeUt
        );
    }

    private boolean decideIdleWithHysteresis(float[] logits, MovementActionSchema spec) {
        int idx = spec.idleIndex();
        if (idx < 0) {
            prevIdle = false;
            return false;
        }
        float p = sigmoid(logits[idx]);
        double enter = spec.idleEnterThreshold();
        double exit = spec.idleExitThreshold();
        if (prevIdle) {
            // Stay idle until p drops below the (lower) exit threshold.
            prevIdle = p >= exit;
        } else {
            // Enter idle when p crosses the (higher) enter threshold.
            prevIdle = p >= enter;
        }
        return prevIdle;
    }

    /**
     * Derive UT99 dodge direction (1-4) from the current movement sector.
     * Maps 8 sectors to 4 cardinal dodge directions.
     */
    private static int movementPrimitiveToDodgeDir(MovementPrimitive loco) {
        return switch (loco) {
            case FORWARD, FORWARD_LEFT, FORWARD_RIGHT -> 1; // dodge forward
            case BACK, BACK_LEFT, BACK_RIGHT -> 2;          // dodge back
            case STRAFE_LEFT -> 3;                           // dodge left
            case STRAFE_RIGHT -> 4;                          // dodge right
            default -> 1;                                    // fallback: forward
        };
    }

    /**
     * Maps a relative angle (in UT units, signed) to the nearest 8-sector MovementPrimitive.
     * Positive = left (CCW), negative = right (CW).
     */
    static MovementPrimitive relativeUtToMovementPrimitive(int relativeUt) {
        relativeUt = ((relativeUt + 32768) & 0xFFFF) - 32768;

        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < SECTOR_CENTERS_UT.length; i++) {
            int center = SECTOR_CENTERS_UT[i];
            int dist = Math.abs(((relativeUt - center + 32768) & 0xFFFF) - 32768);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return SECTOR_PRIMITIVES[bestIdx];
    }

    /**
     * Like {@link #relativeUtToMovementPrimitive} but with hysteresis: stays in the previous
     * sector unless the angle clearly belongs to a different one.
     */
    private MovementPrimitive relativeUtToMovementPrimitiveWithHysteresis(int relativeUt) {
        MovementPrimitive nearest = relativeUtToMovementPrimitive(relativeUt);

        if (prevContinuousSector == null || nearest == prevContinuousSector) {
            prevContinuousSector = nearest;
            return nearest;
        }

        relativeUt = ((relativeUt + 32768) & 0xFFFF) - 32768;
        int prevIdx = indexOfPrimitive(prevContinuousSector);
        int prevCenter = SECTOR_CENTERS_UT[prevIdx];
        int distToPrev = Math.abs(((relativeUt - prevCenter + 32768) & 0xFFFF) - 32768);

        if (distToPrev < SECTOR_HALF_WIDTH_UT + HYSTERESIS_UT) {
            return prevContinuousSector; // stay in previous sector
        }

        prevContinuousSector = nearest;
        return nearest;
    }

    private static int indexOfPrimitive(MovementPrimitive p) {
        for (int i = 0; i < SECTOR_PRIMITIVES.length; i++) {
            if (SECTOR_PRIMITIVES[i] == p) return i;
        }
        return 0;
    }

    private static float tanh(float x) {
        return (float) Math.tanh(x);
    }

    private static float sigmoid(float logit) {
        return (float) (1.0 / (1.0 + Math.exp(-logit)));
    }

    private static void thresholdBernoulliAction(float[] logits, float[] actions, int index) {
        if (index < 0 || index >= logits.length) return;
        actions[index] = sigmoid(logits[index]) > 0.5f ? 1.0f : 0.0f;
    }

    /** Bernoulli sample op sigmoid(logit) als stochastic, anders threshold. */
    private void decideBinaryAction(float[] logits, float[] actions, int index) {
        if (index < 0 || index >= logits.length) return;
        if (stochastic) {
            float p = sigmoid(logits[index]);
            actions[index] = ThreadLocalRandom.current().nextFloat() < p ? 1.0f : 0.0f;
        } else {
            actions[index] = sigmoid(logits[index]) > 0.5f ? 1.0f : 0.0f;
        }
    }

    private static boolean isActionOn(float[] actions, int index) {
        return index >= 0 && index < actions.length && actions[index] > 0.5f;
    }
}
