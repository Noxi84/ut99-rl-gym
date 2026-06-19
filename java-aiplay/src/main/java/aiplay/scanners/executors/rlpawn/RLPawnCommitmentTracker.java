package aiplay.scanners.executors.rlpawn;

/**
 * target_index commitment-lock voor de joint controller (vr-shooting-sac-merge.md
 * sectie 4.3). Houdt de gekozen enemy-slot N ticks vast, ook al schuift het
 * argmax naar een ander slot — dit voorkomt de aim-swing-tussen-enemies failure
 * mode die in decoupled VR + shooting werd gedocumenteerd
 * ({@code viewrotation-architecture.md:177-185}).
 *
 * <p>Niet thread-safe — één tracker per session, gequeried vanuit de consumer
 * thread van {@code RLPawnExecutorAiController}.</p>
 *
 * <p>Gedrag:</p>
 * <ol>
 *   <li>Tick 0 of na expiry: nieuwe candidate wint, lock-counter reset naar
 *       {@code lockTicks}.</li>
 *   <li>Tick t > 0 met candidate == committed: lock-counter reset (sticky-refresh).</li>
 *   <li>Tick t > 0 met candidate != committed terwijl counter > 0: committed
 *       blijft, counter telt af.</li>
 * </ol>
 */
public final class RLPawnCommitmentTracker {

    public static final int ABSENT = -1;

    private final int lockTicks;
    private int committedIndex = ABSENT;
    private int ticksRemaining = 0;

    public RLPawnCommitmentTracker(int lockTicks) {
        if (lockTicks < 0) {
            throw new IllegalArgumentException(
                "rl_pawn: target_commitment_lock_ticks moet >= 0 (kreeg " + lockTicks + ")");
        }
        this.lockTicks = lockTicks;
    }

    /**
     * Apply één tick van de commitment-state machine en retourneer de
     * effectieve target_index voor publicatie naar {@code ShootingTargetIndexBus}.
     *
     * <p>Semantiek bij {@code lockTicks=N}: na een commit op tick 0 worden
     * conflict-updates op tick 1..N-1 genegeerd; bij de N-de conflict-update
     * (tick N) verstrijkt de lock en wordt de nieuwe argmax overgenomen.
     * Voor N=12 @ 30 Hz komt dat overeen met ≈400 ms aanhoud-tijd.</p>
     */
    public int update(int rawArgmaxIndex) {
        if (committedIndex == ABSENT) {
            committedIndex = rawArgmaxIndex;
            ticksRemaining = lockTicks;
            return committedIndex;
        }
        if (rawArgmaxIndex == committedIndex) {
            ticksRemaining = lockTicks;
            return committedIndex;
        }
        // Conflict — decrement counter; bij <=0 verstreken lock, migreer.
        ticksRemaining--;
        if (ticksRemaining <= 0) {
            committedIndex = rawArgmaxIndex;
            ticksRemaining = lockTicks;
            return committedIndex;
        }
        return committedIndex;
    }

    public int committedIndex() {
        return committedIndex;
    }

    public int ticksRemaining() {
        return ticksRemaining;
    }

    public int lockTicks() {
        return lockTicks;
    }
}
