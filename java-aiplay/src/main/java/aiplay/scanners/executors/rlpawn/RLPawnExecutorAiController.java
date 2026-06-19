package aiplay.scanners.executors.rlpawn;

import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.GENERIC_PREDICTOR;
import static aiplay.behaviortreebuilder.blackboard.BlackboardKeys.SESSION_ID;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.dto.GameStateDto;
import aiplay.dto.GridFrame;
import aiplay.rl.PerModelExperienceRecorder;
import aiplay.rl.RLConfig;
import aiplay.runtime.port.InferencePort;
import aiplay.scanners.executors.rlpawn.movement.MovementActionDecoder;
import aiplay.scanners.executors.rlpawn.movement.MovementIntentMapper;
import aiplay.scanners.executors.rlpawn.movement.MovementOutput;
import aiplay.scanners.feature.CanonicalPerspectiveNormalizer;
import aiplay.scanners.executors.IPlayExecutor;
import aiplay.scanners.executors.PlayContext;
import aiplay.scanners.executors.PlayExecutorAiController;
import aiplay.scanners.executors.common.policy.SequenceWindowBuffer;
import aiplay.shared.movement.MovementIntent;
import aiplay.shared.movement.PolicyIntentBus;
import aiplay.shared.shooting.ShootIntent;
import aiplay.shared.shooting.ShootIntentBus;
import aiplay.shared.shooting.ShootingIntentStateBus;
import aiplay.shared.shooting.ShootingTargetIndexBus;
import aiplay.shared.view.EnemySpawnTargeting;
import aiplay.shared.view.EnemySpawnYawFallback;
import aiplay.shared.view.ViewTurnIntent;
import aiplay.shared.view.ViewTurnIntentBus;
import behaviortree.BehaviorTreeContext;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full-joint movement + VR + shooting controller — de enige low-level policy
 * controller in productie.
 *
 * <p>Eén forward pass produceert {@code actions[1,10]} (movement, yaw, pitch,
 * fire, altFire) + {@code target_logits[1,5]}.</p>
 *
 * <p>Outputs worden gesplitst en gepubliceerd naar de bestaande buses zodat de
 * CommandController en movement carrier-shadow logic ongewijzigd kunnen
 * blijven:</p>
 * <ul>
 *   <li>{@code [0:6]} → movement decoder → {@link MovementIntent} → {@link PolicyIntentBus}</li>
 *   <li>{@code [6:8]} → {@link RLPawnActionDecoder} → {@link ViewTurnIntent} → {@link ViewTurnIntentBus}</li>
 *   <li>{@code [8:10]} → {@link RLPawnActionDecoder} (mutex) → {@link ShootIntent} → {@link ShootIntentBus} + {@link ShootingIntentStateBus}</li>
 *   <li>target_logits → argmax + commitment-lock → {@link ShootingTargetIndexBus}</li>
 * </ul>
 *
 * <p>Hergebruik: heading-error berekening + view NaN-guard zijn intern in
 * {@link RLPawnActionDecoder} gerepliceerd om
 * verstrengeling met de decoupled VR controller te vermijden — deze controller
 * is bewust een minimal-state actuator.</p>
 */
public final class RLPawnExecutorAiController implements PlayExecutorAiController {

    private static final Logger LOG = Logger.getLogger(RLPawnExecutorAiController.class.getName());

    private InferencePort predictor;
    private RLPawnModelSpec spec;
    private SequenceWindowBuffer windowBuffer;
    private RLPawnCommitmentTracker commitmentTracker;
    private RLPawnFireDecisionProcessor fireProcessor;
    private MovementActionDecoder movementDecoder;
    private boolean fireSamplerStochastic;
    private int diagTickCount = 0;
    private static final int DIAG_MAX_TICKS = 5;
    private static final int ACTION_DIAG_WINDOW_TICKS = 1000;
    private int actionDiagSamples = 0;
    private int actionDiagRawFire = 0;
    private int actionDiagRawAltFire = 0;
    private int actionDiagRawBoth = 0;
    private int actionDiagFinalFire = 0;
    private int actionDiagFinalAltFire = 0;
    private double actionDiagFireLogitSum = 0.0;
    private double actionDiagAltFireLogitSum = 0.0;
    private long lastSpawnFallbackLogMs = 0L;
    private final EnemySpawnTargeting.TargetState spawnYawTargetState =
        new EnemySpawnTargeting.TargetState();

