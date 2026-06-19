package aiplay.scanners.executors.command;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.rl.MovementPrimitive;
import aiplay.runtime.port.CommandSink;
import aiplay.shared.movement.MovementIntent;
import aiplay.shared.movement.MovementIntentBus;
import aiplay.shared.movement.PolicyIntentBus;
import aiplay.shared.shooting.ShootIntent;
import aiplay.shared.shooting.ShootIntentBus;
import aiplay.shared.tactical.TacticalIntent;
import aiplay.shared.tactical.TacticalIntentBus;
import aiplay.shared.view.ViewTurnIntent;
import aiplay.shared.view.ViewTurnIntentBus;
import aiplay.shared.weapon.WeaponSelectIntent;
import aiplay.shared.weapon.WeaponSelectIntentBus;
import aiplay.tactical.MovementConstraintApplier;
import aiplay.tactical.MovementConstraintApplier.ConstrainedMovement;
import java.util.logging.Logger;

/**
 * Central command controller — the sole owner of command state and UDP command emission.
 * Yaw and pitch are driven directly by the VR model's continuous delta outputs,
 * accumulated by {@link YawPitchAccumulator}.
 *
 * Thread-safety contract:
 * - Policy executor threads may only publish intents to their respective buses (atomic writes).
 * - Only the CommandController thread may read intent buses, mutate command state, and call sendCommand().
 */
public class CommandController {

    private final PolicyIntentBus policyIntentBus;
    private final MovementIntentBus effectiveIntentBus;
    private final ViewTurnIntentBus vrTurnIntentBus;
    private final ShootIntentBus shootIntentBus; // nullable — only present when shoot model is active
    private final TacticalIntentBus tacticalIntentBus; // nullable — reads tactical spatial constraints
    private final WeaponSelectIntentBus weaponSelectBus; // nullable — weapon-planner lane output
    private final CommandSink sender;
    private final Logger vrMonitorLogger;

    private final MovementConstraintApplier constraintApplier =
        new MovementConstraintApplier(GlobalConfigRepository.shared().tactical().carrierLineMarginNorm());
    private final YawPitchAccumulator accumulator = new YawPitchAccumulator();

    // Movement dwell state
    private MovementIntent activeMovementIntent;
    private long activeMovementIntentSetAtMs;

    // Command dedupe state
    private int lastSentYaw = -1;
    private int lastSentPitch = 0;
    private boolean lastForward, lastBack, lastLeft, lastRight;
    private boolean lastJump, lastDuck, lastFire, lastAltFire;
    private int lastDodge = 0;

    // Warmup gate
    private boolean matchStarted = false;
    private double prevRemainingTime = -1.0;
    private double prevElapsedTime = -1.0;

    // Action cooldowns: last tick (ms) a rising edge was emitted.
    private long lastJumpEdgeMs = 0L;
    private long lastDuckEdgeMs = 0L;

    // Weapon select: edge + retry state. The select command is edge-triggered (sent only
    // when the desired weapon differs from the active one) and re-sent at most every
    // WEAPON_SELECT_RETRY_MS to survive UDP packet loss / an in-progress switch. UC is
    // idempotent. Fixed implementation guard (cf. ExecutorFpsDecorator.RESTART_BACKOFF_MS),
    // not a behavioral config knob.
    private static final long WEAPON_SELECT_RETRY_MS = 500L;
    private String lastSelectSentClass;
    private long lastSelectSentMs = 0L;

    // Diagnostics
    private long tickCount = 0;
    private long lastVrSummaryLogMs = 0L;

    public CommandController(PolicyIntentBus policyIntentBus,
                             MovementIntentBus effectiveIntentBus,
                             ViewTurnIntentBus vrTurnIntentBus,
                             ShootIntentBus shootIntentBus,
                             TacticalIntentBus tacticalIntentBus,
                             WeaponSelectIntentBus weaponSelectBus,
                             CommandSink sender,
                             Logger vrMonitorLogger) {
        this.policyIntentBus = policyIntentBus;
        this.effectiveIntentBus = effectiveIntentBus;
        this.vrTurnIntentBus = vrTurnIntentBus;
        this.shootIntentBus = shootIntentBus;
        this.tacticalIntentBus = tacticalIntentBus;
        this.weaponSelectBus = weaponSelectBus;
        this.sender = sender;
        this.vrMonitorLogger = vrMonitorLogger;
    }

