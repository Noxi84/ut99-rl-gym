package aiplay.shared.engagement;

import aiplay.config.global.AimTargetConfig;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.view.ViewTargeting;

import java.util.function.Function;

/**
 * Sticky aim-target selectie-policy: kiest welke vijand de bot blijft aanvallen, zodat alle
 * aim-target lezers (heading-ref, bearing-input, lookahead, reward) op een frame dezelfde enemy
 * zien — voorheen verschilde dat per pad omdat slot-0-distance, sticky-closest en het
 * deterministische resolver-pad onafhankelijk werkten.
 *
 * <p>Dit is de pure policy: gegeven een frame, de {@link AimTargetState} van de aanroeper en de
 * {@link AimTargetConfig}, levert {@link #select} de gekozen vijand plus zijn slot-index. De
 * state-levensduur (verse state per offline batch versus per-sessie persistent voor live) is een
 * zorg van de aanroeper (de feature-pipeline adapter), niet van de policy.
 *
 * <p>Selectie is attention-type-aware: leest {@code frame.annotatedAttentionTarget} (gezet door
 * {@code MissionAnnotationFeatureEnricher} priority 3, de adapter draait op priority 5):
 * <ul>
 *   <li>{@code ENEMY_PLAYER}: sticky-closest. Voor rol {@code Attack} geldt een
 *       hard-pin variant — alleen wisselen op death/missing/unseenForceSwitchMs, geen
 *       distance-ratio switch. DeathMatch/Cover-fallback gebruiken de bestaande
 *       {@link AimTargetConfig} hysterese.</li>
 *   <li>{@code ENEMY_CARRIER}: deterministisch — de enemy met {@code hasFlag}. Geen
 *       hysterese (carrier is altijd uniek).</li>
 *   <li>{@code ENEMY_NEAREST_TO_HOME_FLAG}: sticky-deterministic. Picker = enemy met
 *       kortste 2D-afstand tot eigen vlag-locatie. {@code minCommitMs} hysterese voorkomt
 *       per-tick flipping wanneer 2 enemies vergelijkbaar dichtbij staan.</li>
 *   <li>{@code ENEMY_NEAREST_TO_ATTACKER}: zelfde sticky-deterministic, picker = enemy
 *       met kortste 2D-afstand tot teammate met rol {@code Attack}.</li>
 *   <li>OBJECTIVE / NONE / null: clear sticky state, write null. Engagement-gated rewards
 *       en bearing-selector vallen dan terug op objective bearing.</li>
 * </ul>
 */
public final class AimTargetSelector {

    private AimTargetSelector() {
    }

    /**
     * Kiest het aim-target voor dit frame en muteert {@code state} naar de nieuwe sticky-toestand.
     * Retourneert de gekozen vijand plus zijn slot-index in {@code frame.enemies}
     * ({@link AimTargetSelection#NONE} wanneer er geen doelwit is).
     */
    public static AimTargetSelection select(GameStateDto frame, AimTargetState state, AimTargetConfig cfg) {
        PlayerDto chosen = resolve(frame, state, cfg);
        if (chosen == null) {
            return AimTargetSelection.NONE;
        }
        return new AimTargetSelection(chosen, slotIndexOf(frame, chosen));
    }

    private static int slotIndexOf(GameStateDto frame, PlayerDto chosen) {
        if (chosen == null || chosen.name == null || frame == null || frame.enemies == null) {
            return -1;
        }
        for (int i = 0; i < frame.enemies.length; i++) {
            PlayerDto e = frame.enemies[i];
            if (e != null && chosen.name.equals(e.name)) {
                return i;
            }
        }
        return -1;
    }

