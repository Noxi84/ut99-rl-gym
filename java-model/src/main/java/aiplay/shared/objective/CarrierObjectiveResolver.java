package aiplay.shared.objective;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.field.HalfFieldGeometry;

/**
 * Shared movement objective for a bot that CARRIES the enemy flag.
 *
 * <p>In CTF a capture is blocked until our OWN flag is home. The default carrier objective is
 * therefore the own flag base: preserve the carried flag (dying drops it) and stage at base so the
 * capture lands the instant our flag returns. But when our own flag is lying {@link
 * FlagStatusDto#DROPPED DROPPED} on the field it can be returned by simply touching it — and if it
 * happens to lie roughly on the way home, the carrier returning it himself unblocks the capture
 * <i>immediately</i> instead of idling at base waiting for a teammate. Without this the carrier walks
 * straight past a nearby dropped own flag (observed bug, 2026-05-31).
 *
 * <p>The detour test is purely geometric: {@code extra = d(bot,flag) + d(flag,home) − d(bot,home)} is
 * the additional distance incurred by routing via the flag. When the flag sits on the direct line
 * {@code extra ≈ 0}; it grows as the flag drifts off-route. Only when {@code extra ≤
 * MAX_RETURN_DETOUR_UU} (a small, clearly-worth-it detour) does the carrier divert; a far-off dropped
 * flag is left to a teammate while the carrier protects the captured flag at base.
 *
 * <p><b>A CARRIED own flag (a moving EFC) is never chased</b> — it cannot be returned by a touch, and
 * risking the precious carried enemy flag to hunt the EFC is wrong (the 2026-05-29 carrier-first
 * decision). Only a stationary DROPPED flag is eligible.
 *
 * <p>This one resolver is consumed by both objective sources — the {@code navTarget} feature and the
 * {@code objective_progress}/{@code facing} rewards — so feature input and dense reward cannot drift
 * apart (see CLAUDE.md "Objective dual source", and the sibling {@link CounterGrabResolver}).
 *
 * <p>Precondition: callers invoke this only when {@code state.playerPawn.hasFlag} is true (the carrier
 * branch of the objective priority chain), mirroring {@link CounterGrabResolver#carriedFlagObjective}.
 */
public final class CarrierObjectiveResolver {

    private CarrierObjectiveResolver() {
    }

    /**
     * Maximum extra travel distance (UU) a carrier will accept to divert via a dropped own flag.
     * Sized as a "small, on-the-route" detour: ~1500 UU is a few seconds of extra running at ground
     * speed (~440 UU/s) — cheap relative to the payoff of instantly unblocking the capture, while
     * keeping the carrier from wandering the map with the enemy flag. Tunable; promote to config if
     * per-scenario control is needed.
     */
    static final double MAX_RETURN_DETOUR_UU = 1500.0;

    /**
     * Staging-zone radius as a fraction of the inter-base distance. When a carrier cannot score
     * (capture blocked) and an enemy is near our base, standing on the <i>exact</i> base point is
     * predictable and gets the carrier camped and killed — so within this radius he gets a little
     * manoeuvring room around the base instead of a single point. Kept deliberately SMALL so the
     * carrier stays on the capture trigger rather than wandering. {@code 0.12} ≈ {@code 0.12 × ~1345
     * UU ≈ 160 UU} on CTF-AndAction: a cover-seek radius around the flag stand (well under the ~220 UU
     * rocket splash), not a free-roam zone. 2026-05-31: lowered 0.35 → 0.12 after live observation
     * that the wide zone left the carrier with no objective pull, and the policy filled that vacuum by
     * crawling into the back corner (an easy target) — the opposite of the intent. Map-agnostic
     * (scales with {@link HalfFieldGeometry#interBaseDistance}). Tunable; promote to config if
     * per-scenario control is needed.
     */
    static final double STAGE_ZONE_FRACTION = 0.12;

    /**
     * Minimum enemy penetration onto our half ({@link HalfFieldGeometry#enemyDepthInOwnHalf}, 0 =
     * midfield … 1 = home base) before staging-zone freedom activates. Below this the carrier simply
     * targets the exact base point (staged to score the instant our flag returns). {@code 0.40} means
     * the nearest living enemy must be genuinely close to our base — depth 0.40 ↔ {@code t < 0.3} in
     * {@link HalfFieldGeometry#enemyDepthInOwnHalf}, i.e. into the inner ~30% of our half — before the
     * small manoeuvring room kicks in. 2026-05-31: raised 0.0 → 0.40, same fix as {@link
     * #STAGE_ZONE_FRACTION}: at 0.0 any enemy a single step over midfield armed the zone, so it was on
     * almost constantly; now the carrier only gets dodge room when a threat is actually near, and
     * otherwise stays tight on the capture trigger.
     */
    static final double STAGE_ZONE_ENEMY_DEPTH_THRESHOLD = 0.40;

