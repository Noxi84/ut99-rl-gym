package aiplay.scanners.feature.resolver.enemy;

import aiplay.runtime.config.CoordinatesConverter;
import aiplay.dto.CoordinatesDto;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Classifies players into enemy/teammate slots sorted by 2D distance
 * (closest = slot 0, next = slot 1, ...).
 *
 * <p>With Necto-style permutation-invariant pooling in the model, slot order
 * is architecturally irrelevant — the shared encoder + mean/max pool collapse
 * over slots and produce identical output regardless of which player occupies
 * which slot. Distance-sort is kept only because it keeps the CSV feature
 * layout stable across frames (closest→slot 0 is a simple deterministic rule)
 * and avoids jitter in downstream tools (loggers, dashboards) that may still
 * read per-slot fields.</p>
 *
 * <p>Hysteresis was removed because (a) it introduced per-session state that
 * served no correctness purpose once pooling is permutation-invariant, and
 * (b) it could cause stale slot assignments (a far-away "incumbent" would
 * keep its slot while a nearer threat was pushed to a later slot).</p>
 */
public class PlayerSlotConverter {

    public static final int MAX_ENEMY_SLOTS = 7;
    public static final int MAX_TEAMMATE_SLOTS = 7;

    private final CoordinatesConverter coordinatesConverter = new CoordinatesConverter();

    /** Result of slot classification. */
    public record SlotResult(
        /** Sorted enemies (index 0 = closest). Null entries = empty slot. */
        Player[] enemies,
        /** Sorted teammates (index 0 = closest). Null entries = empty slot. */
        Player[] teammates
    ) {}

    /**
     * Classify all players from the game state into enemy/teammate slots.
     *
     * @param gs     raw game state from webservice
     * @param aiName the bot's own player name
     * @return slot assignment result
     */
    public SlotResult classify(GameState gs, String aiName) {
        Player[] enemySlots = new Player[MAX_ENEMY_SLOTS];
        Player[] teammateSlots = new Player[MAX_TEAMMATE_SLOTS];

        if (gs == null || gs.Players == null || aiName == null) {
            return new SlotResult(enemySlots, teammateSlots);
        }

        Player self = null;
        for (Player p : gs.Players) {
            if (p != null && p.Name != null && p.Name.equalsIgnoreCase(aiName)) {
                self = p;
                break;
            }
        }
        if (self == null) {
            return new SlotResult(enemySlots, teammateSlots);
        }

        CoordinatesDto selfLoc = coordinatesConverter.convert(self.Location);
        int selfTeam = parseIntSafe(self.Team);
        if (selfLoc == null) {
            return new SlotResult(enemySlots, teammateSlots);
        }

        List<PlayerWithDistance> enemies = new ArrayList<>();
        List<PlayerWithDistance> teammates = new ArrayList<>();

        for (Player p : gs.Players) {
            if (p == null || p.Name == null || p.Location == null) continue;
            if (p.Name.equalsIgnoreCase(aiName)) continue;

            CoordinatesDto loc = coordinatesConverter.convert(p.Location);
            if (loc == null) continue;

            double dx = loc.x - selfLoc.x;
            double dy = loc.y - selfLoc.y;
            double dist2D = Math.sqrt(dx * dx + dy * dy);

            int t = parseIntSafe(p.Team);
            if (t != selfTeam) {
                enemies.add(new PlayerWithDistance(p, dist2D));
            } else {
                teammates.add(new PlayerWithDistance(p, dist2D));
            }
        }

        enemies.sort(Comparator.comparingDouble(pwd -> pwd.dist));
        teammates.sort(Comparator.comparingDouble(pwd -> pwd.dist));

        fillSlots(enemies, enemySlots);
        fillSlots(teammates, teammateSlots);

        return new SlotResult(enemySlots, teammateSlots);
    }

    /** Fill slots 0..N with the closest candidates in order. No hysteresis. */
    private static void fillSlots(List<PlayerWithDistance> candidates, Player[] slots) {
        int n = Math.min(candidates.size(), slots.length);
        for (int i = 0; i < n; i++) {
            slots[i] = candidates.get(i).player;
        }
    }

    private record PlayerWithDistance(Player player, double dist) {}

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
