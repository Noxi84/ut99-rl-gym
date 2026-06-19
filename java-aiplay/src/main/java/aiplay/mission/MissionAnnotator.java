package aiplay.mission;

import aiplay.dto.GameStateDto;
import aiplay.engagement.EngagementPolicy;
import aiplay.engagement.RuleBasedEngagementPolicy;
import aiplay.shared.engagement.EngagementIntent;
import aiplay.shared.mission.MissionIntent;
import aiplay.shared.tactical.TacticalIntent;
import aiplay.tactical.RuleBasedTacticalPolicy;
import aiplay.tactical.TacticalPolicy;

/**
 * Shared sequential state machine that annotates frames with mission/engagement/tactical context.
 *
 * Single contract for three consumers:
 * - runtime MissionController (live evaluation + bus publish)
 * - realtime feature enrichment (annotation on GameStateDto)
 * - offline CSV labeling (same annotation, sequential over recorded frames)
 *
 * Pipeline: GameStateDto -> WorldFacts -> MissionPolicy -> EngagementPolicy + TacticalPolicy -> Intent outputs
 */
public class MissionAnnotator {

    private final StuckDetector stuckDetector;
    private final MissionPolicy missionPolicy;
    private final EngagementPolicy engagementPolicy;
    private final TacticalPolicy tacticalPolicy;

    /** Default constructor: wires the rule-based policy implementations. */
    public MissionAnnotator() {
        this(new RuleBasedMissionPolicy(),
             new RuleBasedEngagementPolicy(),
             new RuleBasedTacticalPolicy());
    }

    /** Full constructor for testing or future learned policies. */
    public MissionAnnotator(MissionPolicy missionPolicy,
                                 EngagementPolicy engagementPolicy,
                                 TacticalPolicy tacticalPolicy) {
        this.stuckDetector = new StuckDetector();
        this.missionPolicy = missionPolicy;
        this.engagementPolicy = engagementPolicy;
        this.tacticalPolicy = tacticalPolicy;
    }

    /**
     * Annotate a single frame with mission/engagement/tactical context.
     * Must be called sequentially over frames in timestamp order.
     *
     * @param state the game state frame to annotate
     * @return the annotation result, or null if state is invalid
     */
    public Result annotate(GameStateDto state) {
        if (state == null || state.playerPawn == null) {
            return null;
        }

        long frameTs = state.timestampMillis;

        boolean isStuck = stuckDetector.isStuck(state,
                !aiplay.rl.MovementPrimitive.fromGameState(state).isIdle(), frameTs);
        WorldFacts facts = WorldFacts.derive(state, isStuck, frameTs);
        if (facts == null) {
            return null;
        }

        MissionIntent mission = missionPolicy.evaluate(facts);
        EngagementIntent engagement = engagementPolicy.evaluate(facts, mission);
        TacticalIntent tactical = tacticalPolicy.evaluate(facts, mission);

        return new Result(mission, engagement, tactical, facts, isStuck);
    }

    public static class Result {
        public final MissionIntent mission;
        public final EngagementIntent engagement;
        public final TacticalIntent tactical;
        public final WorldFacts worldFacts;
        public final boolean isStuck;

        public Result(MissionIntent mission,
                      EngagementIntent engagement, TacticalIntent tactical,
                      WorldFacts worldFacts, boolean isStuck) {
            this.mission = mission;
            this.engagement = engagement;
            this.tactical = tactical;
            this.worldFacts = worldFacts;
            this.isStuck = isStuck;
        }
    }
}