    @Override
    public boolean isEnabled() {
        return spec != null;
    }

    @Override
    public void init(PlayContext ctx) {
        if (this.predictor != null) return;
        this.predictor = ctx.predictor;
        this.spec = RLPawnModelSpec.loadOptional().orElse(null);
        if (spec != null) {
            this.windowBuffer = new SequenceWindowBuffer(spec.sequenceLength(), 1024);
            this.commitmentTracker = new RLPawnCommitmentTracker(spec.targetCommitmentLockTicks());
            RLConfig jointCfg = new RLConfig(spec.modelKey(),
                aiplay.runtime.context.PlayerIdentityContext.effectiveRole());
            this.fireSamplerStochastic = !jointCfg.isDeterministicInference();
            this.movementDecoder = new MovementActionDecoder(
                this.fireSamplerStochastic, jointCfg.getMovementExplorationStd(),
                jointCfg.getMovementExplorationEdgeDropThreshold(),
                jointCfg.getMovementExplorationEdgeScale());
            this.fireProcessor = new RLPawnFireDecisionProcessor(this.fireSamplerStochastic);
            LOG.info("RLPawnExecutorAiController init ok: modelKey=" + spec.modelKey()
                + " seqLen=" + spec.sequenceLength()
                + " predictionFps=" + spec.predictionFps()
                + " commitmentLockTicks=" + spec.targetCommitmentLockTicks()
                + " movementBinaryStochastic=" + this.fireSamplerStochastic
                + " fireStochastic=" + this.fireSamplerStochastic);
        }
    }

    @Override
    public void execute(BehaviorTreeContext context, IPlayExecutor executor) {
        final String sid = context.getBlackboard().get(SESSION_ID);
        predictor = context.getBlackboard().get(GENERIC_PREDICTOR);
        final List<GridFrame> gameStates = context.getBlackboard().get(BlackboardKeys.VR_SHOOT_GAMESTATES);
        execute(context, sid, executor, gameStates, null);
    }

