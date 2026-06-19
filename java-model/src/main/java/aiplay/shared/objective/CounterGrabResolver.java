package aiplay.shared.objective;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

/**
 * Shared decentralized task-split for the counter-grab situation: the enemy team carries OUR flag
 * (an EFC exists) while WE do not yet hold theirs.
 *
 * <p>In CTF a capture is blocked until our flag returns home, so sending every bot to chase the EFC
 * leaves the enemy flag unguarded and lets the EFC walk in a free capture. The strategically correct
 * response is a <b>defensive grab</b>: the bot closest to the enemy flag grabs it (now the enemy
 * <i>also</i> cannot capture — their flag is gone → flag standoff), while the remaining bots
 * intercept the EFC. Once the grab lands ({@code ownTeamHasEnemyFlag} becomes true) the counter-grab
 * phase ends and the standard dual-flag intercept logic takes over.
 *
 * <p>The grabber is chosen position-only (role-blind, per design decision): the closest living
 * team member to the enemy flag, with a distance bucket + stable name tie-break so exactly one bot
 * elects itself grabber and the choice does not jitter frame-to-frame. Every bot computes this
 * locally from its own perceived {@code state.teammates}; because all bots see the same geometry the
 * split is consistent without any messaging.
 *
 * <p>This one resolver is consumed by all three objective sources — {@code WorldFacts}/mission,
 * the {@code navTarget} feature, and the {@code objective_progress} reward — so they cannot drift
 * apart (see CLAUDE.md "Objective dual source").
 */
public final class CounterGrabResolver {

    private CounterGrabResolver() {
    }

    /**
     * Distance quantization (UU) for the grabber tie-break. Two candidates whose distance to the
     * enemy flag falls in the same bucket are ordered by name instead, which damps jitter when bots
     * are roughly equidistant while still preferring a clearly-closer bot.
     */
    private static final double GRAB_TIEBREAK_MARGIN_UU = 250.0;

    /** True when our own flag is currently carried by an enemy (the EFC exists). */
    public static boolean isOwnFlagCarriedByEnemy(GameStateDto state) {
        if (state == null || state.playerPawn == null) {
            return false;
        }
        FlagDto ownFlag = ownFlag(state);
        return ownFlag != null && (ownFlag.status == FlagStatusDto.CARRIED || ownFlag.hasHolder);
    }

    /** True when the enemy flag is currently carried by our own team (or this bot). */
    public static boolean isEnemyFlagHeldByOwnTeam(GameStateDto state) {
        if (state == null || state.playerPawn == null) {
            return false;
        }
        FlagDto enemyFlag = enemyFlag(state);
        return enemyFlag != null && (enemyFlag.status == FlagStatusDto.CARRIED || enemyFlag.hasHolder);
    }

    /**
     * Counter-grab phase: the enemy carries our flag AND we do not yet hold theirs. Once a teammate
     * grabs the enemy flag this turns false and the dual-flag standoff (everyone intercepts the EFC
     * to free a capture for the carrier) applies instead.
     */
    public static boolean isCounterGrabActive(GameStateDto state) {
        return isOwnFlagCarriedByEnemy(state) && !isEnemyFlagHeldByOwnTeam(state);
    }

