package aiplay.scanners.feature.resolver;

import aiplay.dto.DodgeState;
import aiplay.dto.PlayerDto;

/**
 * Tracks dodge state across frames for a fixed-size slot array of PlayerDtos
 * (enemies or teammates). Shared by {@code EnemySlotRelativeBatchEnricher}
 * and {@code TeammateSlotRelativeBatchEnricher} to avoid duplicating the
 * temporal tracking logic for {@code activeDodgeDir} + {@code dodgeCooldownNorm}.
 *
 * Two usage modes:
 * <ul>
 *   <li><b>Batch</b>: frame-indexed, used for CSV writing over a bounded frame list.
 *       State is a {@link BatchState} created for the duration of one batch.</li>
 *   <li><b>Incremental</b>: real-time wall-clock tracking. State is {@link IncrementalState}
 *       kept per-session (and per-slot).</li>
 * </ul>
 */
public final class PlayerSlotDodgeTracker {

    private PlayerSlotDodgeTracker() {}

    /** Mutable per-slot tracking state for batch mode. */
    public static final class BatchState {
        public final int[] trackedDir;
        public final DodgeState[] prevState;
        public final int[] dodgeInitFrame;

        public BatchState(int maxSlots) {
            this.trackedDir = new int[maxSlots];
            this.prevState = new DodgeState[maxSlots];
            this.dodgeInitFrame = new int[maxSlots];
            for (int s = 0; s < maxSlots; s++) {
                prevState[s] = DodgeState.NONE;
                dodgeInitFrame[s] = -1;
            }
        }
    }

    /** Mutable per-slot tracking state for incremental/real-time mode. */
    public static final class IncrementalState {
        public int trackedDir = 0;
        public DodgeState prevDodgeState = DodgeState.NONE;
        public long lastDodgeInitMs = -1L;
    }

    /**
     * Update dodge tracking for a slot array (enemies or teammates) in batch mode.
     * Mutates {@code players[s].activeDodgeDir} and {@code dodgeCooldownNorm} in-place.
     */
    public static void updateBatch(PlayerDto[] players, int frameIdx,
                                   BatchState state, double frameDurationMs,
                                   int dodgeCooldownMs) {
        if (players == null) return;
        int maxSlots = state.trackedDir.length;
        for (int s = 0; s < Math.min(players.length, maxSlots); s++) {
            PlayerDto player = players[s];
            if (player == null) continue;

            DodgeState ds = player.dodgeState;
            if (ds == null) ds = DodgeState.NONE;

            state.trackedDir[s] = trackDir(ds, state.trackedDir[s]);
            player.activeDodgeDir = state.trackedDir[s];

            if (state.prevState[s] == DodgeState.NONE && isDirectional(ds)) {
                state.dodgeInitFrame[s] = frameIdx;
            }
            state.prevState[s] = ds;

            if (state.dodgeInitFrame[s] < 0) {
                player.dodgeCooldownNorm = 1.0f;
            } else {
                double elapsedMs = (frameIdx - state.dodgeInitFrame[s]) * frameDurationMs;
                player.dodgeCooldownNorm = (float) Math.min(elapsedMs / dodgeCooldownMs, 1.0);
            }
        }
    }

    /**
     * Update dodge tracking for a slot array in incremental (real-time) mode.
     * Mutates {@code players[s].activeDodgeDir} and {@code dodgeCooldownNorm} in-place.
     */
    public static void updateIncremental(PlayerDto[] players, IncrementalState[] slots,
                                         long now, int dodgeCooldownMs) {
        if (players == null) return;
        int maxSlots = slots.length;
        for (int s = 0; s < Math.min(players.length, maxSlots); s++) {
            PlayerDto player = players[s];
            if (player == null) continue;

            DodgeState ds = player.dodgeState;
            if (ds == null) ds = DodgeState.NONE;

            slots[s].trackedDir = trackDir(ds, slots[s].trackedDir);
            player.activeDodgeDir = slots[s].trackedDir;

            if (slots[s].prevDodgeState == DodgeState.NONE && isDirectional(ds)) {
                slots[s].lastDodgeInitMs = now;
            }
            slots[s].prevDodgeState = ds;

            if (slots[s].lastDodgeInitMs < 0) {
                player.dodgeCooldownNorm = 1.0f;
            } else {
                double elapsedMs = now - slots[s].lastDodgeInitMs;
                player.dodgeCooldownNorm = (float) Math.min(elapsedMs / dodgeCooldownMs, 1.0);
            }
        }
    }

    public static IncrementalState[] createIncrementalSlots(int maxSlots) {
        IncrementalState[] arr = new IncrementalState[maxSlots];
        for (int s = 0; s < maxSlots; s++) arr[s] = new IncrementalState();
        return arr;
    }

    private static int trackDir(DodgeState ds, int trackedDir) {
        return switch (ds) {
            case FORWARD -> 1;
            case BACK    -> 2;
            case LEFT    -> 3;
            case RIGHT   -> 4;
            case ACTIVE  -> trackedDir;
            default      -> 0;
        };
    }

    private static boolean isDirectional(DodgeState ds) {
        return ds == DodgeState.FORWARD || ds == DodgeState.BACK
            || ds == DodgeState.LEFT || ds == DodgeState.RIGHT;
    }

    /**
     * World-space dodge direction sin/cos component. Uses the tracked activeDodgeDir
     * (persists through ACTIVE phase) rotated by the player's viewRotation.
     * Returns 0.0 when not dodging or viewRotation missing.
     */
    public static float dodgeDirComponent(PlayerDto player, boolean sinComponent) {
        if (player == null || player.activeDodgeDir == 0 || player.viewRotation == null) {
            return 0.0f;
        }
        int playerYaw = player.viewRotation.x & 0xFFFF;
        int offset = switch (player.activeDodgeDir) {
            case 1 -> 0;
            case 2 -> 32768;
            case 3 -> 16384;
            case 4 -> -16384;
            default -> 0;
        };
        int worldAngleUt = (playerYaw + offset) & 0xFFFF;
        double rad = worldAngleUt * 2.0 * Math.PI / 65536.0;
        return sinComponent ? (float) Math.sin(rad) : (float) Math.cos(rad);
    }
}
