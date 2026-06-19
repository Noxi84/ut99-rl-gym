package aiplay.play;

import ai.onnxruntime.OrtException;
import aiplay.dto.GameStateDto;
import aiplay.dto.GridFrame;
import aiplay.scanners.executors.PlayExecutionService;
import aiplay.scanners.model.TrainingModelService;
import behaviortree.BehaviorTreeContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class AiPlayFacade {

  // raw capture buffer — initialized in constructor after rawBufferCap is computed
  private Deque<RawFrame> rawBuffer;
  private RawFrame[] rawScratch;

  // resampled output buffer size
  private final int requiredBufferSize;
  private final int rawBufferCap;

  private final TrainingModelService trainingModelService;
  private final PlayExecutionService executionService;
  private final AiPlayService aiPlayService;

  // Game-time based resampling state (milliseconds from UT99 elapsedTime)
  private boolean gridInitialized = false;
  private long resampleStartMs = 0L;
  private long lastReturnedGridMs = 0L;
  private long frameCounter = 0L;

  // Delta-mode: track the highest gridMs we already emitted so we only send new slots.
  // Long.MIN_VALUE means "nothing emitted yet" — always smaller than any real gridMs.
  private long lastEmittedGridMs = Long.MIN_VALUE;

  public AiPlayFacade() {
    this(new TrainingModelService(), new PlayExecutionService());
  }

  private AiPlayFacade(TrainingModelService tms, PlayExecutionService pes) {
    this(tms.getMaxCsvFps(), tms.getMaxCsvNumberOfColumns(), pes.getMaxPredictionFps(),
         new AiPlayService());
    // trainingModelService and executionService are only needed to compute the sizes above;
    // they are not stored (fields are null in the test constructor, unused in production).
  }

  /**
   * Package-private constructor for testing — takes the three buffer-sizing values directly
   * so tests do not need to instantiate the heavyweight service classes.
   */
  AiPlayFacade(int maxCsvFps, int maxCsvColumns, int maxPredictionFps,
               AiPlayService aiPlayService) {
    this.trainingModelService = null;
    this.executionService = null;
    this.aiPlayService = aiPlayService;

    int required = getMax(maxCsvFps, maxCsvColumns, maxPredictionFps) + 2;

    this.requiredBufferSize = required;
    // rawBuffer holds raw captures for resampling. Needs enough history to fill the output window.
    // Scales automatically with game_speed via requiredBufferSize.
    this.rawBufferCap = required * 2;
    this.rawBuffer = new ArrayDeque<>(rawBufferCap + 16);
    this.rawScratch = new RawFrame[rawBufferCap + 1];
  }


  private int getMax(int... numbers) {
    if (numbers == null || numbers.length == 0) {
      throw new IllegalArgumentException("Minstens één getal is vereist");
    }

    int max = numbers[0];
    for (int n : numbers) {
      if (n > max) {
        max = n;
      }
    }
    return max;
  }

  /**
   * Fetches 1 raw gamestate and builds an FPS-aligned buffer on the game-time axis.
   * <p>
   * The resampling grid is based on game-time (from UT99 elapsedTime via AiPlayService),
   * NOT on wall-clock (System.nanoTime). This makes the output speed-invariant:
   * at any game_speed, the policy sees the same "last 1 game-second" of frames
   * with the same time spacing.
   * <p>
   * Grid (windowEnd) is never advanced past the newest raw capture's game-time.
   */
  public List<GridFrame> getNewGameStatus(BehaviorTreeContext context, double fps) throws OrtException {
    GameStateDto current = aiPlayService.getCurrentGameStatus(context);

    if (current == null) {
      return null;
    }

    boolean isDead = current.playerPawn == null || current.playerPawn.health <= 0;
    boolean wasDeadBefore = !rawBuffer.isEmpty()
        && rawBuffer.peekLast() != null
        && rawBuffer.peekLast().dto != null
        && rawBuffer.peekLast().dto.playerPawn != null
        && rawBuffer.peekLast().dto.playerPawn.health <= 0;

    if (isDead && !wasDeadBefore) {
      rawBuffer.clear();

      gridInitialized = false;
      resampleStartMs = 0L;
      lastReturnedGridMs = 0L;
      lastEmittedGridMs = Long.MIN_VALUE;
      frameCounter = 0L;

      aiPlayService.refreshPredictorsWithRetry(context,10, 150);
      return null;
    }

    // Game-time from AiPlayService (based on UT99 elapsedTime, speed-invariant)
    long gameTimeMs = current.timestampMillis;

    // Store raw frame keyed on game-time
    rawBuffer.addLast(new RawFrame(current, gameTimeMs));

    while (rawBuffer.size() > rawBufferCap) {
      rawBuffer.removeFirst();
    }

    if (fps <= 0.0) {
      throw new IllegalArgumentException("fps must be > 0, got " + fps);
    }

    long intervalMs = Math.max(1L, Math.round(1000.0 / fps));

    // Init resample grid on game-time axis
    if (!gridInitialized) {
      gridInitialized = true;
      resampleStartMs = gameTimeMs - (long) (requiredBufferSize - 1) * intervalMs;
      lastReturnedGridMs = resampleStartMs + (long) (requiredBufferSize - 1) * intervalMs;
      frameCounter = 0L;

      if (lastReturnedGridMs > gameTimeMs) {
        lastReturnedGridMs = snapDownToGrid(gameTimeMs, resampleStartMs, intervalMs);
      }
    } else {
      // Advance grid to newest game-time, never past it
      long maxGridMs = snapDownToGrid(gameTimeMs, resampleStartMs, intervalMs);
      if (maxGridMs > lastReturnedGridMs) {
        lastReturnedGridMs = maxGridMs;
      }
    }

    long windowEndMs = lastReturnedGridMs;
    long windowStartMs = windowEndMs - (long) (requiredBufferSize - 1) * intervalMs;

    // Build fixed-grid frames via hold-last resampling on game-time.
    // Delta-mode: only emit slots newer than lastEmittedGridMs.
    // On first call (or after reset) lastEmittedGridMs == -1, so the full window is emitted.
    List<GridFrame> out = new ArrayList<GridFrame>(requiredBufferSize);

    RawFrame lastKnown = null;
    RawFrame[] rawArray = rawBuffer.toArray(rawScratch);
    int rawLen = rawBuffer.size();
    int rawIdx = 0;

    for (int i = 0; i < requiredBufferSize; i++) {
      long gridMs = windowStartMs + (long) i * intervalMs;

      // Always advance rawIdx so lastKnown stays correct for subsequent slots.
      while (rawIdx < rawLen
          && rawArray[rawIdx] != null
          && rawArray[rawIdx].gameTimeMs <= gridMs) {
        lastKnown = rawArray[rawIdx];
        rawIdx++;
      }

      // Skip slots we already sent in a previous tick — no copy needed.
      if (gridMs <= lastEmittedGridMs) {
        continue;
      }

      GameStateDto chosen;
      if (lastKnown != null && lastKnown.dto != null) {
        chosen = lastKnown.dto.shallowCopyForEnrichment();
      } else {
        RawFrame first = rawLen > 0 ? rawArray[0] : null;
        if (first != null && first.dto != null) {
          chosen = first.dto.shallowCopyForEnrichment();
        } else {
          chosen = current.shallowCopyForEnrichment();
        }
      }

      // Set timestamp on DTO for backward compatibility with enrichers/resolvers
      chosen.timestampMillis = gridMs;
      int fn = (int) (++frameCounter);
      chosen.frameNumber = fn;

      out.add(new GridFrame(chosen, gridMs, fn));
    }

    if (!out.isEmpty()) {
      lastEmittedGridMs = out.get(out.size() - 1).gridMs();
    }

    return out.isEmpty() ? null : out;
  }

  /**
   * Snap a time (ms) down to the last grid point <= timeMs.
   */
  private static long snapDownToGrid(long timeMs, long gridStartMs, long intervalMs) {
    if (intervalMs <= 0L) {
      return timeMs;
    }
    long delta = timeMs - gridStartMs;
    if (delta <= 0L) {
      return gridStartMs;
    }
    long steps = delta / intervalMs;
    return gridStartMs + steps * intervalMs;
  }

  private static final class RawFrame {

    private final GameStateDto dto;
    private final long gameTimeMs;

    private RawFrame(GameStateDto dto, long gameTimeMs) {
      this.dto = dto;
      this.gameTimeMs = gameTimeMs;
    }
  }
}
