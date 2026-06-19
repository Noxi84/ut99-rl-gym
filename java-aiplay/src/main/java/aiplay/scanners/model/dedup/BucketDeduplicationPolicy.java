package aiplay.scanners.model.dedup;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureService;
import aiplay.scanners.model.sample.TrainingSample;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bucket-based deduplication: limits canonical samples per state bucket.
 * Extracted from the old writer shouldContinue() logic.
 *
 * Stateful — create one instance per session.
 */
public class BucketDeduplicationPolicy implements DeduplicationPolicy {

    private final TrainingFeatureService trainingFeatureService;
    private final List<String> stateBucketKey;
    private final int maxRowsPerBucket;
    private final Map<String, Integer> bucketCounts = new HashMap<>();

    public BucketDeduplicationPolicy(TrainingFeatureService trainingFeatureService,
                                     List<String> stateBucketKey,
                                     int maxRowsPerBucket) {
        this.trainingFeatureService = trainingFeatureService;
        this.stateBucketKey = stateBucketKey;
        this.maxRowsPerBucket = maxRowsPerBucket;
    }

    @Override
    public boolean shouldAccept(TrainingSample sample) {
        String bucketKey = buildBucketKey(sample);
        int count = bucketCounts.getOrDefault(bucketKey, 0);
        if (count >= maxRowsPerBucket) {
            return false;
        }
        bucketCounts.put(bucketKey, count + 1);
        return true;
    }

    private String buildBucketKey(TrainingSample sample) {
        GameStateDto lastFrame = sample.getLastFrame();

        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < stateBucketKey.size(); i++) {
            String featureId = stateBucketKey.get(i);
          if (i > 0) {
            sb.append('|');
          }
          String specialBucket = buildSpecialBucketKey(featureId, lastFrame);
          if (specialBucket != null) {
            sb.append(specialBucket);
            continue;
          }

            float v = safeResolve(sample, featureId, lastFrame);
            int decimals = bucketDecimals(featureId);
            float vr = round(v, decimals);
            sb.append(formatBucketNumber(vr, featureId, decimals));
        }
        return sb.toString();
    }

  private String buildSpecialBucketKey(String featureId, GameStateDto frame) {
    if (frame == null || featureId == null) {
      return null;
    }
    return switch (featureId) {
      case "missionBucket" -> "mission=" + (frame.annotatedMission != null ? frame.annotatedMission.name() : "NONE");
      case "engagementBucket" -> "engagement=" + (frame.annotatedEngagement != null ? frame.annotatedEngagement.name() : "NONE");
      default -> buildYawBucketKey(featureId, frame);
    };
  }

  private String buildYawBucketKey(String featureId, GameStateDto frame) {
    if (!featureId.startsWith("viewYawBucket")) {
      return null;
    }
    int buckets = parseYawBucketCount(featureId);
    int yaw = 0;
    if (frame.playerPawn != null && frame.playerPawn.viewRotation != null) {
      yaw = frame.playerPawn.viewRotation.x & 0xFFFF;
    }
    int bucket = (int) Math.floor((yaw / 65536.0) * buckets);
    if (bucket >= buckets) {
      bucket = buckets - 1;
    }
    return "yaw" + buckets + "=" + bucket;
  }

  private static int parseYawBucketCount(String featureId) {
    String suffix = featureId.substring("viewYawBucket".length());
    if (suffix.isEmpty()) {
      return 16;
    }
    try {
      int parsed = Integer.parseInt(suffix);
      return parsed > 0 ? parsed : 16;
    } catch (NumberFormatException ex) {
      return 16;
    }
  }

    private float safeResolve(TrainingSample sample, String featureId, GameStateDto frame) {
        try {
            Float resolved = trainingFeatureService.resolveCsvWriterFeatureValue(
                    sample.getModelKey(), sample.getSessionId(), featureId,
                    sample.getSessionFrames(), frame);
            float v = (resolved != null) ? resolved : 0f;
            return Float.isFinite(v) ? v : 0f;
        } catch (Exception ex) {
            return 0f;
        }
    }

    private int bucketDecimals(String featureId) {
        if (featureId == null) {
            return 4;
        }
        if ("self_locationX".equals(featureId) || "self_locationY".equals(featureId) || "self_locationZ".equals(featureId)) {
            return 0;
        }
        if (featureId.endsWith("_norm")) {
            return 4;
        }
        if ("self_viewRotationX_sin".equals(featureId) || "self_viewRotationX_cos".equals(featureId)) {
            return 4;
        }
        if (featureId.endsWith("Collision_norm")) {
            return 3;
        }
        return 4;
    }

    private String formatBucketNumber(float v, String featureId, int decimals) {
        if (trainingFeatureService.isBooleanFeature(featureId)) {
            return Integer.toString((int) Math.round(v));
        }
        String fmt = "%." + decimals + "f";
        return String.format(java.util.Locale.ROOT, fmt, v);
    }

    private float round(float v, int decimals) {
        double p = Math.pow(10.0d, decimals);
        return (float) (Math.round(v * p) / p);
    }
}