    /**
     * Called at controller rate (~60Hz effective) by the command-controller executor thread.
     */
    public void tick(GameStateDto currentState) {
        if (currentState == null) return;

        if (!checkMatchStarted(currentState)) return;

        // Weapon activation runs independently of the movement/view dedupe below, so it
        // happens before the early-return path. Pure actuation: compares the planner's
        // chosen weapon with the active one and sends a select command on a mismatch.
        maybeSelectWeapon(currentState);

        MovementIntent movIntent = policyIntentBus.latest();
        ViewTurnIntent turnIntent = vrTurnIntentBus.latest();

        movIntent = applyMovementDwell(movIntent);

        boolean fire = movIntent.fire;
        boolean altFire = movIntent.altFire;
        if (shootIntentBus != null) {
            ShootIntent shootIntent = shootIntentBus.latest();
            if (shootIntent != null && shootIntent.timestampMs() > 0) {
                fire = shootIntent.fire();
                altFire = shootIntent.altFire();
            }
        }

        MovementPrimitive loco = movIntent.locomotion;
        boolean forward = loco.isForwardIntent();
        boolean back = loco.isBackIntent();
        boolean left = loco.isLeftIntent();
        boolean right = loco.isRightIntent();
        int dodgeDir = movIntent.dodgeDir;

        // Dodge cooldown gate: suppress dodge when engine is already in a dodge sequence.
        // The model doesn't know about cooldown — it may keep predicting dodge=1.
        if (dodgeDir > 0 && currentState.playerPawn != null
                && currentState.playerPawn.dodgeState != DodgeState.NONE) {
            dodgeDir = 0;
        }

        // Action mutual-exclusivity + cooldown gates.
        // Priority: dodge > jump > duck. Jump/duck are physically incompatible with a dodge,
        // duck is physically incompatible with a jump. The model may still emit them together;
        // the actuator enforces the engine's real constraints.
        long nowForGates = System.currentTimeMillis();
        boolean dodgeActive = dodgeDir > 0
                || (currentState.playerPawn != null
                    && currentState.playerPawn.dodgeState != null
                    && currentState.playerPawn.dodgeState != DodgeState.NONE);

        boolean jump = movIntent.jump;
        if (jump && dodgeActive) {
            jump = false;
        }
        int jumpCooldownMs = GlobalConfigRepository.shared().commandController().general().jumpCooldownMs();
        if (jump && jumpCooldownMs > 0 && (nowForGates - lastJumpEdgeMs) < jumpCooldownMs) {
            jump = false;
        }
        if (jump && !lastJump) {
            lastJumpEdgeMs = nowForGates;
        }

        boolean duck = movIntent.duck;
        if (duck && (dodgeActive || jump)) {
            duck = false;
        }
        int duckCooldownMs = GlobalConfigRepository.shared().commandController().general().duckCooldownMs();
        if (duck && duckCooldownMs > 0 && (nowForGates - lastDuckEdgeMs) < duckCooldownMs) {
            duck = false;
        }
        if (duck && !lastDuck) {
            lastDuckEdgeMs = nowForGates;
        }

        // --- Yaw/Pitch sync: own state to avoid server feedback jitter ---
        int serverYaw = (currentState.playerPawn != null && currentState.playerPawn.viewRotation != null)
                ? (currentState.playerPawn.viewRotation.x & 0xFFFF) : -1;

        if (!accumulator.isInitialized() && serverYaw >= 0) {
            accumulator.sync(serverYaw, YawPitchAccumulator.toSignedPitch(currentState.playerPawn.viewRotation.y));
        } else if (serverYaw >= 0) {
            int drift = YawPitchAccumulator.shortestDeltaUt(accumulator.smoothedYaw(), serverYaw);
            if (Math.abs(drift) > 8192) { // >45° drift = respawn
                accumulator.sync(serverYaw, YawPitchAccumulator.toSignedPitch(currentState.playerPawn.viewRotation.y));
            }
        }

        long now = System.currentTimeMillis();
        boolean freshTurnIntent = isFreshTurnIntent(turnIntent, now);
        float rawDelta = freshTurnIntent ? turnIntent.yawDelta : 0f;
        int yawStep = accumulator.applyContinuousYaw(rawDelta);
        int sentYaw = accumulator.smoothedYaw();
        int serverPitchSigned = (currentState.playerPawn != null && currentState.playerPawn.viewRotation != null)
                ? YawPitchAccumulator.toSignedPitch(currentState.playerPawn.viewRotation.y) : 0;
        maybeLogViewRotationControl(serverYaw, sentYaw, turnIntent, yawStep, now, freshTurnIntent,
                serverPitchSigned, accumulator.sentPitch());

        // --- Tactical spatial constraint ---
        TacticalIntent tactical = (tacticalIntentBus != null) ? tacticalIntentBus.latest() : null;
        ConstrainedMovement constrained = constraintApplier.apply(
                forward, back, left, right, dodgeDir, sentYaw, tactical, currentState);
        forward = constrained.forward();
        back = constrained.back();
        left = constrained.left();
        right = constrained.right();
        dodgeDir = constrained.dodgeDir();

        // --- Pitch ---
        if (freshTurnIntent) {
            accumulator.applyContinuousPitch(turnIntent.pitchDelta);
        } else if (currentState.playerPawn != null && currentState.playerPawn.viewRotation != null) {
            accumulator.syncPitchFromServer(currentState.playerPawn.viewRotation.y);
        }
        int sentPitch = accumulator.sentPitch();

        // Publish effective intent (with constrained locomotion + shoot-model-overridden fire flags)
        MovementPrimitive effectiveLoco = MovementPrimitive.fromLegacyKeyStates(forward, back, left, right);
        effectiveIntentBus.publish(new MovementIntent(
                effectiveLoco, jump, duck,
                fire, altFire, dodgeDir));

        // --- Command dedupe ---
        int dedupeThreshold = GlobalConfigRepository.shared().commandController().general().dedupeThresholdUt();
        int yawDelta = ((sentYaw - lastSentYaw + 32768) & 0xFFFF) - 32768;
        boolean yawSame = lastSentYaw >= 0 && Math.abs(yawDelta) < dedupeThreshold;
        boolean pitchSame = Math.abs(sentPitch - lastSentPitch) < dedupeThreshold;
        boolean moveSame = (forward == lastForward && back == lastBack && left == lastLeft && right == lastRight);
        boolean actionSame = (jump == lastJump && duck == lastDuck
                           && fire == lastFire && altFire == lastAltFire
                           && dodgeDir == lastDodge);

        if (yawSame && pitchSame && moveSame && actionSame) {
            tickCount++;
            return;
        }

        lastSentYaw = sentYaw;
        lastSentPitch = sentPitch;
        lastForward = forward; lastBack = back; lastLeft = left; lastRight = right;
        lastJump = jump; lastDuck = duck;
        lastFire = fire; lastAltFire = altFire;
        lastDodge = dodgeDir;

        // Movement yaw: world-space heading for MoveTo(). The movement model predicts a
        // CONTINUE view-relatieve richting; we maken er sentYaw + relativeUt van zodat de bot een
        // exacte heading volgt i.p.v. 45°-grof gequantiseerd (8-sector) — dat laatste veroorzaakt
        // laterale drift van smalle bruggen (CTF-Face vallen). Identieke frame-logica als de
        // legacy-route (sentYaw + relatieve hoek), enkel un-gequantiseerd. Bij een actieve tactical
        // clamp (zeldzame carrier-reentry-block) is de richting gewijzigd → val terug op de legacy
        // 8-sector moveYaw uit de geclampte keys. Ook fallback wanneer geen continue hoek meegeleverd.
        int moveYaw = (movIntent.moveYawRelativeUt != MovementIntent.NO_CONTINUOUS_YAW
                       && !constrained.wasClamped())
            ? ((sentYaw + movIntent.moveYawRelativeUt) & 0xFFFF)
            : computeMoveYaw(forward, back, left, right, sentYaw);

        sender.sendCommand(forward, back, left, right,
                jump, duck, fire, altFire,
                dodgeDir, sentYaw, sentPitch, moveYaw);

        tickCount++;
        if (tickCount <= 3 || tickCount % 5000 == 0) {
            int curYaw = (currentState.playerPawn != null && currentState.playerPawn.viewRotation != null)
                    ? (currentState.playerPawn.viewRotation.x & 0xFFFF) : -1;
            System.out.println("CMD_CTRL tick=" + tickCount
                    + " loco=" + loco.name()
                    + " yaw_step=" + yawStep
                    + " cmd_yaw=" + sentYaw + " cur_yaw=" + curYaw);
        }
    }