    @Override
    public void execute(BehaviorTreeContext context, String sessionId, IPlayExecutor executor,
                        List<GridFrame> frames, Object extraArg) {
        boolean diag = diagTickCount < DIAG_MAX_TICKS;
        if (diag) {
            diagTickCount++;
            LOG.info("DIAG_VRS_EXEC#" + diagTickCount + " sid=" + sessionId
                + " spec=" + (spec == null ? "null" : "set")
                + " frames=" + (frames == null ? "null" : frames.size())
                + " predictor=" + (predictor == null ? "null" : predictor.getClass().getSimpleName()));
        }
        try {
            if (spec == null || frames == null || frames.isEmpty()) {
                if (diag) LOG.info("DIAG_VRS_RET_EARLY1 spec/frames null/empty");
                return;
            }

            windowBuffer.append(frames);

            int windowLen = spec.sequenceLength();
            List<GridFrame> window = windowBuffer.buildAlignedWindow(windowLen, spec.csvFps());

            GameStateDto currentFrame;
            if (window.size() >= windowLen) {
                currentFrame = window.get(window.size() - 1).state();
            } else {
                GridFrame latest = windowBuffer.latestFrame();
                if (latest == null) {
                    if (diag) LOG.info("DIAG_VRS_RET_EARLY2 windowBuffer.latestFrame=null window.size=" + window.size() + " need=" + windowLen);
                    return;
                }
                currentFrame = latest.state();
            }

            if (RLPawnSpectatorFilter.shouldSkip(currentFrame)) {
                if (diag) LOG.info("DIAG_VRS_RET_SPECTATOR currentFrame skipped by spectator filter");
                return;
            }

            // Positie-trace voor het self-bootstrapping geodesic field (rate-limited, fail-safe).
            aiplay.rl.trace.PositionTraceLogger.shared().log(sessionId, currentFrame);

            float[][][] input = spec.inputBuilder().build(sessionId, states(window), currentFrame);
            if (diag) {
                LOG.info("DIAG_VRS_INPUT_READY window.size=" + window.size()
                    + " input.shape=[" + input.length + "][" + (input.length > 0 ? input[0].length : 0)
                    + "][" + (input.length > 0 && input[0].length > 0 ? input[0][0].length : 0) + "]"
                    + " calling predictor.predictRaw(modelKey=" + spec.modelKey() + ")");
            }
            String predictorKey = aiplay.runtime.context.PlayerIdentityContext.predictorKey(spec.modelKey());
            InferencePort.RawPrediction pred = predictor.predictRaw(sessionId, predictorKey, input);
            if (pred == null) {
                if (diag) LOG.info("DIAG_VRS_RET_PRED_NULL predictor returned null");
                return;
            }
            if (diag) {
                LOG.info("DIAG_VRS_PRED_OK pred.raw.length=" + (pred.action() == null ? -1 : pred.action().length)
                    + " pred.targetLogits.length=" + (pred.targetLogits() == null ? -1 : pred.targetLogits().length));
            }

            // Strict format-validatie: joint ONNX moet 2 outputs hebben
            // (actions[10] + target_logits[5]). Single-output (legacy VR/shooting)
            // ONNX zou hier crashen — exact wat we willen om mismatches direct
            // te ontdekken in plaats van stilletjes door te draaien.
            float[] actions = pred.action();
            float[] targetLogits = pred.targetLogits();

            long nowMs = System.currentTimeMillis();
            EnemySpawnYawFallback.Result spawnYawFallback = resolveEnemySpawnYawFallback(currentFrame);
            if (spawnYawFallback.active()
                    && actions != null
                    && actions.length > RLPawnActionDecoder.IDX_YAW) {
                actions = Arrays.copyOf(actions, actions.length);
                actions[RLPawnActionDecoder.IDX_YAW] = spawnYawFallback.yawRaw();
                logEnemySpawnYawFallback(sessionId, spawnYawFallback, nowMs);
            }
            float angularError = spawnYawFallback.active()
                ? spawnYawFallback.angularError()
                : computeAngularError(input);
            RLPawnActionDecoder.Decoded decoded =
                RLPawnActionDecoder.decode(actions, targetLogits, angularError, nowMs);

            int viewYawUt = (currentFrame.playerPawn != null && currentFrame.playerPawn.viewRotation != null)
                ? (currentFrame.playerPawn.viewRotation.x & 0xFFFF) : 0;
            // Movement targets are recorded in canonical blue-team coordinates.
            // Red-team runtime adds 180 degrees, matching the standalone
            // MovementExecutorAiController path.
            if (currentFrame.playerPawn != null
                    && CanonicalPerspectiveNormalizer.needsNormalization(currentFrame.playerPawn.team)) {
                viewYawUt = (viewYawUt + 32768) & 0xFFFF;
            }
            // Edge-aware exploratie: max drop-amount over de 8 floor-richtingen (-tanh(floorDelta/64),
            // doc CollisionsDto regel 82: floorDelta<0 = drop). Frame-agnostisch (min over richtingen) →
            // geen perspectief-normalisatie nodig. De decoder dempt de heading-ruis bij een drop zodat de
            // bot niet van de smalle brug wordt geduwd (vallen = dominante doodsoorzaak op CTF-Face).
            double edgeDropAmount = 0.0;
            if (currentFrame.playerPawn != null && currentFrame.playerPawn.collisions != null) {
                aiplay.dto.CollisionsDto col = currentFrame.playerPawn.collisions;
                int[] floorDeltas = {
                    col.fwdFloorDelta, col.fwdRightFloorDelta, col.rightFloorDelta, col.backRightFloorDelta,
                    col.backFloorDelta, col.backLeftFloorDelta, col.leftFloorDelta, col.fwdLeftFloorDelta
                };
                for (int delta : floorDeltas) {
                    double drop = -Math.tanh(delta / 64.0);
                    if (drop > edgeDropAmount) edgeDropAmount = drop;
                }
            }
            MovementOutput movementOutput = movementDecoder.decode(actions, spec, viewYawUt, edgeDropAmount);
            if (context.getBlackboard().has(BlackboardKeys.POLICY_INTENT_BUS)) {
                PolicyIntentBus policyBus = context.getBlackboard().get(BlackboardKeys.POLICY_INTENT_BUS);
                MovementIntent movementIntent = MovementIntentMapper.map(movementOutput);
                policyBus.publish(movementIntent);
            }

            int committedTargetIndex = commitmentTracker.update(decoded.rawTargetIndex());
            ShootingTargetIndexBus.publish(sessionId, committedTargetIndex);

            if (decoded.viewTurnIntent() != null
                    && context.getBlackboard().has(BlackboardKeys.VIEWTURN_INTENT_BUS)) {
                ViewTurnIntentBus busIntent = context.getBlackboard().get(BlackboardKeys.VIEWTURN_INTENT_BUS);
                busIntent.publish(decoded.viewTurnIntent());
            }

            // State-machine + Bernoulli-sampled fire decision. Vervangt de
            // pure-threshold ShootIntent uit de decoder met:
            //   - stochastic Bernoulli(sigmoid(logit)) sampling (geeft fire-on
            //     samples in de replay buffer zodat SAC's critic kan leren),
            //   - FIRING/COOLDOWN state machines per primary/secondary
            //     (Flak primary's 1455 ms cycle vereist sustained fire-press),
            //   - binary policyAction (0/1) voor de fire/altFire dims in NPZ.
            RLPawnFireDecisionProcessor.Decision fireDecision =
                fireProcessor.process(decoded.fireLogit(), decoded.altFireLogit(),
                    currentFrame, nowMs);
            ShootIntent shootIntent = fireDecision.intent();
            currentFrame.playerPawn.fireWantedDuringCooldown = shootIntent.fireSuppressedByCooldown();
            observeActionDiagnostics(sessionId, decoded, fireDecision);
            ShootingIntentStateBus.publish(sessionId, shootIntent);
            if (context.getBlackboard().has(BlackboardKeys.SHOOT_INTENT_BUS)) {
                ShootIntentBus shootBus = context.getBlackboard().get(BlackboardKeys.SHOOT_INTENT_BUS);
                shootBus.publish(shootIntent);
            }

            // Experience-recording. Zonder dit krijgt de joint SAC trainer
            // nooit NPZs en blijft hij op "Waiting for experience..." hangen
            // (de decoupled controllers hebben dezelfde hook).
            boolean hasRecorder = context.getBlackboard().has(BlackboardKeys.JOINT_PAWN_EXPERIENCE_RECORDER);
            if (diag) {
                LOG.info("DIAG_VRS_RECORDER hasKey=" + hasRecorder);
            }
            if (hasRecorder) {
                PerModelExperienceRecorder recorder = context.getBlackboard().get(
                    BlackboardKeys.JOINT_PAWN_EXPERIENCE_RECORDER);
                // policyAction full-joint layout:
                // [moveDir_sin_logit, moveDir_cos_logit, dodge, bJump, bDuck,
                //  bIdle, yaw_logit, pitch_logit, fire_binary, altFire_binary].
                // Movement and fire binaries are recorded as 0/1 decisions;
                // continuous dims remain pre-tanh logits so Python can map
                // them into SAC's tanh action domain consistently.
                // log_prob = NaN: joint SAC herrekent uit policy mean+std.
                float[] policyAction = new float[RLPawnActionDecoder.ACTIONS_LENGTH];
                policyAction[RLPawnActionDecoder.IDX_MOVE_SIN] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_MOVE_SIN];
                policyAction[RLPawnActionDecoder.IDX_MOVE_COS] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_MOVE_COS];
                policyAction[RLPawnActionDecoder.IDX_DODGE] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_DODGE];
                policyAction[RLPawnActionDecoder.IDX_JUMP] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_JUMP];
                policyAction[RLPawnActionDecoder.IDX_DUCK] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_DUCK];
                policyAction[RLPawnActionDecoder.IDX_IDLE] =
                    movementOutput.actions[RLPawnActionDecoder.IDX_IDLE];
                policyAction[RLPawnActionDecoder.IDX_YAW] =
                    actions[RLPawnActionDecoder.IDX_YAW];
                policyAction[RLPawnActionDecoder.IDX_PITCH] =
                    actions[RLPawnActionDecoder.IDX_PITCH];
                policyAction[RLPawnActionDecoder.IDX_FIRE] = fireDecision.fireBinaryAction();
                policyAction[RLPawnActionDecoder.IDX_ALTFIRE] = fireDecision.altFireBinaryAction();
                recorder.onTick(input, policyAction, currentFrame,
                    Float.NaN, decoded.rawTargetIndex(), 0.0f);
                if (diag) {
                    LOG.info("DIAG_VRS_ONTICK recorder.onTick fire="
                        + policyAction[RLPawnActionDecoder.IDX_FIRE]
                        + " alt=" + policyAction[RLPawnActionDecoder.IDX_ALTFIRE]
                        + " move=" + movementOutput.locomotionAction
                        + " jump=" + movementOutput.jump
                        + " duck=" + movementOutput.duck
                        + " dodgeDir=" + movementOutput.dodgeDir
                        + " targetIdx=" + decoded.rawTargetIndex());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "rl_pawn inference/intent failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reproduceer de heading-error berekening uit
     * {@code ViewRotationExecutorAiController.buildIntent} minimal-mode: angular
     * error = -atan2(enemy0_relSin, enemy0_relCos). Pure utility voor
     * {@link ViewTurnIntent#angularError} logging — invloed op de UDP-command
     * loopt via {@link ViewTurnIntent#yawDelta} / {@link ViewTurnIntent#pitchDelta}.
     */
    private float computeAngularError(float[][][] input) {
        if (input == null || input.length == 0 || input[0].length == 0) return 0f;
        int lastT = input[0].length - 1;
        float[] timelineRow = input[0][lastT];
        int relSinIdx = spec.idxEnemy0RelSin();
        int relCosIdx = spec.idxEnemy0RelCos();
        if (relSinIdx < 0 || relCosIdx < 0
            || relSinIdx >= timelineRow.length || relCosIdx >= timelineRow.length) {
            return 0f;
        }
        return -(float) Math.atan2(timelineRow[relSinIdx], timelineRow[relCosIdx]);
    }

    private static List<GameStateDto> states(List<GridFrame> window) {
        List<GameStateDto> out = new java.util.ArrayList<>(window.size());
        for (GridFrame frame : window) {
            out.add(frame.state());
        }
        return out;
    }

    private EnemySpawnYawFallback.Result resolveEnemySpawnYawFallback(GameStateDto frame) {
        int maxStep = aiplay.config.global.GlobalConfigRepository.shared()
            .commandController()
            .yawHeading()
            .continuousMaxStep();
        return EnemySpawnYawFallback.resolve(
            frame, spawnYawTargetState, EnemySpawnTargeting.DEFAULT_HOLD_TICKS, maxStep);
    }

    private void logEnemySpawnYawFallback(String sessionId,
                                          EnemySpawnYawFallback.Result fallback,
                                          long nowMs) {
        if (sessionId == null || sessionId.isBlank() || fallback == null) return;
        if ((nowMs - lastSpawnFallbackLogMs) < 250L) return;
        var target = fallback.target();
        LOG.info(String.format(Locale.ROOT,
            "RL_VRS_SPAWN_FALLBACK sid=%s yawDelta=%+.3f angErrDeg=%.1f target=(%.1f,%.1f,%.1f)",
            sessionId,
            fallback.yawDelta(),
            Math.toDegrees(fallback.angularError()),
            target != null ? target.x : 0.0,
            target != null ? target.y : 0.0,
            target != null ? target.z : 0.0));
        lastSpawnFallbackLogMs = nowMs;
    }

    private void observeActionDiagnostics(String sessionId, RLPawnActionDecoder.Decoded decoded,
                                          RLPawnFireDecisionProcessor.Decision fireDecision) {
        if (decoded == null || fireDecision == null) {
            return;
        }

        // rawFire = wat de policy wilde NA Bernoulli/threshold (vóór state-
        // machine gating). Voor het meten van de policy-bias is dit nuttiger
        // dan de UC-bound fire-flag die door cooldown wordt onderdrukt.
        boolean rawFire = fireDecision.rawSampledFire();
        boolean rawAltFire = fireDecision.rawSampledAltFire();
        ShootIntent shootIntent = fireDecision.intent();
        actionDiagSamples++;
        if (rawFire) actionDiagRawFire++;
        if (rawAltFire) actionDiagRawAltFire++;
        if (rawFire && rawAltFire) actionDiagRawBoth++;
        if (shootIntent.fire()) actionDiagFinalFire++;
        if (shootIntent.altFire()) actionDiagFinalAltFire++;
        if (Float.isFinite(decoded.fireLogit())) actionDiagFireLogitSum += decoded.fireLogit();
        if (Float.isFinite(decoded.altFireLogit())) actionDiagAltFireLogitSum += decoded.altFireLogit();

        if (actionDiagSamples < ACTION_DIAG_WINDOW_TICKS) {
            return;
        }

        double n = Math.max(1, actionDiagSamples);
        LOG.info(String.format(Locale.ROOT,
            "RL_VRS_ACTION_RATE sid=%s n=%d rawFire=%.3f rawAltFire=%.3f rawBoth=%.3f "
                + "fire=%.3f altFire=%.3f meanFireLogit=%.3f meanAltFireLogit=%.3f",
            sessionId,
            actionDiagSamples,
            actionDiagRawFire / n,
            actionDiagRawAltFire / n,
            actionDiagRawBoth / n,
            actionDiagFinalFire / n,
            actionDiagFinalAltFire / n,
            actionDiagFireLogitSum / n,
            actionDiagAltFireLogitSum / n));

        actionDiagSamples = 0;
        actionDiagRawFire = 0;
        actionDiagRawAltFire = 0;
        actionDiagRawBoth = 0;
        actionDiagFinalFire = 0;
        actionDiagFinalAltFire = 0;
        actionDiagFireLogitSum = 0.0;
        actionDiagAltFireLogitSum = 0.0;
    }

    // ===== Test hooks =====

    /** Test-only: injecteer een prepared spec + commitment tracker zonder ModelRoleRegistry. */
    void initForTest(InferencePort predictor, RLPawnModelSpec spec,
                     RLPawnCommitmentTracker tracker) {
        this.predictor = predictor;
        this.spec = spec;
        this.commitmentTracker = tracker;
        this.fireProcessor = new RLPawnFireDecisionProcessor(false);
        this.movementDecoder = new MovementActionDecoder();
        this.fireSamplerStochastic = false;
    }

    RLPawnCommitmentTracker commitmentTracker() {
        return commitmentTracker;
    }
}
