package aiplay.shared.objective;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;

/**
 * Shared escort decisions for a NON-carrier whose objective falls through to the teammate flag
 * carrier (objective priority 6: enemy flag location — the live carrier position while CARRIED).
 *
 * <p>Without this resolver every dense pull on the escort peaks at distance 0 to the carrier: the
 * {@code objective_progress} delta runs monotonically to 0 UU, and {@code cover_escort} /
 * {@code escort_proximity_dense} ramp linearly to their maximum at 0 UU. The reward optimum is
 * therefore <i>standing inside the carrier</i> — and UT99 pawns collide, so the escort physically
 * bumps and blocks the carrier. Observed live (2026-06-06): the carrier reaches his own base with
 * the capture open (own flag home) and a teammate runs into his back, blocking the capture funnel.
 *
 * <p>Two shared decisions fix this:
 *
 * <ol>
 *   <li><b>Escort standoff</b> ({@link #escortStandoffUu}): the engagement-standoff pattern
 *       (clamp at {@code max(dist, floor)} — see the EFC-chase floor in {@code objective_progress})
 *       applied to the teammate-carrier objective. Closing is rewarded up to the band edge;
 *       movement inside the band is neutral, so the band edge becomes the attractor and the escort
 *       no longer has any incentive to step into the carrier.</li>
 *   <li><b>Capture funnel release</b> ({@link #isCaptureFunnelActive}): when the carrier can
 *       actually score (own flag {@link FlagStatusDto#HOME HOME}) and is inside the capture
 *       last-mile ({@link #CAPTURE_FUNNEL_RADIUS_UU} around the own base — the same zone where the
 *       carrier's quadratic {@code carrier_proximity_bonus} burst is active), every escort pull is
 *       released entirely: even the band-edge attractor would drag the escort into the funnel the
 *       carrier needs free. The sparse {@code flag_event.team_captured} +
 *       {@code team_assist.team_captured_assist} payouts (within {@code assist_radius_uu} of the
 *       base) keep the escort interested in the area without a dense gradient pinning him onto the
 *       carrier's route.</li>
 * </ol>
 *
 * <p>This one resolver is consumed by both objective sources — the {@code navTarget} feature and
 * the {@code objective_progress} reward — plus the {@code cover_escort} escort-shaping, so feature
 * input and dense rewards cannot drift apart (see CLAUDE.md "Objective dual source", and the
 * siblings {@link CarrierObjectiveResolver} / {@link CounterGrabResolver}).
 *
 * <p>Note: the chain exclusions (own-flag-return priorities 1–3, the Defend-role base anchor of
 * priority 5) stay with the callers, which already own that priority knowledge — this resolver only
 * answers the teammate-carrier questions, on the same raw facts both sources share.
 */
public final class EscortObjectiveResolver {

    private EscortObjectiveResolver() {
    }

    /**
     * Standoff band radius (UU) around the teammate carrier for every dense escort pull. Sized like
     * the EFC-chase engagement floor (280 UU, see {@code efc_engagement_range_uu}): far above the
     * pawn bump range (collision radius ~17 UU, physical pushing plays under ~100 UU) yet close
     * enough to clear threats off the carrier and to instantly contest a drop. Slightly tighter
     * than the hostile EFC floor — a friendly escort needs no rocket-splash margin against its own
     * carrier. Tunable; promote to config if per-scenario control is needed.
     */
    static final double ESCORT_STANDOFF_UU = 250.0;

    /**
     * Carrier-to-own-base distance (UU) under which the capture funnel release activates (given the
     * own flag is HOME so the capture is actually open). Deliberately equal to
     * {@code carrier_proximity_radius_uu} (500): the exact zone where the carrier earns his
     * quadratic last-mile capture burst is the zone where the escort lets go. Tunable; promote to
     * config if per-scenario control is needed.
     */
    static final double CAPTURE_FUNNEL_RADIUS_UU = 500.0;

    /**
     * True when a TEAMMATE carries the enemy flag: the enemy flag is CARRIED while this bot does
     * not hold it himself. (A CARRIED enemy flag is by definition held by our team — CTF only lets
     * a team pick up the opposing flag — so "carried and not by me" means a teammate.) This is the
     * situation where objective priority 6 silently turns "rush enemy flag" into "chase my own
     * carrier", which is what the standoff/funnel decisions below correct.
     */
    public static boolean hasTeammateCarrier(GameStateDto state) {
        if (state == null || state.playerPawn == null || state.playerPawn.hasFlag) {
            return false;
        }
        FlagDto enemyFlag = enemyFlag(state);
        return enemyFlag != null && enemyFlag.status == FlagStatusDto.CARRIED;
    }

    /**
     * True when the capture funnel must be released: a teammate carries the enemy flag, our own
     * flag is HOME (the capture is open — touching base scores instantly), and the carrier is
     * within {@link #CAPTURE_FUNNEL_RADIUS_UU} of our base. Consumers drop every dense escort pull
     * (objective progress, navTarget bearing, cover_escort proximity) so no teammate is dragged
     * onto the carrier's final capture route; {@code cover_escort.berth_penalty} additionally
     * pushes an already-glued escort out of the carrier's standoff band in exactly this window.
     */
    public static boolean isCaptureFunnelActive(GameStateDto state) {
        if (!hasTeammateCarrier(state)) {
            return false;
        }
        FlagDto ownFlag = ownFlag(state);
        FlagDto enemyFlag = enemyFlag(state);
        if (ownFlag == null || ownFlag.status != FlagStatusDto.HOME
                || ownFlag.baseLocation == null
                || enemyFlag.location == null) {
            return false;
        }
        // enemyFlag.location == carrier position while CARRIED (UC sends holder.Location).
        return distance2d(enemyFlag.location, ownFlag.baseLocation) < CAPTURE_FUNNEL_RADIUS_UU;
    }

    /**
     * Standoff floor (UU) around the teammate carrier when {@link #hasTeammateCarrier}, else
     * {@code 0.0}. Consumers clamp the escort's distance-to-carrier at this radius: the reward
     * neutralises progress within the band (band edge = attractor) and the feature squashes the
     * navTarget bearing to neutral inside it. {@code 0.0} → exact-point behaviour (no clamp),
     * identical to before.
     */
    public static double escortStandoffUu(GameStateDto state) {
        return hasTeammateCarrier(state) ? ESCORT_STANDOFF_UU : 0.0;
    }

    private static FlagDto ownFlag(GameStateDto state) {
        return (state.playerPawn.team == 0) ? state.redFlag : state.blueFlag;
    }

    private static FlagDto enemyFlag(GameStateDto state) {
        PlayerDto pawn = state.playerPawn;
        return (pawn.team == 0) ? state.blueFlag : state.redFlag;
    }

    private static double distance2d(CoordinatesDto a, CoordinatesDto b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