    private static PlayerDto resolve(GameStateDto frame, AimTargetState state, AimTargetConfig cfg) {
        AttentionTargetType att = (frame.annotatedAttentionTarget != null)
            ? frame.annotatedAttentionTarget
            : AttentionTargetType.ENEMY_PLAYER;
        long ts = frame.timestampMillis;
        switch (att) {
            case ENEMY_PLAYER:
                return resolveEnemyPlayer(frame, state, cfg);
            case ENEMY_CARRIER:
                return resolveCarrier(frame, state, ts);
            case ENEMY_NEAREST_TO_HOME_FLAG:
                return resolveStickyDeterministic(frame, state, cfg, AimTargetSelector::pickNearestToHomeFlag);
            case ENEMY_NEAREST_TO_ATTACKER:
                return resolveStickyDeterministic(frame, state, cfg, AimTargetSelector::pickNearestToAttacker);
            case ENEMY_THREAT_TO_SELF:
                return resolveStickyDeterministic(frame, state, cfg, AimTargetSelector::pickMostThreateningToSelf);
            case OBJECTIVE_ENEMY_FLAG:
            case OBJECTIVE_HOME_BASE:
            case OBJECTIVE_HOME_FLAG:
            case NONE:
            default:
                state.currentName = null;
                state.committedAtTs = ts;
                state.lastSeenVisibleTs = ts;
                return null;
        }
    }

    /**
     * Sticky closest enemy — original behavior. For role=Attack the distance-ratio switch is
     * disabled (hard-pin until death/missing/long-unseen), per the user-specified semantics
     * "kies dichtstbijzijnde speler en blijf vastgepind tot hij dood is".
     */
    private static PlayerDto resolveEnemyPlayer(GameStateDto frame, AimTargetState state, AimTargetConfig cfg) {
        long ts = frame.timestampMillis;
        PlayerDto current = findByName(frame, state.currentName);
        boolean currentAlive = current != null && current.health > 0 && current.location != null;
        if (!currentAlive) {
            return commit(state, ts, pickFreshClosest(frame));
        }
        if (current.enemyVisible) {
            state.lastSeenVisibleTs = ts;
        }
        long unseenMs = ts - state.lastSeenVisibleTs;
        if (unseenMs >= cfg.unseenForceSwitchMs()) {
            return commit(state, ts, pickFreshClosest(frame));
        }
        if (isHardPinRole()) {
            return current;
        }
        long committedMs = ts - state.committedAtTs;
        if (committedMs < cfg.minCommitMs()) {
            return current;
        }
        if (unseenMs >= cfg.unseenBeforeSwitchMs()) {
            PlayerDto candidate = findCloserVisible(frame, current, cfg.switchDistanceRatio(), selfLoc(frame));
            if (candidate != null) {
                return commit(state, ts, candidate);
            }
        }
        return current;
    }

    /**
     * Carrier is deterministic — the enemy with hasFlag. Visibility tracking is preserved
     * for symmetry with other paths but no hysteresis is applied (carrier identity is canonical;
     * if it changes, switch immediately).
     */
    private static PlayerDto resolveCarrier(GameStateDto frame, AimTargetState state, long ts) {
        if (frame.enemies == null) {
            return commit(state, ts, null);
        }
        PlayerDto carrier = null;
        for (PlayerDto e : frame.enemies) {
            if (e != null && e.hasFlag && e.health > 0 && e.location != null) {
                carrier = e;
                break;
            }
        }
        if (carrier == null) {
            return commit(state, ts, null);
        }
        if (state.currentName == null || !state.currentName.equals(carrier.name)) {
            return commit(state, ts, carrier);
        }
        if (carrier.enemyVisible) {
            state.lastSeenVisibleTs = ts;
        }
        return carrier;
    }