    /**
     * Movement objective for a flag carrier: the dropped own-flag location when returning it is a
     * cheap on-route detour, otherwise the own flag base. See class doc for the rationale.
     */
    public static CoordinatesDto carrierObjective(GameStateDto state) {
        FlagDto ownFlag = ownFlag(state);
        if (ownFlag == null || ownFlag.baseLocation == null) {
            return ownFlag != null ? ownFlag.baseLocation : null;
        }
        if (shouldReturnDroppedFlag(state)) {
            return ownFlag.location;
        }
        return ownFlag.baseLocation;
    }

    /**
     * True when this carrier should divert to return a stationary dropped own flag on the way home:
     * the own flag is DROPPED with a known location and the via-flag detour stays within
     * {@link #MAX_RETURN_DETOUR_UU}. Returns false for a CARRIED (moving EFC) or HOME own flag, or
     * when positions are unavailable. Exposed so the reward path can stay aligned with the navTarget
     * feature without recomputing the geometry.
     */
    public static boolean shouldReturnDroppedFlag(GameStateDto state) {
        if (state == null || state.playerPawn == null) {
            return false;
        }
        PlayerDto pawn = state.playerPawn;
        FlagDto ownFlag = ownFlag(state);
        if (pawn.location == null || ownFlag == null
                || ownFlag.status != FlagStatusDto.DROPPED
                || ownFlag.location == null || ownFlag.baseLocation == null) {
            return false;
        }
        double detour = distance2d(pawn.location, ownFlag.location)
                + distance2d(ownFlag.location, ownFlag.baseLocation)
                - distance2d(pawn.location, ownFlag.baseLocation);
        return detour <= MAX_RETURN_DETOUR_UU;
    }

    /**
     * True when carrier staging-zone freedom applies: the bot carries the enemy flag, the capture is
     * BLOCKED (our own flag is not home and the carrier is not himself diverting to return a dropped
     * own flag by touch), and an enemy is close to our base (deep on our half, see {@link
     * #STAGE_ZONE_ENEMY_DEPTH_THRESHOLD}). In that case both objective sources relax the pull toward
     * the exact base point into a small zone (see {@link #stageZoneRadiusUu}); otherwise the carrier
     * targets the exact base point as before — to score (own flag home) or to stage with no nearby
     * threat. Shared by the {@code objective_progress} reward floor and the {@code navTarget} feature
     * so reward and feature cannot drift (dual source).
     */
    public static boolean isStagingZoneActive(GameStateDto state) {
        if (state == null || state.playerPawn == null || !state.playerPawn.hasFlag) {
            return false;
        }
        FlagDto ownFlag = ownFlag(state);
        if (ownFlag == null || ownFlag.baseLocation == null) {
            return false;
        }
        // Capture possible (our flag is home) → no zone; reaching base scores instantly.
        if (ownFlag.status == FlagStatusDto.HOME) {
            return false;
        }
        // Carrier is fetching a nearby dropped own flag himself (touch) → needs the exact point.
        if (shouldReturnDroppedFlag(state)) {
            return false;
        }
        // Manoeuvring room only while a threat is close to our base; otherwise target the exact point.
        return HalfFieldGeometry.enemyDepthInOwnHalf(state) > STAGE_ZONE_ENEMY_DEPTH_THRESHOLD;
    }

    /**
     * Staging-zone radius in UU ({@link #STAGE_ZONE_FRACTION} × inter-base distance) when {@link
     * #isStagingZoneActive}, else {@code 0.0}. Consumers clamp the carrier's distance-to-base at this
     * radius: the reward neutralises progress within the zone (free movement, the rim a soft wall) and
     * the feature squashes the navTarget bearing to neutral inside it. {@code 0.0} → exact-point
     * behaviour (no clamp), identical to before.
     */
    public static double stageZoneRadiusUu(GameStateDto state) {
        if (!isStagingZoneActive(state)) {
            return 0.0;
        }
        return STAGE_ZONE_FRACTION * HalfFieldGeometry.interBaseDistance(state);
    }

    private static FlagDto ownFlag(GameStateDto state) {
        return (state.playerPawn.team == 0) ? state.redFlag : state.blueFlag;
    }

    private static double distance2d(CoordinatesDto a, CoordinatesDto b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
