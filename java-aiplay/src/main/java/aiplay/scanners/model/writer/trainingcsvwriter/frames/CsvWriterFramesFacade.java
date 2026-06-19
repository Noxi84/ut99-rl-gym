package aiplay.scanners.model.writer.trainingcsvwriter.frames;

import aiplay.runtime.context.ActiveMapContext;
import aiplay.dto.GameStateDto;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.rl.MovementPrimitive;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public class CsvWriterFramesFacade {

    private final CsvWriterFramesService csvWriterFramesService = new CsvWriterFramesService();
    private final TrainingFeatureService trainingFeatureService = TrainingFeatureService.shared();

    public LinkedHashMap<Integer, List<GameStateDto>> groupByElapsedTime(String sessionId,
                                                                         String modelKey,
                                                                         List<GameStateDto> gameStates,
                                                                         int minFps,
                                                                         int preferredFps) {

        Logger log = SessionRollingLogger.get(sessionId, SessionLogPaths.framesLog());

        if (gameStates == null) {
            log.warning("FRAMES groupByElapsedTime called with gameStates=null");
            return new LinkedHashMap<Integer, List<GameStateDto>>();
        }

        log.info("FRAMES start size=" + gameStates.size()
                + " minFps=" + minFps
                + " preferredFps=" + preferredFps);

      long minNoMoveMs = shouldFilterNoMovement(modelKey) ? 400L : -1L;
      int[] filterStats = filterAndSort(sessionId, log, gameStates, 1000L, minNoMoveMs);
        int removedNull = filterStats[0];
        int removedDead = filterStats[1];
        int removedNoMove = filterStats[2];

        log.info("FRAMES after filters size=" + gameStates.size()
                + " removedNull=" + removedNull
                + " removedDead=" + removedDead
                + " removedRespawn=" + (removedDead < 0 ? "?" : "see above")
                + " removedNoMove=" + removedNoMove);

        // batch enrich (features like holds, deltas, etc.)
        // ReaderFacade only sets ActiveMapContext during DTO conversion and
        // clears it on return, so enrichers like JumpPadEnricher would fall
        // back to gameplay.mapName here. Re-set it from the frames so
        // MapJumpPadsResolver and friends resolve against the correct map.
        String mapForBatch = resolveMapNameFromFrames(gameStates);
        String prevMapContext = ActiveMapContext.get();
        try {
            if (mapForBatch != null) ActiveMapContext.set(mapForBatch);
            trainingFeatureService.enrichBatchForCsvWriter(sessionId, modelKey, gameStates);
        } finally {
            if (prevMapContext == null) ActiveMapContext.clear();
            else ActiveMapContext.set(prevMapContext);
        }

        // groepeer op seconde (op basis van timestamps)
        LinkedHashMap<Integer, List<GameStateDto>> groupedByElapsedTime = csvWriterFramesService.groupByElapsedTimeFromTimestamps(gameStates, minFps, preferredFps);

        logGroupedStats(log, groupedByElapsedTime);

        // ✅ Verrijk met framenummers (en evt extra)
        enrichPropertiesFor1Second(sessionId, log, groupedByElapsedTime);

        // ✅ Duplicaten buckets verwijderen
        int dupRemoved = removeDuplicateFrames(sessionId, log, groupedByElapsedTime);

        log.info("FRAMES done buckets=" + groupedByElapsedTime.size() + " removedDuplicateFrames=" + dupRemoved);

        return groupedByElapsedTime;
    }

  private static boolean shouldFilterNoMovement(String modelKey) {
    // Joint VR+shooting policy: needs idle frames intact for the bIdle target
    // and for view rotation BC supervision (natural idle = leerstof, not noise).
    String jointModelKey = ModelRoleRegistry.shared().getModelKey(ModelRole.PAWN_POLICY);
    if (jointModelKey != null && jointModelKey.equalsIgnoreCase(modelKey)) {
      return false;
    }
    return true;
  }

    // ===================== FILTERS =====================

    /**
     * Combined filter: sort once, then apply null/dead/respawn/noMovement filters in a single pass.
     * @return int[3] = {removedNull, removedDead, removedNoMove}
     */
    private int[] filterAndSort(String sessionId, Logger log, List<GameStateDto> gameStates,
                                 long respawnCooldownMs, long minNoMoveMs) {
        if (gameStates == null || gameStates.isEmpty()) return new int[]{0, 0, 0};

        // Remove nulls first (can't sort nulls meaningfully)
        int beforeNull = gameStates.size();
        gameStates.removeIf(Objects::isNull);
        int removedNull = beforeNull - gameStates.size();
        if (removedNull > 0) {
            log.info("FRAMES filter removeNulls removed=" + removedNull + " before=" + beforeNull + " after=" + gameStates.size());
        }

        if (gameStates.isEmpty()) return new int[]{removedNull, 0, 0};

        // Single sort on timestamp + frameNumber
        gameStates.sort((a, b) -> {
            long ta = a.timestampMillis;
            long tb = b.timestampMillis;
            if (ta == tb) {
                return Integer.compare(a.frameNumber, b.frameNumber);
            }
            return Long.compare(ta, tb);
        });

        int size = gameStates.size();
        boolean[] keep = new boolean[size];

        // Pass 1: dead + respawn cooldown
        boolean wasDead = false;
        long respawnWindowEnd = -1L;
        long lastDeathTs = -1L;
        int removedDead = 0;
        int removedRespawn = 0;

        for (int i = 0; i < size; i++) {
            GameStateDto gs = gameStates.get(i);
            boolean dead = gs.playerPawn != null && gs.playerPawn.health <= 0;
            long now = gs.timestampMillis;

            if (dead) {
                keep[i] = false;
                removedDead++;
                wasDead = true;
                lastDeathTs = now;
                respawnWindowEnd = -1L;
            } else {
                if (wasDead) {
                    respawnWindowEnd = now + respawnCooldownMs;
                    wasDead = false;
                }
                if (respawnWindowEnd > 0 && now < respawnWindowEnd) {
                    keep[i] = false;
                    removedRespawn++;
                } else {
                    keep[i] = true;
                }
            }
        }

        int removedDeadTotal = removedDead + removedRespawn;
        if (removedDeadTotal > 0) {
            log.info("FRAMES filter removeDeadAndRespawnCooldown removedTotal=" + removedDeadTotal
                    + " removedDead=" + removedDead
                    + " removedRespawn=" + removedRespawn
                    + " respawnCooldownMs=" + respawnCooldownMs
                    + " before=" + size
                    + " after=" + (size - removedDeadTotal)
                    + (lastDeathTs > 0 ? (" lastDeathTs=" + lastDeathTs) : ""));
        }

      int removedNoMove = 0;
      boolean[] keepNoMove = new boolean[size];
      System.arraycopy(keep, 0, keepNoMove, 0, size);
      if (minNoMoveMs >= 0L) {
        // Pass 2: no-movement filter (on frames that survived pass 1)
        // Mark idle blocks >= minNoMoveMs for removal
        long blockStartTs = -1L;
        int blockStartIdx = -1;

        for (int i = 0; i < size; i++) {
          if (!keep[i]) {
            continue; // already removed by dead/respawn
          }

          GameStateDto gs = gameStates.get(i);
          if (gs.playerPawn == null || gs.playerPawn.playerPawn == null) {
            keepNoMove[i] = false;
            continue;
          }

          boolean hasMove = MovementPrimitive.fromGameState(gs) != MovementPrimitive.IDLE;

          if (!hasMove) {
            if (blockStartTs < 0) {
              blockStartTs = gs.timestampMillis;
              blockStartIdx = i;
            }
            keepNoMove[i] = false;
          } else {
            if (blockStartTs >= 0) {
              long duration = gs.timestampMillis - blockStartTs;
              if (duration < minNoMoveMs) {
                // Re-keep short idle blocks
                for (int j = blockStartIdx; j < i; j++) {
                  if (keep[j]) { // only re-keep if not already removed by dead/respawn
                    keepNoMove[j] = true;
                  }
                }
              }
              blockStartTs = -1L;
              blockStartIdx = -1;
            }
            keepNoMove[i] = true;
          }
        }

        // Handle trailing idle block
        if (blockStartTs >= 0) {
          long lastTs = gameStates.get(size - 1).timestampMillis;
          long duration = lastTs - blockStartTs;
          if (duration < minNoMoveMs) {
            for (int j = blockStartIdx; j < size; j++) {
              if (keep[j]) {
                keepNoMove[j] = true;
              }
                    }
                }
            }
        }

        // Compact using final keepNoMove
        int beforeCompact = gameStates.size();
        int write = 0;
        for (int read = 0; read < size; read++) {
            if (keepNoMove[read]) {
                gameStates.set(write++, gameStates.get(read));
            }
        }
        while (gameStates.size() > write) gameStates.remove(gameStates.size() - 1);

        removedNoMove = (beforeNull - removedNull - removedDeadTotal) - gameStates.size();
        // Correct: removedNoMove is everything removed beyond null+dead
        removedNoMove = beforeCompact - removedDeadTotal - removedNull - gameStates.size();
        // Simpler: total removed minus what null and dead removed
        removedNoMove = (beforeNull - removedNull) - removedDeadTotal - gameStates.size();

        if (removedNoMove > 0) {
            log.info("FRAMES filter removeNoMovement removed=" + removedNoMove
                    + " minNoMoveMs=" + minNoMoveMs
                    + " before=" + (beforeNull - removedNull - removedDeadTotal)
                    + " after=" + gameStates.size());
        } else if (minNoMoveMs < 0L) {
          log.info("FRAMES filter removeNoMovement skipped model retains idle view frames");
        }

        return new int[]{removedNull, removedDeadTotal, removedNoMove};
    }

    // ===================== ENRICH + DEDUP =====================

    private static String resolveMapNameFromFrames(List<GameStateDto> frames) {
        if (frames == null) return null;
        for (GameStateDto f : frames) {
            if (f != null && f.mapInfo != null && f.mapInfo.mapName != null
                    && !f.mapInfo.mapName.isBlank()) {
                return f.mapInfo.mapName;
            }
        }
        return null;
    }

    public void enrichFrameNumbers(List<GameStateDto> frames) {
        int i = 0;
        for (GameStateDto f : frames) f.frameNumber = i++;
    }

    private void enrichPropertiesFor1Second(String sessionId, Logger log, LinkedHashMap<Integer, List<GameStateDto>> groupedByElapsedTime) {
        int bucketsTouched = 0;
        int framesTouched = 0;

        for (Map.Entry<Integer, List<GameStateDto>> entry : groupedByElapsedTime.entrySet()) {
            List<GameStateDto> gameStates = entry.getValue();
            if (gameStates == null || gameStates.size() <= 1) continue;

            bucketsTouched++;
            framesTouched += gameStates.size();

            enrichFrameNumbers(gameStates);
        }

        log.info("FRAMES enrichPropertiesFor1Second bucketsTouched=" + bucketsTouched + " framesTouched=" + framesTouched);
    }

    /**
     * @return removed frames count (sum of removed buckets sizes)
     */
    private int removeDuplicateFrames(String sessionId, Logger log, LinkedHashMap<Integer, List<GameStateDto>> grouped) {
        int totalRemovedFrames = 0;
        int removedBuckets = 0;

        var it = grouped.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<GameStateDto>> entry = it.next();
            List<GameStateDto> gs = entry.getValue();

            if (gs == null || gs.isEmpty()) {
                it.remove();
                continue;
            }

            int beforeNulls = gs.size();
            gs.removeIf(Objects::isNull);
            int nullsRemoved = beforeNulls - gs.size();
            if (nullsRemoved > 0) {
                log.info("FRAMES bucket=" + entry.getKey() + " removedNullsInsideBucket=" + nullsRemoved);
            }

            if (gs.isEmpty()) {
                it.remove();
                continue;
            }

            if (gs.size() < 2) continue;

            GameStateDto first = gs.getFirst();
            if (first == null ||
                    first.playerPawn == null ||
                    first.playerPawn.viewRotation == null ||
                    first.playerPawn.location == null) {
                continue;
            }

            boolean allIdentical = gs.stream().allMatch(g ->
                    g != null &&
                            g.playerPawn != null &&
                            g.playerPawn.viewRotation != null &&
                            g.playerPawn.location != null &&
                            g.playerPawn.viewRotation.x == first.playerPawn.viewRotation.x &&
                            g.playerPawn.viewRotation.y == first.playerPawn.viewRotation.y &&
                            g.playerPawn.location.x == first.playerPawn.location.x &&
                            g.playerPawn.location.y == first.playerPawn.location.y &&
                            g.playerPawn.location.z == first.playerPawn.location.z
            );

            if (allIdentical) {
                totalRemovedFrames += gs.size();
                removedBuckets++;
                it.remove();
            }
        }

        if (totalRemovedFrames > 0) {
            log.info("FRAMES filter removeDuplicateFrames removedFrames=" + totalRemovedFrames + " removedBuckets=" + removedBuckets);
        }
        return totalRemovedFrames;
    }

    // ===================== STATS =====================

    private void logGroupedStats(Logger log, LinkedHashMap<Integer, List<GameStateDto>> grouped) {
        if (grouped == null) {
            log.warning("FRAMES grouped=null");
            return;
        }

        int buckets = grouped.size();
        int frames = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;

        int preview = 0;
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Integer, List<GameStateDto>> e : grouped.entrySet()) {
            List<GameStateDto> list = e.getValue();
            int n = (list != null ? list.size() : 0);
            frames += n;
            if (n < min) min = n;
            if (n > max) max = n;

            if (preview < 8) {
                sb.append(e.getKey()).append("→").append(n).append(" ");
                preview++;
            }
        }

        if (min == Integer.MAX_VALUE) min = 0;

        log.info("FRAMES grouped buckets=" + buckets
                + " frames=" + frames
                + " perBucket[min=" + min + ",max=" + max + ",avg=" + avg(frames, buckets) + "]"
                + " preview=" + sb.toString().trim());
    }

    private static String avg(int total, int buckets) {
        if (buckets <= 0) return "0.00";
        double v = ((double) total) / ((double) buckets);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