    /**
     * Activate the weapon chosen by the weapon-planner lane, only when it is not already
     * active (edge-triggered). The decision (which weapon, incl. next-best-with-ammo
     * fallback) lives in the planner; this is pure actuation — a compare against the
     * server-reported active weapon plus a re-send guard for UDP loss.
     */
    private void maybeSelectWeapon(GameStateDto currentState) {
        if (weaponSelectBus == null) return;
        WeaponSelectIntent intent = weaponSelectBus.latest();
        if (intent == null || intent.weaponClass() == null) return;

        String currentWeapon = (currentState.playerPawn != null)
                ? currentState.playerPawn.weaponClass : null;
        String desired = intent.weaponClass();

        if (desired.equals(currentWeapon)) {
            // Already holding it — clear so a later divergence sends immediately.
            lastSelectSentClass = null;
            return;
        }

        long now = System.currentTimeMillis();
        boolean newTarget = !desired.equals(lastSelectSentClass);
        boolean retryDue = (now - lastSelectSentMs) >= WEAPON_SELECT_RETRY_MS;
        if (newTarget || retryDue) {
            sender.selectWeapon(intent.classHash());
            lastSelectSentClass = desired;
            lastSelectSentMs = now;
        }
    }

    private MovementIntent applyMovementDwell(MovementIntent newIntent) {
        long now = System.currentTimeMillis();
        int minDwellMs = GlobalConfigRepository.shared().commandController().general().minMovementDwellMs();

        if (activeMovementIntent == null) {
            activeMovementIntent = newIntent;
            activeMovementIntentSetAtMs = now;
            return newIntent;
        }

        if (newIntent.locomotion != activeMovementIntent.locomotion) {
            long elapsed = now - activeMovementIntentSetAtMs;
            if (elapsed >= minDwellMs) {
                activeMovementIntent = newIntent;
                activeMovementIntentSetAtMs = now;
            }
        } else {
            activeMovementIntent = newIntent;
        }

        return activeMovementIntent;
    }

