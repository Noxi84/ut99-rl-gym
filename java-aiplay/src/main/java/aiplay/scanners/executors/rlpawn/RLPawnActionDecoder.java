package aiplay.scanners.executors.rlpawn;

import aiplay.shared.shooting.ShootIntent;
import aiplay.shared.view.ViewTurnIntent;

/**
 * Pure decoder die full-joint ONNX output ({@code actions[10]} + {@code target_logits[5]})
 * vertaalt naar de bestaande {@link ViewTurnIntent} / {@link ShootIntent}
 * data-classes. Stateless — zie {@link RLPawnCommitmentTracker} voor de
 * target_index sticky-lock. Movement wordt door
 * {@link aiplay.scanners.executors.rlpawn.movement.MovementActionDecoder}
 * gedecodeerd zodat de standalone movement en full-joint policy exact dezelfde
 * actuatorsemantiek houden.
 *
 * <p>Action-encoding:</p>
 * <ul>
 *   <li>dim 0/1 = moveDir_sin/cos (movement decoder)</li>
 *   <li>dim 2..5 = dodge, bJump, bDuck, bIdle (movement decoder)</li>
 *   <li>dim 6 = yaw_delta_norm (pre-tanh logit)</li>
 *   <li>dim 7 = pitch_delta_norm (pre-tanh logit)</li>
 *   <li>dim 8 = fire (pre-tanh logit, threshold/sampling downstream)</li>
 *   <li>dim 9 = altFire (pre-tanh logit, threshold/sampling downstream)</li>
 * </ul>
 *
 * <p>Threshold op pre-tanh logit > 0.0 is equivalent aan post-sigmoid > 0.5
 * (logit 0 ↔ sigmoid 0.5). Bij beide-true wint de hoogste logit — matches
 * {@link aiplay.scanners.executors.shooting.ShootingActionDecoder} regel 59-66:
 * een UT99-speler kan niet beide vuurknoppen tegelijk indrukken.</p>
 */
public final class RLPawnActionDecoder {

    public static final int ACTIONS_LENGTH = 10;
    public static final int TARGET_LOGITS_LENGTH = 5;

    public static final int IDX_MOVE_SIN = 0;
    public static final int IDX_MOVE_COS = 1;
    public static final int IDX_DODGE = 2;
    public static final int IDX_JUMP = 3;
    public static final int IDX_DUCK = 4;
    public static final int IDX_IDLE = 5;
    public static final int IDX_YAW = 6;
    public static final int IDX_PITCH = 7;
    public static final int IDX_FIRE = 8;
    public static final int IDX_ALTFIRE = 9;

    public record Decoded(
        ViewTurnIntent viewTurnIntent,
        ShootIntent shootIntent,
        int rawTargetIndex,
        float yawLogit, float pitchLogit, float fireLogit, float altFireLogit
    ) {}

    private RLPawnActionDecoder() {}

    /**
     * @param actions       length-{@value #ACTIONS_LENGTH} float array van het policy head
     * @param targetLogits  length-{@value #TARGET_LOGITS_LENGTH} float array van de target_index aux head
     * @param angularError  current heading-error in radialen (alleen door geconsumeerd voor logging in {@link ViewTurnIntent})
     * @param nowMs         tick timestamp (millis)
     */
    public static Decoded decode(float[] actions, float[] targetLogits, float angularError, long nowMs) {
        if (actions == null || actions.length != ACTIONS_LENGTH) {
            throw new IllegalStateException(
                "rl_pawn: actions array moet " + ACTIONS_LENGTH + " floats hebben "
                    + "(moveDir_sin, moveDir_cos, dodge, bJump, bDuck, bIdle, yaw, pitch, fire, altFire) — got "
                    + (actions == null ? "null" : "length=" + actions.length)
                    + ". Mismatch met full-joint ONNX export format.");
        }
        if (targetLogits == null || targetLogits.length != TARGET_LOGITS_LENGTH) {
            throw new IllegalStateException(
                "rl_pawn: target_logits moet " + TARGET_LOGITS_LENGTH + " floats hebben "
                    + "(5 enemy slots) — got "
                    + (targetLogits == null ? "null (single-output ONNX?)" : "length=" + targetLogits.length)
                    + ". Joint ONNX export moet 2 outputs hebben: actions[10] + target_logits[5].");
        }

        float yawLogit = actions[IDX_YAW];
        float pitchLogit = actions[IDX_PITCH];
        float fireLogit = actions[IDX_FIRE];
        float altFireLogit = actions[IDX_ALTFIRE];

        // NaN/Inf guard: identical aan ViewRotationIntentMapper.map — een
        // corrupted policy (FP16 overflow, partial export) mag de UDP command
        // niet bereiken. tanh(NaN)=NaN; clamp naar 0.
        float yawDelta;
        float pitchDelta;
        if (!isFinite(yawLogit) || !isFinite(pitchLogit)) {
            yawDelta = 0f;
            pitchDelta = 0f;
        } else {
            yawDelta = (float) Math.tanh(yawLogit);
            pitchDelta = (float) Math.tanh(pitchLogit);
        }
        ViewTurnIntent viewIntent = new ViewTurnIntent(yawDelta, pitchDelta, angularError, nowMs);

        // Mutex op fire vs altFire — hoogste pre-tanh logit wint. NaN sanitatie:
        // niet-finite logit telt als negatief (geen vuur).
        boolean rawFire = isFinite(fireLogit) && fireLogit > 0.0f;
        boolean rawAlt  = isFinite(altFireLogit) && altFireLogit > 0.0f;
        boolean fire = rawFire;
        boolean altFire = rawAlt;
        if (rawFire && rawAlt) {
            if (fireLogit >= altFireLogit) {
                altFire = false;
            } else {
                fire = false;
            }
        }
        ShootIntent shootIntent = new ShootIntent(fire, altFire, nowMs);

        int rawTargetIndex = argmax(targetLogits);

        return new Decoded(viewIntent, shootIntent, rawTargetIndex,
            yawLogit, pitchLogit, fireLogit, altFireLogit);
    }

    private static int argmax(float[] x) {
        int best = 0;
        float bestV = x[0];
        for (int i = 1; i < x.length; i++) {
            if (x[i] > bestV) {
                bestV = x[i];
                best = i;
            }
        }
        return best;
    }

    private static boolean isFinite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }
}
