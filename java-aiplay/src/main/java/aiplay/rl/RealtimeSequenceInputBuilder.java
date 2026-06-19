package aiplay.rl;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.CanonicalPerspectiveNormalizer;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.feature.contract.FeatureContract;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.List;

public class RealtimeSequenceInputBuilder {

  private static final int FEATURE_CACHE_MAX = 120;

  private final TrainingFeatureService featureService = TrainingFeatureService.shared();
  private final String modelKey;
  private final List<String> inputFeatureOrder;
  private final int seqLen;
  private final FeatureContract contract;
  private final CanonicalPerspectiveNormalizer normalizer;
  // Thread-safe bounded LRU. Vervangt access-order LinkedHashMap die in
  // productie 5/54 instances liet ontsporen tot ~800K entries (zie MAT-
  // analyse 2026-05-22, 17.4 GB retained door één controller).
  private final Cache<Long, float[]> featureCache = Caffeine.newBuilder()
      .maximumSize(FEATURE_CACHE_MAX)
      .build();

  public RealtimeSequenceInputBuilder(String modelKey, FeatureContract contract) {
    this.modelKey = modelKey;
    this.contract = contract;
    this.inputFeatureOrder = contract.inputFeatures();
    this.seqLen = contract.totalWindow() > 0 ? contract.totalWindow() : 1;
    this.normalizer = new CanonicalPerspectiveNormalizer(inputFeatureOrder);
  }

  public float[][][] build(String sessionId, List<GameStateDto> alignedWindow, GameStateDto currentFrame) {
    int nFeatures = inputFeatureOrder.size();
    int windowLen = Math.min(alignedWindow.size(), seqLen);
    float[][][] input = new float[1][seqLen][nFeatures];

    List<GameStateDto> framesToEnrich = new ArrayList<>(Math.min(windowLen, FEATURE_CACHE_MAX));
    for (int t = alignedWindow.size() - windowLen; t < alignedWindow.size(); t++) {
      GameStateDto frame = alignedWindow.get(t);
      if (frame != null && featureCache.getIfPresent(frame.timestampMillis) == null) {
        framesToEnrich.add(frame.shallowCopyForEnrichment());
      }
    }
    if (!framesToEnrich.isEmpty()) {
      featureService.enrichIncrementalForRealTimePlay(sessionId, modelKey, framesToEnrich);
    }

    int offset = seqLen - windowLen;
    int enrichIdx = 0;
    for (int t = 0; t < windowLen; t++) {
      GameStateDto frame = alignedWindow.get(alignedWindow.size() - windowLen + t);
      if (frame == null) {
        continue;
      }

      float[] cached = featureCache.getIfPresent(frame.timestampMillis);
      if (cached != null) {
        System.arraycopy(cached, 0, input[0][offset + t], 0, nFeatures);
      } else {
        GameStateDto enriched = null;
        for (int e = enrichIdx; e < framesToEnrich.size(); e++) {
          if (framesToEnrich.get(e).timestampMillis == frame.timestampMillis) {
            enriched = framesToEnrich.get(e);
            enrichIdx = e + 1;
            break;
          }
        }
        if (enriched == null) {
          enriched = frame;
        }

        float[] resolved = new float[nFeatures];
        for (int f = 0; f < nFeatures; f++) {
          try {
            float v = featureService.resolveFeatureValueForRealTimePlay(
                sessionId, modelKey, enriched, inputFeatureOrder.get(f));
            resolved[f] = Float.isFinite(v) ? v : 0f;
          } catch (Exception e) {
            resolved[f] = 0f;
          }
        }
        // Normalize non-canonical team perspective (red→blue) before caching
        if (enriched.playerPawn != null
            && CanonicalPerspectiveNormalizer.needsNormalization(enriched.playerPawn.team)) {
          normalizer.normalize(resolved);
        }
        System.arraycopy(resolved, 0, input[0][offset + t], 0, nFeatures);
        featureCache.put(frame.timestampMillis, resolved);
      }
    }

    contract.applyTemporalMask(input);

    return input;
  }
}
