package aiplay.rl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes experience transitions in numpy-compatible .npz format.
 * Each .npz file contains arrays: states, actions, rewards, next_states, dones.
 *
 * The .npy format is: magic(6) + version(2) + header_len(2) + header + data.
 * We use format version 1.0 with float32 (little-endian) arrays.
 */
public class ReplayBufferWriter {

    private static final byte[] NPY_MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};
    private static final byte NPY_MAJOR = 1;
    private static final byte NPY_MINOR = 0;
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    /**
     * Write a batch of transitions to an .npz file.
     *
     * @param outputPath    path to write the .npz file
     * @param states        [N][stateSize] state feature vectors
     * @param actions       [N][actionSize] action vectors
     * @param rewards       [N] scalar rewards
     * @param nextStates    [N][stateSize] next state feature vectors
     * @param dones         [N] done flags (1.0 = episode ended, 0.0 = ongoing)
     */
    public void write(Path outputPath, float[][] states, float[][] actions,
                      float[] rewards, float[][] nextStates, float[] dones) throws IOException {
        write(outputPath, states, actions, rewards, nextStates, dones, null, null, null);
    }

    /** Additionally writes {@code target_indices.npy} and
     *  {@code target_log_probs.npy} when both arrays are non-null. The Python
     *  trainer detects their presence and adds the target_head supervision.
     *  Pass null for both arrays to write the legacy 5-array layout. */
    public void write(Path outputPath, float[][] states, float[][] actions,
                      float[] rewards, float[][] nextStates, float[] dones,
                      float[] targetIndices, float[] targetLogProbs) throws IOException {
        write(outputPath, states, actions, rewards, nextStates, dones, null,
            targetIndices, targetLogProbs);
    }

    /** Full metadata overload. {@code action_log_probs.npy} stores the
     * behavior-policy log_prob of each sampled action. Target arrays are still
     * optional and are only written when both are non-null. */
    public void write(Path outputPath, float[][] states, float[][] actions,
                      float[] rewards, float[][] nextStates, float[] dones,
                      float[] actionLogProbs,
                      float[] targetIndices, float[] targetLogProbs) throws IOException {
        write(outputPath, states, actions, rewards, nextStates, dones,
            actionLogProbs, targetIndices, targetLogProbs, null);
    }

    /** Champion-aware overload — additionally writes {@code policy_role.npy}
     *  (one int32 per row, encoded as float: 0=CURRENT, 1=CHAMPION) so the
     *  Python trainer can filter out frozen-champion rollouts before feeding
     *  SAC. Pass {@code null} for the legacy (pre-champion) layout — Python
     *  loaders default missing column to CURRENT for backwards compat. */
    public void write(Path outputPath, float[][] states, float[][] actions,
                      float[] rewards, float[][] nextStates, float[] dones,
                      float[] actionLogProbs,
                      float[] targetIndices, float[] targetLogProbs,
                      float[] policyRole) throws IOException {
        write(outputPath, states, actions, rewards, nextStates, dones,
            actionLogProbs, targetIndices, targetLogProbs, policyRole, null);
    }

    /** Joint VR+shooting overload (vr-shooting-sac-merge.md sectie 7.3 commitment 3):
     *  schrijft naast de scalar reward de per-skill decomp arrays
     *  ({@code reward_movement, reward_view, reward_pitch, reward_fire,
     *  reward_altFire, reward_team_assist, reward_residual}), aux target supervision
     *  ({@code target_label} int64 + {@code target_confidence} float32), en
     *  Fase 2.5 CTDE teammate-state slices. Pass {@code null} voor de legacy
     *  layout — decoupled VR / shooting / movement writers blijven ongewijzigd.
     *
     *  <p>NPZ kolomnamen matchen de Python ingestor: zie
     *  {@code train/rl/shared/sac_core/replay_buffer.py:_resolve_npz_key}
     *  voor de naamkonventie ({@code reward_<skill>} en {@code target_label} /
     *  {@code target_confidence}).</p>
     *
     *  @param jointExtras  immutable bundel met decomp + target arrays; null voor
     *                      decoupled writers. Lengtes worden gevalideerd tegen
     *                      {@code states.length}.
     */
    public void write(Path outputPath, float[][] states, float[][] actions,
                      float[] rewards, float[][] nextStates, float[] dones,
                      float[] actionLogProbs,
                      float[] targetIndices, float[] targetLogProbs,
                      float[] policyRole,
                      JointExperienceExtras jointExtras) throws IOException {

        int n = states.length;
        if (n == 0) return;

        int stateSize = states[0].length;
        int actionSize = actions[0].length;

        if (jointExtras != null) {
            jointExtras.validateLength(n);
        }

        // Write to temp file first, then atomic rename
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        Files.createDirectories(outputPath.getParent());

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempPath))) {
            writeNpyEntry(zos, "states.npy", states, n, stateSize);
            writeNpyEntry(zos, "actions.npy", actions, n, actionSize);
            writeNpyEntry(zos, "rewards.npy", rewards, n);
            writeNpyEntry(zos, "next_states.npy", nextStates, n, stateSize);
            writeNpyEntry(zos, "dones.npy", dones, n);
            if (actionLogProbs != null) {
                writeNpyEntry(zos, "action_log_probs.npy", actionLogProbs, n);
            }
            if (targetIndices != null && targetLogProbs != null) {
                writeNpyEntry(zos, "target_indices.npy", targetIndices, n);
                writeNpyEntry(zos, "target_log_probs.npy", targetLogProbs, n);
            }
            if (policyRole != null) {
                writeNpyEntry(zos, "policy_role.npy", policyRole, n);
            }
            if (jointExtras != null) {
                writeNpyEntry(zos, "reward_movement.npy", jointExtras.rewardMovement, n);
                writeNpyEntry(zos, "reward_view.npy", jointExtras.rewardView, n);
                writeNpyEntry(zos, "reward_pitch.npy", jointExtras.rewardPitch, n);
                writeNpyEntry(zos, "reward_fire.npy", jointExtras.rewardFire, n);
                writeNpyEntry(zos, "reward_altFire.npy", jointExtras.rewardAltFire, n);
                writeNpyEntry(zos, "reward_team_assist.npy", jointExtras.rewardTeamAssist, n);
                writeNpyEntry(zos, "reward_residual.npy", jointExtras.rewardResidual, n);
                writeNpyEntryInt64(zos, "target_label.npy", jointExtras.targetLabel, n);
                writeNpyEntry(zos, "target_confidence.npy", jointExtras.targetConfidence, n);
                writeNpyEntry(zos, "teammate_state.npy", jointExtras.teammateState, n,
                    jointExtras.teammateState[0].length);
                writeNpyEntry(zos, "next_teammate_state.npy", jointExtras.nextTeammateState, n,
                    jointExtras.nextTeammateState[0].length);
            }
        }

        Files.move(tempPath, outputPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeNpyEntryInt64(ZipOutputStream zos, String name, long[] data,
                                     int length) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        writeNpyHeader(zos, "{'descr': '<i8', 'fortran_order': False, 'shape': (" + length + ",), }");
        ByteBuffer buf = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            putLong(zos, buf, data[i]);
        }
        flushBuffer(zos, buf);
        zos.closeEntry();
    }

    /**
     * Immutable bundel met joint VR+shooting NPZ extras. Validatie van
     * lengte-consistentie gebeurt in {@link #validateLength(int)} zodat
     * inconsistente shapes vroeg crashen (CLAUDE.md: geen silent fallbacks).
     */
    public static final class JointExperienceExtras {
        public final float[] rewardView;
        public final float[] rewardMovement;
        public final float[] rewardPitch;
        public final float[] rewardFire;
        public final float[] rewardAltFire;
        /** Fase 2.5 CTDE — 6e per-skill reward channel. */
        public final float[] rewardTeamAssist;
        /** 7e per-skill channel: residual (death + damage_taken) — survival/damage-taken gradient. */
        public final float[] rewardResidual;
        public final long[]  targetLabel;
        public final float[] targetConfidence;
        /** Fase 2.5 CTDE — closest-2 teammate state slice at the current step, shape [N, FEATURES]. */
        public final float[][] teammateState;
        /** Fase 2.5 CTDE — closest-2 teammate state at the next step. Same shape. */
        public final float[][] nextTeammateState;

        public JointExperienceExtras(float[] rewardMovement, float[] rewardView, float[] rewardPitch,
                                     float[] rewardFire, float[] rewardAltFire,
                                     float[] rewardTeamAssist, float[] rewardResidual,
                                     long[] targetLabel, float[] targetConfidence,
                                     float[][] teammateState, float[][] nextTeammateState) {
            this.rewardMovement = rewardMovement;
            this.rewardView = rewardView;
            this.rewardPitch = rewardPitch;
            this.rewardFire = rewardFire;
            this.rewardAltFire = rewardAltFire;
            this.rewardTeamAssist = rewardTeamAssist;
            this.rewardResidual = rewardResidual;
            this.targetLabel = targetLabel;
            this.targetConfidence = targetConfidence;
            this.teammateState = teammateState;
            this.nextTeammateState = nextTeammateState;
        }

        void validateLength(int n) {
            if (rewardMovement.length != n || rewardView.length != n || rewardPitch.length != n
                || rewardFire.length != n || rewardAltFire.length != n
                || rewardTeamAssist.length != n || rewardResidual.length != n
                || targetLabel.length != n || targetConfidence.length != n
                || teammateState.length != n || nextTeammateState.length != n) {
                throw new IllegalArgumentException(
                    "JointExperienceExtras length mismatch: expected " + n
                        + " got movement=" + rewardMovement.length
                        + " got view=" + rewardView.length
                        + " pitch=" + rewardPitch.length
                        + " fire=" + rewardFire.length
                        + " altFire=" + rewardAltFire.length
                        + " teamAssist=" + rewardTeamAssist.length
                        + " residual=" + rewardResidual.length
                        + " label=" + targetLabel.length
                        + " conf=" + targetConfidence.length
                        + " teammateState=" + teammateState.length
                        + " nextTeammateState=" + nextTeammateState.length);
            }
            if (teammateState.length > 0 && teammateState[0] == null) {
                throw new IllegalArgumentException(
                    "JointExperienceExtras teammateState contains a null row");
            }
            if (nextTeammateState.length > 0 && nextTeammateState[0] == null) {
                throw new IllegalArgumentException(
                    "JointExperienceExtras nextTeammateState contains a null row");
            }
            if (teammateState.length > 0 && nextTeammateState.length > 0
                && teammateState[0].length != nextTeammateState[0].length) {
                throw new IllegalArgumentException(
                    "JointExperienceExtras teammateState/nextTeammateState column mismatch: "
                        + teammateState[0].length + " vs " + nextTeammateState[0].length);
            }
        }
    }

    private void writeNpyEntry(ZipOutputStream zos, String name, float[][] data,
                                int rows, int cols) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        writeNpyHeader(zos, "{'descr': '<f4', 'fortran_order': False, 'shape': (" + rows + ", " + cols + "), }");
        ByteBuffer buf = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                putFloat(zos, buf, data[i][j]);
            }
        }
        flushBuffer(zos, buf);
        zos.closeEntry();
    }

    private void writeNpyEntry(ZipOutputStream zos, String name, float[] data,
                                int length) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        writeNpyHeader(zos, "{'descr': '<f4', 'fortran_order': False, 'shape': (" + length + ",), }");
        ByteBuffer buf = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            putFloat(zos, buf, data[i]);
        }
        flushBuffer(zos, buf);
        zos.closeEntry();
    }

    private void writeNpyHeader(ZipOutputStream zos, String headerContent) throws IOException {
        int headerContentLen = headerContent.length();
        int preambleLen = NPY_MAGIC.length + 2 + 2;
        int totalBefore = preambleLen + headerContentLen + 1;
        int padding = (64 - (totalBefore % 64)) % 64;
        int headerLen = headerContentLen + padding + 1;

        ByteBuffer preamble = ByteBuffer.allocate(preambleLen).order(ByteOrder.LITTLE_ENDIAN);
        preamble.put(NPY_MAGIC);
        preamble.put(NPY_MAJOR);
        preamble.put(NPY_MINOR);
        preamble.putShort((short) headerLen);
        zos.write(preamble.array());
        zos.write(headerContent.getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < padding; i++) {
            zos.write((byte) ' ');
        }
        zos.write((byte) '\n');
    }

    private void putFloat(ZipOutputStream zos, ByteBuffer buf, float value) throws IOException {
        if (buf.remaining() < Float.BYTES) {
            flushBuffer(zos, buf);
        }
        buf.putFloat(value);
    }

    private void putLong(ZipOutputStream zos, ByteBuffer buf, long value) throws IOException {
        if (buf.remaining() < Long.BYTES) {
            flushBuffer(zos, buf);
        }
        buf.putLong(value);
    }

    private void flushBuffer(ZipOutputStream zos, ByteBuffer buf) throws IOException {
        int len = buf.position();
        if (len > 0) {
            zos.write(buf.array(), 0, len);
            buf.clear();
        }
    }

}
