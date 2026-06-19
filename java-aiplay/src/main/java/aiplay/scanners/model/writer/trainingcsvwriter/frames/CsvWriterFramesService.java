package aiplay.scanners.model.writer.trainingcsvwriter.frames;

import aiplay.dto.GameStateDto;
import aiplay.dto.KeyboardMoveDto;
import aiplay.rl.MovementPrimitive;

import java.util.*;
import java.util.stream.Collectors;

public class CsvWriterFramesService {

    public LinkedHashMap<Integer, List<GameStateDto>> groupByElapsedTimeFromTimestamps(List<GameStateDto> gameStates,
                                                                                       int minNumberOfFrames,
                                                                                       int preferredNumberOfFrames) {
        int numberOfFrames = preferredNumberOfFrames + 1; // behoud je huidige gedrag
        LinkedHashMap<Integer, List<GameStateDto>> groupedByElapsedTime = new LinkedHashMap<Integer, List<GameStateDto>>();

        if (gameStates.isEmpty()) return groupedByElapsedTime;

        long startTimestamp = gameStates.get(0).timestampMillis;

        for (GameStateDto gameState : gameStates) {
            long elapsedMillis = gameState.timestampMillis - startTimestamp;
            int elapsedSeconds = (int) (elapsedMillis / 1000);
            groupedByElapsedTime
                    .computeIfAbsent(elapsedSeconds, k -> new ArrayList<GameStateDto>())
                    .add(gameState);
        }

        groupedByElapsedTime.replaceAll((key, value) ->
                balancedSelectForMovement(value, movementSpecs(), numberOfFrames)
        );

        groupedByElapsedTime.entrySet().removeIf(entry -> entry.getValue().size() < minNumberOfFrames);
        return groupedByElapsedTime;
    }

    // ===============================================================
    // Balanced selection for movement properties
    // ===============================================================

    /**
     * Balanceer frames binnen 1s-bucket: per property kies start/mid/end uit elk actief segment.
     * Daarna merge je alles, dedupliceer je op timestamp en vul je desnoods aan tot target.
     */
    public List<GameStateDto> balancedSelectForMovement(List<GameStateDto> frames,
                                                        List<PropertySpec> properties,
                                                        int targetFrames) {
        if (frames == null || frames.isEmpty()) return Collections.emptyList();

        // 1) Sorteer op tijd
        List<GameStateDto> sorted = frames.stream()
                .sorted(Comparator.comparingLong(f -> f.timestampMillis))
                .collect(Collectors.toList());

        // 2) Verzamel geselecteerde frames in insertion-ordered set (timestamp als uniqueness key)
        LinkedHashMap<Long, GameStateDto> chosen = new LinkedHashMap<Long, GameStateDto>();

        for (PropertySpec prop : properties) {
            collectSegmentsAndPick(sorted, prop, chosen);
        }

        // 3) Als te weinig gekozen → vul aan met gelijkmatig verdeelde "neutrale" frames
        List<GameStateDto> merged = new ArrayList<GameStateDto>(chosen.values());
        if (merged.size() < targetFrames) {
            List<GameStateDto> filler = evenlyDistribute(sorted, new HashSet<Long>(chosen.keySet()), targetFrames - merged.size());
            merged.addAll(filler);
            Collections.sort(merged, Comparator.comparingLong(f -> f.timestampMillis));
        }

        // 4) Als te veel gekozen → knip terug op gelijke intervallen
        if (merged.size() > targetFrames) {
            merged = trimEvenly(merged, targetFrames);
        }

        return merged;
    }

    // --- Helpers ---

