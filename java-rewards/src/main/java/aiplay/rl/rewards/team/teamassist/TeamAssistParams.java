package aiplay.rl.rewards.team.teamassist;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Sparse + dense rewards that decompose to the 6th critic head ({@code team_assist}).
 *
 * <p>Five sub-components, each conditionally active per rewardgroup-override:
 *
 * <ul>
 *   <li>{@code teamCapturedAssist}: bonus when a teammate captures the enemy flag while this bot
 *       was within {@code assistRadiusUu} of the bot's own flag base — the capture happens at our
 *       home base, so "near home base during capture" matches the spec "binnen X UU van eigen vlag
 *       tijdens capture window".</li>
 *   <li>{@code teamReturnedAssist}: bonus when a teammate returns our own flag while this bot was
 *       within {@code assistRadiusUu} of the return location (own flag base). Auto-returns
 *       (instigator slot &lt; 0) are excluded; self-returns are excluded — those credit
 *       {@code FlagEventReward.returned} on the actor itself.</li>
 *   <li>{@code carrierKillAssist}: bonus when our own flag transitioned carried→dropped (i.e.
 *       enemy carrier just died) but this bot was NOT the killer (no frag-delta this tick), while
 *       being within {@code killAssistRadiusUu} of the drop site. The killer credit goes through
 *       {@code FlagCarrierKillReward} on the actor.</li>
 *   <li>{@code escortProximityDense}: dense per-tick bonus when the bot is within
 *       {@code escortDenseRangeUu} of any living NON-carrier teammate. Linear ramp 1.0 → 0.0 from
 *       0 UU to the full range. Defaults to zero in every rewardgroup; only the Cover group should
 *       set this to a non-zero weight (it is the open-field escort signal that complements
 *       {@code cover_escort}, which only fires when a teammate carries a flag). The
 *       teammate-carrier is excluded as target (2026-06-06) so this ramp never stacks with
 *       {@code cover_escort} onto the carrier — that stack made standing inside the carrier the
 *       reward optimum and physically blocked his capture run.</li>
 *   <li>{@code endgameAttackBonus}: dense per-tick bonus when the team is behind on score in the
 *       final stretch of the match. Reward = {@code endgameAttackBonus × urgency ×
 *       clamp01(botDepthInEnemyHalf)} where urgency ramps from 0 → 1 between
 *       {@code endgame_urgency.ramp_start_remaining_norm} and
 *       {@code endgame_urgency.ramp_full_remaining_norm} and is multiplied by 1.0 iff
 *       our_score &lt; their_score (else 0). Non-zero only for Defend/Cover rolegroups where
 *       breaking out of camping/escort is the desired endgame behavior; Attack stays 0 (it is
 *       already pushing). Ramp shape lives top-level in {@code EndgameUrgencyParams} so it is a
 *       single global tunable, not per-role.</li>
 * </ul>
 *
 * <p>All five sub-rewards collapse to a single {@code team_assist} scalar in
 * {@code RewardBreakdown} so the 6th critic head consumes one merged channel. The per-component
 * detail is still exposed via {@code TeamAssistReward.Result} for breakdown logging.
 */
public record TeamAssistParams(
    RewardMetadata metadata,
    double teamCapturedAssist,
    double teamReturnedAssist,
    double carrierKillAssist,
    double escortProximityDense,
    double endgameAttackBonus,
    double assistRadiusUu,
    double killAssistRadiusUu,
    double escortDenseRangeUu)
    implements RewardBlock {

  public TeamAssistParams {
    if (metadata == null) {
      throw new IllegalArgumentException("TeamAssistParams.metadata required");
    }
    if (assistRadiusUu <= 0.0) {
      throw new IllegalArgumentException("TeamAssistParams.assistRadiusUu must be > 0");
    }
    if (killAssistRadiusUu <= 0.0) {
      throw new IllegalArgumentException("TeamAssistParams.killAssistRadiusUu must be > 0");
    }
    if (escortDenseRangeUu <= 0.0) {
      throw new IllegalArgumentException("TeamAssistParams.escortDenseRangeUu must be > 0");
    }
  }

  @Override
  public boolean enabled() {
    return teamCapturedAssist != 0.0
        || teamReturnedAssist != 0.0
        || carrierKillAssist != 0.0
        || escortProximityDense != 0.0
        || endgameAttackBonus != 0.0;
  }
}