    /**
     * Sticky picker for deterministic-per-frame attention types (nearest-to-flag,
     * nearest-to-attacker). The picker computes the canonical "ideal" target for the current
     * frame; we honor minCommitMs before switching to a different ideal so adjacent enemies
     * at similar distance don't cause per-tick flipping.
     */
    private static PlayerDto resolveStickyDeterministic(GameStateDto frame, AimTargetState state,
                                                         AimTargetConfig cfg,
                                                         Function<GameStateDto, PlayerDto> picker) {
        long ts = frame.timestampMillis;
        PlayerDto current = findByName(frame, state.currentName);
        boolean currentAlive = current != null && current.health > 0 && current.location != null;
        if (!currentAlive) {
            return commit(state, ts, picker.apply(frame));
        }
        if (current.enemyVisible) {
            state.lastSeenVisibleTs = ts;
        }
        long unseenMs = ts - state.lastSeenVisibleTs;
        if (unseenMs >= cfg.unseenForceSwitchMs()) {
            return commit(state, ts, picker.apply(frame));
        }
        long committedMs = ts - state.committedAtTs;
        if (committedMs < cfg.minCommitMs()) {
            return current;
        }
        PlayerDto candidate = picker.apply(frame);
        if (candidate != null && candidate.name != null && !candidate.name.equals(current.name)) {
            return commit(state, ts, candidate);
        }
        return current;
    }

    // ---- Per-attention-type pickers ----