    private void collectSegmentsAndPick(List<GameStateDto> sorted,
                                        PropertySpec prop,
                                        LinkedHashMap<Long, GameStateDto> out) {
        // Vind aaneengesloten segmenten waar isActive==true
        int n = sorted.size();
        int i = 0;
        while (i < n) {
            // Skip inactive
            while (i < n && !prop.isActive.test(sorted.get(i))) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && prop.isActive.test(sorted.get(i))) i++;
            int end = i - 1;

            // Kies start/mid/end op basis van index (robuust & snel)
            int len = end - start + 1;
            if (len == 1) {
                addIfPresent(sorted, start, out);
            } else if (len == 2) {
                addIfPresent(sorted, start, out);
                addIfPresent(sorted, end, out);
            } else {
                int mid = start + (len / 2);
                addIfPresent(sorted, start, out);
                addIfPresent(sorted, mid, out);
                addIfPresent(sorted, end, out);
            }
        }
    }

    private void addIfPresent(List<GameStateDto> sorted, int idx, LinkedHashMap<Long, GameStateDto> out) {
        if (idx < 0 || idx >= sorted.size()) return;
        GameStateDto f = sorted.get(idx);
        out.putIfAbsent(f.timestampMillis, f);
    }

    private List<GameStateDto> evenlyDistribute(List<GameStateDto> sorted,
                                                Set<Long> already,
                                                int need) {
        if (need <= 0) return Collections.emptyList();
        List<GameStateDto> pool = new ArrayList<GameStateDto>();
        for (GameStateDto f : sorted) {
            if (!already.contains(f.timestampMillis)) pool.add(f);
        }
        if (pool.isEmpty()) return Collections.emptyList();

        int take = Math.min(need, pool.size());
        List<GameStateDto> picked = new ArrayList<GameStateDto>(take);

        long min = pool.get(0).timestampMillis;
        long max = pool.get(pool.size() - 1).timestampMillis;
        long total = Math.max(1L, max - min);

        int idx = 0;
        for (int i = 0; i < take; i++) {
            long target = (take == 1) ? (min + total / 2) : (min + i * total / (long) (take - 1));
            GameStateDto best = pool.get(idx);
            long bestDiff = Math.abs(best.timestampMillis - target);
            while (idx + 1 < pool.size()) {
                long nextDiff = Math.abs(pool.get(idx + 1).timestampMillis - target);
                if (nextDiff < bestDiff) {
                    idx++;
                    best = pool.get(idx);
                    bestDiff = nextDiff;
                } else break;
            }
            picked.add(best);
            already.add(best.timestampMillis);
        }
        picked.sort(Comparator.comparingLong(f -> f.timestampMillis));
        return picked;
    }

    private List<GameStateDto> trimEvenly(List<GameStateDto> in, int target) {
        if (in.size() <= target) return in;
        if (target <= 1) return Collections.singletonList(in.get(in.size() / 2));
        List<GameStateDto> out = new ArrayList<GameStateDto>(target);
        int n = in.size();
        for (int i = 0; i < target; i++) {
            int idx = (int) Math.round(i * (n - 1) / (double) (target - 1));
            out.add(in.get(idx));
        }
        return out;
    }

    // ————————————————————————————————————————————————————————————————
    // Property mapping naar DTO's
    // ————————————————————————————————————————————————————————————————
    private static boolean isDown(KeyboardMoveDto k) {
        return k != null && k.value;
    }

    private List<PropertySpec> movementSpecs() {
        List<PropertySpec> specs = new ArrayList<PropertySpec>();

        for (MovementPrimitive mp : MovementPrimitive.LOCOMOTION_VALUES) {
            specs.add(new PropertySpec(
                    mp.getFeatureId(),
                    f -> f.playerPawn != null
                            && f.playerPawn.playerPawn != null
                            && MovementPrimitive.fromGameState(f) == mp
            ));
        }
        specs.add(new PropertySpec(
                "jump",
                f -> f.playerPawn != null
                        && f.playerPawn.playerPawn != null
                        && isDown(f.playerPawn.playerPawn.bJump)
        ));
        specs.add(new PropertySpec(
                "duck",
                f -> f.playerPawn != null
                        && isDown(f.playerPawn.bDuck)
        ));

        return specs;
    }
}