    private boolean checkMatchStarted(GameStateDto frame) {
        if (frame.mapInfo == null) return matchStarted;

        double elapsed = frame.mapInfo.elapsedTime;
        double remaining = frame.mapInfo.remainingTime;

        if (!matchStarted) {
            boolean remainingDecreased = prevRemainingTime >= 0 && remaining < prevRemainingTime - 0.01;
            boolean elapsedFallback = frame.mapInfo.timeLimit <= 0 && elapsed >= 1.0;
            if (remainingDecreased || elapsedFallback) {
                matchStarted = true;
            } else {
                prevRemainingTime = remaining;
                prevElapsedTime = elapsed;
                return false;
            }
        }

        if (elapsed < prevElapsedTime - 5.0 || (remaining > 0 && remaining > prevRemainingTime + 60.0)) {
            matchStarted = false;
            prevRemainingTime = remaining;
            prevElapsedTime = elapsed;
            return false;
        }

        prevRemainingTime = remaining;
        prevElapsedTime = elapsed;
        return true;
    }

    private static int computeMoveYaw(boolean forward, boolean back, boolean left, boolean right, int viewYaw) {
        // UE1 convention: positive yaw = counter-clockwise = LEFT.
        // UT units: 16384 = 90°, 32768 = 180°
        int offset = 0;
        if (forward && !back) {
            if (left && !right) {
                offset = 8192;    // forward-left = +45°
            } else if (right && !left) {
                offset = -8192;   // forward-right = -45°
            }
        } else if (back && !forward) {
            if (left && !right) {
                offset = 24576;   // back-left = +135°
            } else if (right && !left) {
                offset = -24576;  // back-right = -135°
            } else {
                offset = 32768;   // pure back = 180°
            }
        } else if (left && !right) {
            offset = 16384;       // pure strafe left = +90°
        } else if (right && !left) {
            offset = -16384;      // pure strafe right = -90°
        }
        return (viewYaw + offset) & 0xFFFF;
    }