    /**
     * True when THIS bot is the designated counter-grabber: counter-grab is active and no living
     * team member is a strictly better grabber (closer distance bucket, or equal bucket with a
     * lexicographically smaller name). Role-blind, position-only. Assumes unique player names
     * (UT99 enforces this) so the (bucket, name) ordering elects exactly one grabber.
     */
    public static boolean isDesignatedGrabber(GameStateDto state) {
        if (!isCounterGrabActive(state)) {
            return false;
        }
        PlayerDto me = state.playerPawn;
        if (me == null || me.location == null) {
            return false;
        }
        CoordinatesDto goal = enemyFlagPosition(state);
        if (goal == null) {
            return false;
        }
        long myBucket = bucket(distance2d(me.location, goal));
        String myName = (me.name != null) ? me.name : "";

        if (state.teammates != null) {
            for (PlayerDto t : state.teammates) {
                if (t == null || t.location == null || t.health <= 0) {
                    continue;
                }
                long tBucket = bucket(distance2d(t.location, goal));
                String tName = (t.name != null) ? t.name : "";
                boolean teammateIsBetter = tBucket < myBucket
                        || (tBucket == myBucket && tName.compareTo(myName) < 0);
                if (teammateIsBetter) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Movement objective while our flag is carried by an enemy. The designated grabber heads for the
     * enemy flag (defensive grab → standoff); every other bot intercepts the EFC at a cut-off point
     * instead of tail-chasing its current position. Used by both the reward and the navTarget feature
     * inside their existing "own flag carried" branch.
     */
    public static CoordinatesDto carriedFlagObjective(GameStateDto state) {
        if (isDesignatedGrabber(state)) {
            return enemyFlagPosition(state);
        }
        return interceptPoint(state);
    }

    /**
     * Equal-speed lead-pursuit cut-off point for an EFC interceptor. The EFC moves from its current
     * position {@code E} (= our flag location while carried) toward its goal {@code B} (the enemy
     * flag base, where it scores). Assuming the interceptor and EFC move at the same speed, the
     * earliest meeting point on the ray {@code E + s·û} satisfies {@code |X − P| = s}, which reduces
     * to the linear {@code s = −|E−P|² / (2·(E−P)·û)}.
     *
     * <p>When that yields no forward solution (the interceptor sits behind the EFC and cannot catch
     * it at equal speed) we aim at the destination {@code B} to cut it off there. Either way the
     * interceptor always aims <i>ahead</i> of the EFC toward the enemy base — never back at its
     * trailing position — which is what stops the "run all the way home and chase from behind"
     * behaviour. Falls back to the raw EFC position if base geometry is unavailable.
     */
    public static CoordinatesDto interceptPoint(GameStateDto state) {
        PlayerDto me = (state != null) ? state.playerPawn : null;
        if (me == null) {
            return null;
        }
        FlagDto ownFlag = ownFlag(state);
        FlagDto enemyFlag = enemyFlag(state);
        CoordinatesDto efcRaw = (ownFlag != null) ? ownFlag.location : null;
        if (efcRaw == null || me.location == null
                || enemyFlag == null || enemyFlag.baseLocation == null) {
            return efcRaw; // fallback: pure pursuit toward the EFC
        }

        CoordinatesDto e = efcRaw;                 // EFC current position
        CoordinatesDto b = enemyFlag.baseLocation; // EFC destination (its capture point)
        CoordinatesDto p = me.location;            // interceptor position

        double ux = b.x - e.x;
        double uy = b.y - e.y;
        double axisLen = Math.hypot(ux, uy);
        if (axisLen < 1e-6) {
            return e; // EFC already at its base
        }
        ux /= axisLen;
        uy /= axisLen;

        double dx = e.x - p.x;
        double dy = e.y - p.y;
        double dDotU = dx * ux + dy * uy;
        double dSq = dx * dx + dy * dy;

        double s;
        if (dDotU < -1e-6) {
            // Interceptor is ahead of the EFC along the axis → real equal-speed cut-off exists.
            s = -dSq / (2.0 * dDotU);
        } else {
            // Interceptor is behind/abreast of the EFC → can't catch it; cut off at the base.
            s = axisLen;
        }
        s = Math.max(0.0, Math.min(axisLen, s));

        double frac = s / axisLen;
        CoordinatesDto x = new CoordinatesDto();
        x.x = e.x + s * ux;
        x.y = e.y + s * uy;
        x.z = e.z + (b.z - e.z) * frac;
        return x;
    }

    // ---- helpers ----

    /** Enemy-flag grab target: live location when known, else its base. */
    private static CoordinatesDto enemyFlagPosition(GameStateDto state) {
        FlagDto enemyFlag = enemyFlag(state);
        if (enemyFlag == null) {
            return null;
        }
        return (enemyFlag.location != null) ? enemyFlag.location : enemyFlag.baseLocation;
    }

    private static FlagDto ownFlag(GameStateDto state) {
        return (state.playerPawn.team == 0) ? state.redFlag : state.blueFlag;
    }

    private static FlagDto enemyFlag(GameStateDto state) {
        return (state.playerPawn.team == 0) ? state.blueFlag : state.redFlag;
    }

    private static long bucket(double distanceUu) {
        return (long) Math.floor(distanceUu / GRAB_TIEBREAK_MARGIN_UU);
    }

    private static double distance2d(CoordinatesDto a, CoordinatesDto b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
