package aiplay.rl.rewards.objective.scoregainrate;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.dto.PlayerDto;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dense per-tick reward proportioneel aan eigen UT99 score-gain over een rolling window.
 *
 * <p>Anchort de SAC-gradient direct aan de DeltaGate goal-metric (RL-bot score-rate vs
 * UT99-baseline). Voorheen detecteerde DeltaGate Goodhart-drift maar duwde de policy niet
 * richting score-gain — reward-proxy en goal-metric divergeerden. Deze reward maakt de
 * gradient consistent met wat de gate test.
 *
 * <p><b>Formule</b> (per tick, integraal-correct):
 *
 * <pre>
 *   reward = weight × scoreDelta × dtMs / windowMs
 * </pre>
 *
 * <p>waarbij {@code scoreDelta = curr.score − oldest_score_in_window} en {@code dtMs} de
 * tijd is tussen prev en curr frame. Cumulatieve gen-bijdrage = {@code weight ×
 * score_gained_in_gen}, schaal-invariant t.o.v. tick-rate. Eerste implementatie gaf per
 * tick {@code weight × rate_per_min} — fysisch incorrect (geen tijds-eenheid in de reward),
 * waardoor de bijdrage 3000× te groot was en de andere reward-componenten wegdrukte
 * (geobserveerd 2026-05-06: returns sprongen 3 → 27, frequente ADAM_WIPE en PROBE_FAIL).
 *
 * <p><b>State:</b> één instance per bot via {@link aiplay.rl.RewardComputer}. De reward houdt
 * een eigen FIFO van {@code (timestampMs, score)}-snapshots, één per {@link #compute} call.
 * Snapshots ouder dan {@code windowMs} worden afgevoerd.
 *
 * <p><b>Match/respawn-reset:</b> UT99 reset score naar 0 bij match-end. Wanneer
 * {@code curr.score < oldest.score} in het window wordt de buffer geleegd en de reward = 0
 * voor die tick. Voorkomt dat een score-reset als negatieve rate doorslaat (en met negative
 * weight een grote anti-reward zou worden).
 *
 * <p><b>Warm-up:</b> zolang het window kleiner is dan {@code minWindowMs} (default 5s) is de
 * rate-schatting niet betrouwbaar — reward = 0. Daarna lineair op rate * weight.
 */
public class ScoreGainRateReward implements RewardComponent {

  private final ScoreGainRateParams params;
  private final Deque<Snapshot> history = new ArrayDeque<>();

  public ScoreGainRateReward(ScoreGainRateParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "ScoreGainRateReward requires non-null ScoreGainRateParams");
    }
    this.params = params;
  }

  private record Snapshot(long timeMs, int score) {}

  @Override
  public synchronized double compute(RewardContext ctx) {
    PlayerDto currSelf = ctx.curr().playerPawn;
    if (currSelf == null) {
      return 0.0;
    }
    long now = ctx.curr().timestampMillis;
    int score = currSelf.score;

    // Drop entries outside the window. Capture peekFirst() once per iteration: with
    // concurrent access the deque can be drained between !isEmpty() and peekFirst(),
    // and ArrayDeque.peekFirst() can also return null transiently during a write from
    // another thread even after isEmpty() returned false (no memory barrier).
    long cutoff = now - params.windowMs();
    Snapshot head;
    while ((head = history.peekFirst()) != null && head.timeMs() < cutoff) {
      history.pollFirst();
    }

    Snapshot oldest = history.peekFirst();

    // Match-restart / score-reset: UT99 sets score to 0 between matches. A drop
    // in score is not a real performance regression — clear the window so the
    // next reward uses the fresh-match score baseline.
    if (oldest != null && score < oldest.score()) {
      history.clear();
      history.addLast(new Snapshot(now, score));
      return 0.0;
    }

    double reward = 0.0;
    if (oldest != null) {
      long span = now - oldest.timeMs();
      if (span >= params.minWindowMs() && span > 0) {
        // Per-tick integraal van rate over dt: weight × scoreDelta × dtMs / windowMs.
        // Cumulatieve gen-bijdrage = weight × score_gained_in_gen × (gen_ms / windowMs).
        // Bij gen ≫ windowMs reflecteert dit feitelijk weight × score_gained × gen-multiplier
        // — de score-gain wordt op constant tempo verdeeld over alle ticks.
        long dtMs = now - ctx.prev().timestampMillis;
        if (dtMs > 0) {
          double scoreDelta = (double) (score - oldest.score());
          reward = params.weight() * scoreDelta * dtMs / params.windowMs();
        }
      }
    }

    history.addLast(new Snapshot(now, score));
    return reward;
  }
}