    private boolean isFreshTurnIntent(ViewTurnIntent turnIntent, long nowMs) {
        if (turnIntent == null || turnIntent.timestampMs <= 0) {
            return false;
        }
        int maxAgeMs = GlobalConfigRepository.shared()
                .commandController()
                .general()
                .viewTurnIntentMaxAgeMs();
        return maxAgeMs <= 0 || (nowMs - turnIntent.timestampMs) <= maxAgeMs;
    }

    private void maybeLogViewRotationControl(int serverYaw,
                                             int sentYaw,
                                             ViewTurnIntent turnIntent,
                                             int yawStep,
                                             long nowMs,
                                             boolean freshTurnIntent,
                                             int serverPitchSigned,
                                             int sentPitchSigned) {
        if (vrMonitorLogger == null) return;
        if ((nowMs - lastVrSummaryLogMs) < 250L) return;

        double angularErrorDeg = Math.toDegrees(turnIntent.angularError);
        int cmdDeltaUt = (serverYaw >= 0) ? YawPitchAccumulator.shortestDeltaUt(serverYaw, sentYaw) : 0;
        int turnAgeMs = (turnIntent.timestampMs > 0) ? (int) Math.max(0L, nowMs - turnIntent.timestampMs) : -1;

        // pitchDelta/serverPitch/sentPitch: telemetrie voor de episodische omhoog-staar-diagnose
        // (06-06): onderscheidt policy-stuurt-omhoog (pitchDelta>0 aanhoudend) van accumulator-vast
        // (sentPitch op clamp terwijl delta ~0) van server-volgt-niet (sentPitch normaal, serverPitch hoog).
        vrMonitorLogger.info(String.format(
                "VR_CTRL yawStep=%d yawDelta=%+.3f angErrDeg=%.1f serverYaw=%d sentYaw=%d cmdDeltaUt=%d turnAgeMs=%d turnFresh=%s pitchDelta=%+.3f serverPitch=%d sentPitch=%d",
                yawStep, turnIntent.yawDelta, angularErrorDeg, serverYaw, sentYaw, cmdDeltaUt, turnAgeMs, freshTurnIntent,
                turnIntent.pitchDelta, serverPitchSigned, sentPitchSigned));

        if (freshTurnIntent && Math.abs(angularErrorDeg) > 20.0 && Math.abs(turnIntent.yawDelta) < 0.05f) {
            vrMonitorLogger.warning(String.format(
                    "VR_CTRL_IDLE_WITH_ERROR angErrDeg=%.1f yawDelta=%+.3f serverYaw=%d sentYaw=%d",
                    angularErrorDeg, turnIntent.yawDelta, serverYaw, sentYaw));
        }

        lastVrSummaryLogMs = nowMs;
    }
}
