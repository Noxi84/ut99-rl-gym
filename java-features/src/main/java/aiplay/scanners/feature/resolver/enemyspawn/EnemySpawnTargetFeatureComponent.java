package aiplay.scanners.feature.resolver.enemyspawn;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.scanners.feature.resolver.enemy.EnemySlotRelativeBatchEnricher;
import aiplay.shared.view.EnemySpawnTargeting;
import aiplay.shared.view.ViewTargeting;
import aiplay.util.NormalizationUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enemy-spawn aim context for the all-enemies-dead window.
 *
 * <p>Priority 11: runs after player/enemy/map DTO conversion and after the normal aim-target
 * enricher. The features are zero outside the all-dead window.</p>
 */
@TrainingFeatureComponent(priority = 11)
public class EnemySpawnTargetFeatureComponent implements ITrainingFeature {

  private static final Set<String> FEATURE_IDS = Set.of(
      "enemySpawnTarget_active",
      "enemySpawnTarget_relSin",
      "enemySpawnTarget_relCos",
      "enemySpawnTarget_distance_norm",
      "enemySpawnTarget_forwardDist_norm",
      "enemySpawnTarget_rightDist_norm",
      "enemySpawnTarget_pitchBearing_norm",
      "enemySpawnTarget_pitchError_norm"
  );

  private static final Set<String> BOOLEAN_FEATURES = Set.of("enemySpawnTarget_active");

  private final TrainingFeatureValueResolver resolver = EnemySpawnTargetFeatureComponent::resolve;
  private final TrainingFeatureEnricher enricher = new EnemySpawnTargetEnricher();

  @Override
  public Set<String> getFeatureIds() {
    return FEATURE_IDS;
  }

  @Override
  public Set<String> getBooleanFeatures() {
    return BOOLEAN_FEATURES;
  }

  @Override
  public TrainingFeatureValueResolver getTrainingFeatureValueResolver() {
    return resolver;
  }

  @Override
  public TrainingFeatureEnricher getTrainingFeatureEnricher() {
    return enricher;
  }

  private static Float resolve(String featureId, GameStateDto frame) {
    CoordinatesDto target = resolveTarget(frame);
    if ("enemySpawnTarget_active".equals(featureId)) {
      return target != null ? 1.0f : 0.0f;
    }
    if (target == null
        || frame == null
        || frame.playerPawn == null
        || frame.playerPawn.location == null
        || frame.playerPawn.viewRotation == null) {
      return 0.0f;
    }

    PlayerRelationDto rel = EnemySlotRelativeBatchEnricher.buildRelation(
        frame.playerPawn.location, frame.playerPawn.viewRotation.x & 0xFFFF, target);
    return switch (featureId) {
      case "enemySpawnTarget_relSin" -> finite(rel.relSin);
      case "enemySpawnTarget_relCos" -> finite(rel.relCos);
      case "enemySpawnTarget_distance_norm" -> finite(rel.distance_norm);
      case "enemySpawnTarget_forwardDist_norm" -> finite(rel.forwardDist_norm);
      case "enemySpawnTarget_rightDist_norm" -> finite(rel.rightDist_norm);
      case "enemySpawnTarget_pitchBearing_norm" -> finite(
          EnemySlotRelativeBatchEnricher.computePitchBearingNorm(
              frame.playerPawn.location, frame.playerPawn.baseEyeHeight, target, 0.0));
      case "enemySpawnTarget_pitchError_norm" -> finite(NormalizationUtils.clampM11(
          ViewTargeting.computePitchNormToward(frame, target)
              - ViewTargeting.extractCurrentPitchNorm(frame)));
      default -> null;
    };
  }

  private static CoordinatesDto resolveTarget(GameStateDto frame) {
    if (frame == null) {
      return null;
    }
    if (frame.annotatedEnemySpawnTarget != null) {
      return frame.annotatedEnemySpawnTarget;
    }
    return EnemySpawnTargeting.resolveAimPoint(frame);
  }

  private static float finite(double value) {
    return Double.isFinite(value) ? (float) value : 0.0f;
  }

  public static final class EnemySpawnTargetEnricher implements TrainingFeatureEnricher {
    private static final ConcurrentHashMap<String, RuntimeState> RUNTIME_STATES =
        new ConcurrentHashMap<>();

    public static void unregisterSession(String sessionId) {
      if (sessionId != null) {
        RUNTIME_STATES.remove(sessionId);
      }
    }

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
      if (frames == null || frames.isEmpty()) {
        return;
      }
      EnemySpawnTargeting.TargetState state = new EnemySpawnTargeting.TargetState();
      for (GameStateDto frame : frames) {
        if (frame == null) {
          continue;
        }
        frame.annotatedEnemySpawnTarget = EnemySpawnTargeting.resolveAimPoint(
            frame, state, EnemySpawnTargeting.DEFAULT_HOLD_TICKS);
      }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
      if (frames == null || frames.isEmpty()) {
        return;
      }
      RuntimeState runtimeState = RUNTIME_STATES.computeIfAbsent(sessionId, k -> new RuntimeState());
      int startIndex = findFirstIndexAfterTimestamp(frames, runtimeState.lastTs);
      if (startIndex < 0) {
        return;
      }
      for (int i = startIndex; i < frames.size(); i++) {
        GameStateDto frame = frames.get(i);
        if (frame == null) {
          continue;
        }
        frame.annotatedEnemySpawnTarget = EnemySpawnTargeting.resolveAimPoint(
            frame, runtimeState.targetState, EnemySpawnTargeting.DEFAULT_HOLD_TICKS);
        runtimeState.lastTs = frame.timestampMillis;
      }
    }

    private static int findFirstIndexAfterTimestamp(List<GameStateDto> frames, long lastTs) {
      if (lastTs == Long.MIN_VALUE) {
        return 0;
      }
      for (int i = 0; i < frames.size(); i++) {
        GameStateDto frame = frames.get(i);
        if (frame != null && frame.timestampMillis > lastTs) {
          return i;
        }
      }
      return -1;
    }

    private static final class RuntimeState {
      private final EnemySpawnTargeting.TargetState targetState = new EnemySpawnTargeting.TargetState();
      private long lastTs = Long.MIN_VALUE;
    }
  }
}
