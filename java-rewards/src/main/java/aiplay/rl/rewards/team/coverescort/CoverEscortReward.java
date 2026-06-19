package aiplay.rl.rewards.team.coverescort;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.rl.rewards.catalog.EndgameUrgencyParams;
import aiplay.rl.rewards.team.endgame.EndgameUrgency;
import aiplay.shared.objective.EscortObjectiveResolver;

/**
 * Dense escort shaping for Cover-role bots: rewards proximity to whichever teammate carries a flag.
 *
 * <p>Returns zero when no teammate currently holds a flag — open-field escort (Cover near the
 * Attack-teammate without an active carry) is handled by the {@code team_assist} head's
 * {@code escort_proximity_dense} sub-component, not here.
 *
 * <p>Escort-standoff (2026-06-06): the proximity ramp is computed on {@code max(dist, standoff)}
 * ({@link EscortObjectiveResolver#escortStandoffUu}) so the reward plateaus inside the standoff
 * band instead of peaking at 0 UU. The old linear 1.0 → 0.0 ramp made <i>standing inside the
 * carrier</i> the optimum — pawns collide, so the escort physically bumped and blocked the carrier
 * (observed live: a teammate running into the carrier's back on the capture run, blocking the
 * capture). The band edge is now the attractor, same pattern as the EFC-chase engagement floor.
 *
 * <p>Capture-funnel-release (2026-06-06, {@link EscortObjectiveResolver#isCaptureFunnelActive}):
 * when the carrier can actually score (own flag home) and is in the last-mile near base, the pull
 * AND the far-penalty are released entirely — no dense signal may drag the escort into the funnel.
 * Inside the standoff band the {@code berth_penalty} ramp then actively pushes an already-glued
 * escort out of the carrier's path; the sparse {@code flag_event.team_captured} +
 * {@code team_assist.team_captured_assist} keep "being near the base at the capture" rewarding.
 * The berth-penalty is deliberately NOT endgame-modulated: it is an anti-blocking corrective, not
 * escort shaping.
 *
 * <p>Endgame catchup: the escort shaping is multiplied by {@code (1 - urgency)} so both the
 * proximity bonus and the far-penalty fade to zero during the configured endgame ramp window
 * (only when the team is behind). Cover then stops being pinned to the carrier and can break off
 * to attack alongside; positive pull is supplied by
 * {@code TeamAssistReward.endgameAttackBonus}.
 */
public class CoverEscortReward implements RewardComponent {

  private final CoverEscortParams params;
  private final EndgameUrgencyParams endgameParams;

  public CoverEscortReward(CoverEscortParams params, EndgameUrgencyParams endgameParams) {
    if (params == null) {
      throw new IllegalArgumentException("CoverEscortReward requires non-null CoverEscortParams");
    }
    if (endgameParams == null) {
      throw new IllegalArgumentException(
          "CoverEscortReward requires non-null EndgameUrgencyParams");
    }
    this.params = params;
    this.endgameParams = endgameParams;
  }

  @Override
  public double compute(RewardContext ctx) {
    if (!params.enabled()) {
      return 0.0;
    }
    GameStateDto state = ctx.curr();
    if (state.playerPawn == null || state.playerPawn.location == null
        || state.playerPawn.health <= 0) {
      return 0.0;
    }
    PlayerDto carrier = RewardUtils.findTeammateCarrier(state);
    if (carrier == null) {
      return 0.0;
    }
    CoordinatesDto self = state.playerPawn.location;
    double dist = RewardUtils.distance(self, carrier.location);
    double standoff = EscortObjectiveResolver.escortStandoffUu(state);

    // Capture-funnel-release: carrier kan scoren en zit in de last-mile → pull en far-penalty
    // volledig los; binnen de standoff-band duwt de berth-penalty een al-plakkende escort actief
    // uit het capture-pad. Bewust niet endgame-gemoduleerd (anti-blokkade, geen escort-shaping).
    if (EscortObjectiveResolver.isCaptureFunnelActive(state)) {
      if (params.berthPenalty() != 0.0 && standoff > 0.0 && dist < standoff) {
        return -params.berthPenalty() * (1.0 - dist / standoff);
      }
      return 0.0;
    }

    // Escort-standoff: ramp op de geclampte afstand → vlak plateau binnen de band, band-rand =
    // attractor (geen gradient meer die de escort ÍN de carrier duwt).
    double effDist = Math.max(dist, standoff);
    double raw;
    if (effDist < params.escortRangeUu()) {
      double scale = 1.0 - (effDist / params.escortRangeUu());
      raw = params.proximityBonus() * scale;
    } else if (dist > params.farRangeUu()) {
      raw = -params.farPenalty();
    } else {
      return 0.0;
    }

    double urgency = EndgameUrgency.urgency(state, endgameParams);
    return raw * (1.0 - urgency);
  }
}
