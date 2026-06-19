package aiplay.scanners.executors.rlpawn;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.shooting.FireKind;
import aiplay.config.global.shooting.WeaponFireModeConfig;
import aiplay.config.global.shooting.WeaponFireProfile;
import aiplay.dto.GameStateDto;
import aiplay.shared.shooting.ShootIntent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateful fire/altFire processor voor de joint VR+shooting executor.
 *
 * <p>Vult de drie gaten die {@link RLPawnActionDecoder} bewust open laat
 * en die voor cold-start van een SAC-getrainde binary policy fataal zijn:
 *
 * <ol>
 *   <li><b>Stochastic Bernoulli sampling</b> op {@code sigmoid(logit)} in plaats
 *       van een pure {@code logit > 0} threshold. Zonder samples-met-fire-aan
 *       in de replay buffer kan de critic nooit leren dat fire-on (in een
 *       engagement) beter is dan fire-off, en blijft de actor's mean[fire]
 *       vastzitten in de under-firing BC-prior (sigmoid(BC mean[fire]) ≈ 0.1 →
 *       deterministic mean &lt; 0 → bot vuurt nooit in production).</li>
 *   <li><b>Per-mode state machine</b> (IDLE → FIRING → COOLDOWN) gebaseerd op
 *       {@link WeaponFireProfile#primary()} / {@link WeaponFireProfile#secondary()}
 *       fire_duration_ms + cooldown_ms. Flak primary heeft een 1455 ms cycle
 *       (100 ms fire + 1410 ms cooldown). Zonder state machine zou een
 *       single-tick fire-edge UC's NormalFire state stilletjes laten negeren
 *       (Botpack/UT_FlakCannon.uc Sleep(...) tijdens animatie).</li>
 *   <li><b>Binary action recording</b> in de replay buffer in plaats van
 *       continuous {@code pred.raw} mean. Match met BC labels (0/1 vanuit
 *       CSV) zodat SAC en BC dezelfde action-space leren.</li>
 * </ol>
 *
 * <p>AltFire-mask: voor wapens waarvan de secondary-fire geen damage genereert
 * (sniper=scope-zoom, redeemer=guided-missile FP-view, translocator=teleport)
 * wordt het altFire-logit geforceerd op {@link Float#NEGATIVE_INFINITY} vóór
 * sampling. Identiek aan {@link
 * aiplay.scanners.executors.shooting.ShootingActionDecoder#ALT_FIRE_MASKED_WEAPONS}.
 */
public final class RLPawnFireDecisionProcessor {

    private static final float TRUE_LOGIT = 20.0f;
    private static final float FALSE_LOGIT = -20.0f;

    /** Wapens waarvan altFire geen damage geeft — model-output wordt gemasked. */
    private static final java.util.Set<String> ALT_FIRE_MASKED_WEAPONS = java.util.Set.of(
        "Botpack.SniperRifle",      // alt = scope zoom (geen damage, geen rendering)
        "Botpack.WarheadLauncher",  // alt = guided missile (FP view, geen rendering)
        "Botpack.Translocator"      // alt = teleport (movement, niet shooting)
    );

    private enum State { IDLE, FIRING, COOLDOWN }

    private final boolean stochastic;
    private State primaryState = State.IDLE;
    private long primaryStateStartMs = 0;
    private State secondaryState = State.IDLE;
    private long secondaryStateStartMs = 0;

    private static final java.util.Set<String> EIGHTBALL_CLASSES = java.util.Set.of(
        "Botpack.UT_Eightball",
        "NeuralNetWebserver.RLEightball"
    );

    private static final long HOLD_COMMITMENT_MS = 3500;
    private long primaryHoldCommitEndMs = 0;
    private long secondaryHoldCommitEndMs = 0;

    public RLPawnFireDecisionProcessor(boolean stochastic) {
        this.stochastic = stochastic;
    }

    /**
     * Gesample'd-en-state-machine-gegate decoded fire decision.
     *
     * @param intent           UC-bound {@link ShootIntent} (na FIRING/COOLDOWN gate)
     * @param fireBinaryAction 0.0f or 1.0f — wat in de replay buffer komt voor de fire-dim
     * @param altFireBinaryAction 0.0f or 1.0f — idem voor altFire-dim
     * @param fireExecLogit    saturated logit (-20 / +20) — alleen voor diagnostics
     * @param altFireExecLogit idem voor altFire
     * @param logProb          policy log-prob van het gesample'de paar (Bernoulli of deterministic-NaN)
     */
    public record Decision(
        ShootIntent intent,
        float fireBinaryAction,
        float altFireBinaryAction,
        float fireExecLogit,
        float altFireExecLogit,
        float logProb,
        boolean rawSampledFire,
        boolean rawSampledAltFire
    ) {}

    public Decision process(float fireLogit, float altFireLogit,
                            GameStateDto currentFrame, long nowMs) {
        WeaponFireProfile profile = resolveProfile(currentFrame);
        String weaponClass = (currentFrame != null && currentFrame.playerPawn != null)
            ? currentFrame.playerPawn.weaponClass : null;
        boolean isEightball = weaponClass != null && EIGHTBALL_CLASSES.contains(weaponClass);

        if (weaponClass != null && ALT_FIRE_MASKED_WEAPONS.contains(weaponClass)) {
            altFireLogit = Float.NEGATIVE_INFINITY;
        }

        boolean rawFire;
        boolean rawAlt;
        if (!isFinite(fireLogit)) {
            rawFire = false;
        } else if (stochastic) {
            rawFire = ThreadLocalRandom.current().nextDouble() < sigmoid(fireLogit);
        } else {
            rawFire = fireLogit > 0.0f;
        }
        if (!isFinite(altFireLogit)) {
            rawAlt = false;
        } else if (stochastic) {
            rawAlt = ThreadLocalRandom.current().nextDouble() < sigmoid(altFireLogit);
        } else {
            rawAlt = altFireLogit > 0.0f;
        }

        // Hold commitment for HOLD-mode weapons in stochastic mode: once
        // fire/altFire is sampled true, sustain for HOLD_COMMITMENT_MS so
        // multi-load can be discovered via exploration (Eightball rockets/
        // grenades, pulse beam, minigun, etc.). Edge-mode weapons (sniper,
        // flak, instagib, shock, ...) are unaffected.
        if (stochastic && profile != null) {
            if (profile.primary() != null && profile.primary().kind() == FireKind.HOLD) {
                if (rawFire && primaryState == State.IDLE) {
                    primaryHoldCommitEndMs = nowMs + HOLD_COMMITMENT_MS;
                }
                if (!rawFire && nowMs < primaryHoldCommitEndMs) {
                    rawFire = true;
                }
            }
            if (profile.secondary() != null && profile.secondary().kind() == FireKind.HOLD) {
                if (rawAlt && secondaryState == State.IDLE) {
                    secondaryHoldCommitEndMs = nowMs + HOLD_COMMITMENT_MS;
                }
                if (!rawAlt && nowMs < secondaryHoldCommitEndMs) {
                    rawAlt = true;
                }
            }
        }

        // Eightball allows both fire+altFire simultaneously: primary loads
        // rockets while bAltFire sets bTightWad for guided/tight grouping.
        // All other weapons: highest logit wins.
        boolean policyWantsFire = rawFire;
        boolean policyWantsAlt = rawAlt;
        if (!isEightball && policyWantsFire && policyWantsAlt) {
            if (fireLogit >= altFireLogit) {
                policyWantsAlt = false;
            } else {
                policyWantsFire = false;
            }
        }

        float logProb = computeLogProb(fireLogit, altFireLogit, policyWantsFire, policyWantsAlt);

        boolean firePressed;
        boolean altFirePressed;
        boolean fireSuppressedByCooldown = false;

        if (profile == null) {
            firePressed = policyWantsFire;
            altFirePressed = policyWantsAlt;
        } else if (isEightball && policyWantsFire && policyWantsAlt) {
            // Eightball guided rockets: primary drives hold-mode state machine;
            // altFire is a pass-through flag that sets bTightWad in
            // RLEightball.NormalFire.AnimEnd — no secondary state machine
            // update and no cross-mode block.
            StateStep primaryStep = stepState(primaryState, primaryStateStartMs,
                profile.primary(), true, nowMs);
            primaryState = primaryStep.next;
            primaryStateStartMs = primaryStep.startMs;
            firePressed = primaryStep.fire;
            altFirePressed = true;
        } else {
            // Standard path: cross-mode block prevents fire during alt cooldown
            // and vice versa. Correct for all edge-mode weapons and for
            // Eightball when only one fire mode is active.
            boolean execWantsFire = policyWantsFire;
            boolean execWantsAlt = policyWantsAlt;
            if (primaryState != State.IDLE && execWantsAlt) {
                execWantsAlt = false;
                fireSuppressedByCooldown = true;
            }
            if (secondaryState != State.IDLE && execWantsFire) {
                execWantsFire = false;
                fireSuppressedByCooldown = true;
            }

            StateStep primaryStep = stepState(primaryState, primaryStateStartMs,
                profile.primary(), execWantsFire, nowMs);
            primaryState = primaryStep.next;
            primaryStateStartMs = primaryStep.startMs;
            firePressed = primaryStep.fire;
            fireSuppressedByCooldown |= primaryStep.suppressedByCooldown;

            if (profile.secondary() != null) {
                StateStep altStep = stepState(secondaryState, secondaryStateStartMs,
                    profile.secondary(), execWantsAlt, nowMs);
                secondaryState = altStep.next;
                secondaryStateStartMs = altStep.startMs;
                altFirePressed = altStep.fire;
                fireSuppressedByCooldown |= altStep.suppressedByCooldown;
            } else {
                altFirePressed = false;
            }
        }

        ShootIntent intent = new ShootIntent(firePressed, altFirePressed, nowMs,
            fireSuppressedByCooldown);

        float fireBinary = policyWantsFire ? 1.0f : 0.0f;
        float altFireBinary = policyWantsAlt ? 1.0f : 0.0f;
        float fireExecLogit = policyWantsFire ? TRUE_LOGIT : FALSE_LOGIT;
        float altFireExecLogit = policyWantsAlt ? TRUE_LOGIT : FALSE_LOGIT;

        return new Decision(intent, fireBinary, altFireBinary,
            fireExecLogit, altFireExecLogit, logProb, rawFire, rawAlt);
    }

    /** Test/diagnostic hook. */
    State primaryStateForTest() { return primaryState; }
    State secondaryStateForTest() { return secondaryState; }

    private static StateStep stepState(State state, long stateStartMs,
                                       WeaponFireModeConfig cfg,
                                       boolean modelWants, long nowMs) {
        if (cfg.kind() == FireKind.HOLD) {
            return stepHold(state, stateStartMs, modelWants, nowMs);
        }
        return stepEdge(state, stateStartMs, cfg, modelWants, nowMs);
    }

    private static StateStep stepEdge(State state, long stateStartMs,
                                      WeaponFireModeConfig cfg,
                                      boolean modelWants, long nowMs) {
        boolean fire = false;
        boolean suppressed = false;
        State next = state;
        long nextStart = stateStartMs;

        switch (state) {
            case IDLE -> {
                if (modelWants) {
                    next = State.FIRING;
                    nextStart = nowMs;
                    fire = true;
                }
            }
            case FIRING -> {
                if (nowMs - stateStartMs < cfg.fireDurationMs()) {
                    fire = true;
                } else {
                    next = State.COOLDOWN;
                    nextStart = nowMs;
                    suppressed = modelWants;
                }
            }
            case COOLDOWN -> {
                if (nowMs - stateStartMs >= cfg.cooldownMs()) {
                    next = State.IDLE;
                } else {
                    suppressed = modelWants;
                }
            }
        }
        return new StateStep(next, nextStart, fire, suppressed);
    }

    /**
     * Pass-through semantiek: bit = modelWants direct. Geen COOLDOWN-onderdrukking.
     * State enum wordt hergebruikt zodat de cross-mode mutex (FIRING != IDLE blokkeert
     * andere mode) blijft werken — een HOLD-mode "FIRING" duurt zolang modelWants=true.
     */
    private static StateStep stepHold(State state, long stateStartMs,
                                      boolean modelWants, long nowMs) {
        State next;
        long nextStart;
        if (modelWants) {
            next = State.FIRING;
            nextStart = (state == State.FIRING) ? stateStartMs : nowMs;
        } else {
            next = State.IDLE;
            nextStart = nowMs;
        }
        return new StateStep(next, nextStart, modelWants, false);
    }

    private static WeaponFireProfile resolveProfile(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null) return null;
        return GlobalConfigRepository.shared().shooting().profileFor(frame.playerPawn.weaponClass);
    }

    private float computeLogProb(float fireLogit, float altFireLogit,
                                 boolean fire, boolean altFire) {
        if (!stochastic) {
            return Float.NaN;
        }
        // Pre-mutex Bernoulli log-probs; we tellen ze op want fire en altFire
        // werden onafhankelijk gesample'd voordat de mutex de winnaar koos.
        // SAC herrekent log_prob op de huidige actor; deze waarde is alleen
        // bookkeeping voor de NPZ.
        double lpFire = fire ? logSigmoid(fireLogit) : logSigmoid(-fireLogit);
        double lpAlt = altFire ? logSigmoid(altFireLogit) : logSigmoid(-altFireLogit);
        double total = lpFire + lpAlt;
        return Double.isFinite(total) ? (float) total : Float.NaN;
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private static double logSigmoid(float x) {
        if (x >= 0.0f) {
            return -Math.log1p(Math.exp(-x));
        }
        return x - Math.log1p(Math.exp(x));
    }

    private static boolean isFinite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }

    private record StateStep(State next, long startMs, boolean fire,
                             boolean suppressedByCooldown) {}
}