    private static PlayerDto pickNearestToHomeFlag(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null || frame.enemies == null) return null;
        FlagDto ownFlag = (frame.playerPawn.team == 1) ? frame.blueFlag : frame.redFlag;
        if (ownFlag == null || ownFlag.location == null) return null;
        return nearestEnemyToPoint(frame, ownFlag.location.x, ownFlag.location.y);
    }

    private static PlayerDto pickNearestToAttacker(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null || frame.enemies == null) return null;
        PlayerDto attacker = ViewTargeting.findLivingTeammateByRole(
            frame, frame.playerPawn.team, "Attack");
        if (attacker == null || attacker.location == null) return null;
        return nearestEnemyToPoint(frame, attacker.location.x, attacker.location.y);
    }

    /**
     * Most-threatening-to-self picker for the flag carrier. Ranks living enemies by how directly
     * they threaten US: an enemy we can see that is also firing or facing us (acute attacker)
     * outranks one merely visible, which outranks one firing/facing from out of sight; equal tiers
     * break on proximity to self. Returns null when no enemy poses a real threat, so the carrier's
     * view falls back to its objective (run home) instead of locking onto a passive enemy.
     *
     * <p>The enemy carrying OUR flag (the EFC) is deliberately ignored unless it is actively
     * attacking us (acute tier): it is fleeing toward its capture, not hunting the carrier, so it
     * must not pull the carrier's view off the recoverer trying to kill it.
     */
    private static PlayerDto pickMostThreateningToSelf(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null
                || frame.playerPawn.location == null || frame.enemies == null) {
            return null;
        }
        double sx = frame.playerPawn.location.x;
        double sy = frame.playerPawn.location.y;
        PlayerDto best = null;
        int bestTier = 0;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (PlayerDto e : frame.enemies) {
            if (e == null || e.health <= 0 || e.location == null) continue;
            int tier = threatTierToSelf(e, sx, sy);
            if (tier == 0) continue;
            double dx = e.location.x - sx;
            double dy = e.location.y - sy;
            double distSq = dx * dx + dy * dy;
            if (tier > bestTier || (tier == bestTier && distSq < bestDistSq)) {
                bestTier = tier;
                bestDistSq = distSq;
                best = e;
            }
        }
        return best;
    }

    /**
     * Threat tier of one enemy toward self: 3 = visible AND (firing or facing us) — acute attacker;
     * 2 = visible (has line-of-sight, can engage); 1 = firing or facing us from out of sight;
     * 0 = no threat. The EFC (enemy holding our flag) is forced to tier 0 unless it is an acute
     * attacker, so a fleeing flag-stealer never outranks the recoverer attacking the carrier.
     */
    private static int threatTierToSelf(PlayerDto enemy, double selfX, double selfY) {
        boolean firing = enemy.bFire != null && enemy.bFire.value_norm > 0.5f;
        boolean facing = ViewTargeting.isEnemyFacingPoint(enemy, selfX, selfY);
        if (enemy.enemyVisible && (firing || facing)) {
            return 3; // acute attacker — applies to the EFC too if it actually shoots at us
        }
        if (enemy.hasFlag) {
            return 0; // fleeing EFC, not actively attacking → ignore for carrier survival
        }
        if (enemy.enemyVisible) {
            return 2;
        }
        if (firing || facing) {
            return 1;
        }
        return 0;
    }

    private static PlayerDto nearestEnemyToPoint(GameStateDto frame, double px, double py) {
        PlayerDto best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (PlayerDto e : frame.enemies) {
            if (e == null || e.health <= 0 || e.location == null) continue;
            double dx = e.location.x - px;
            double dy = e.location.y - py;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestSq) {
                bestSq = d2;
                best = e;
            }
        }
        return best;
    }

    /**
     * Hard-pin only applies when the bot's role is "Attack" (per gameplay.json). The
     * thread-local lookup is tolerant of an unset identity (offline annotator threads might
     * not have one set per-bot) — falls back to the soft sticky-closest path.
     */
    private static boolean isHardPinRole() {
        try {
            return "Attack".equals(PlayerIdentityContext.effectiveRole());
        } catch (IllegalStateException ignore) {
            return false;
        }
    }

    private static PlayerDto commit(AimTargetState state, long ts, PlayerDto chosen) {
        if (chosen == null) {
            state.currentName = null;
            state.committedAtTs = ts;
            state.lastSeenVisibleTs = ts;
            return null;
        }
        state.currentName = chosen.name;
        state.committedAtTs = ts;
        state.lastSeenVisibleTs = chosen.enemyVisible ? ts : state.lastSeenVisibleTs;
        return chosen;
    }

    private static PlayerDto pickFreshClosest(GameStateDto frame) {
        if (frame == null || frame.enemies == null) return null;
        double[] self = selfLoc(frame);
        PlayerDto bestVisible = null;
        double bestVisibleDist = Double.POSITIVE_INFINITY;
        PlayerDto bestAny = null;
        double bestAnyDist = Double.POSITIVE_INFINITY;
        for (PlayerDto e : frame.enemies) {
            if (e == null || e.health <= 0 || e.location == null) continue;
            double d = dist2D(self, e);
            if (d < bestAnyDist) {
                bestAnyDist = d;
                bestAny = e;
            }
            if (e.enemyVisible && d < bestVisibleDist) {
                bestVisibleDist = d;
                bestVisible = e;
            }
        }
        return bestVisible != null ? bestVisible : bestAny;
    }

    private static PlayerDto findCloserVisible(GameStateDto frame, PlayerDto current, double ratio, double[] self) {
        if (frame == null || frame.enemies == null || current == null) return null;
        double currentDist = dist2D(self, current);
        if (!Double.isFinite(currentDist) || currentDist <= 0.0) return null;
        double threshold = currentDist * ratio;
        PlayerDto best = null;
        double bestDist = threshold;
        for (PlayerDto e : frame.enemies) {
            if (e == null || e.health <= 0 || e.location == null) continue;
            if (e.name != null && e.name.equals(current.name)) continue;
            if (!e.enemyVisible) continue;
            double d = dist2D(self, e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static PlayerDto findByName(GameStateDto frame, String name) {
        if (name == null || frame == null || frame.enemies == null) return null;
        for (PlayerDto e : frame.enemies) {
            if (e != null && name.equals(e.name)) return e;
        }
        return null;
    }

    private static double[] selfLoc(GameStateDto frame) {
        if (frame == null || frame.playerPawn == null || frame.playerPawn.location == null) {
            return new double[] {0.0, 0.0};
        }
        return new double[] {frame.playerPawn.location.x, frame.playerPawn.location.y};
    }

    private static double dist2D(double[] self, PlayerDto other) {
        if (other == null || other.location == null) return Double.POSITIVE_INFINITY;
        double dx = other.location.x - self[0];
        double dy = other.location.y - self[1];
        return Math.hypot(dx, dy);
    }
}
