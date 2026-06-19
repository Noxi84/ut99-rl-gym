package aiplay.mission;

import aiplay.dto.GameStateDto;
import aiplay.shared.engagement.AttentionTargetType;
import aiplay.shared.engagement.EngagementIntent;
import aiplay.shared.engagement.EngagementType;
import aiplay.shared.mission.MissionIntent;
import aiplay.shared.mission.MissionType;
import aiplay.shared.tactical.TacticalIntent;
import aiplay.shared.tactical.TacticalIntentBus;
import aiplay.shared.tactical.TacticalType;
import java.util.logging.Logger;

/**
 * Top-level orchestrator for the mission/engagement/tactical layer.
 * Called at skill-selector frequency (5Hz). Delegates to MissionAnnotator
 * (the shared state machine) and publishes results to intent buses.
 *
 * Uses the same annotator contract as feature-enrichment and CSV labeling.
 * Never sends commands.
 */
public class MissionController {

    private final MissionAnnotator annotator;
    private final TacticalIntentBus tacticalBus;
    private final Logger logger;

    private MissionType lastLoggedMission = null;
    private EngagementType lastLoggedEngagement = null;
    private AttentionTargetType lastLoggedAttention = null;
    private TacticalType lastLoggedTactical = null;

    public MissionController(
            TacticalIntentBus tacticalBus,
            Logger logger) {
        this.annotator = new MissionAnnotator();
        this.tacticalBus = tacticalBus;
        this.logger = logger;
    }

    /**
     * Called every tick at skill-selector frequency (5Hz).
     * Annotates the frame and publishes engagement/tactical intents to buses.
     */
    public void tick(GameStateDto state) {
        MissionAnnotator.Result result = annotator.annotate(state);
        if (result == null) return;

        state.annotatedMission = result.mission.missionType;
        state.annotatedEngagement = result.engagement.engagementType;
        state.annotatedAttentionTarget = result.engagement.attentionTarget;

        tacticalBus.publish(result.tactical);

        logTransitions(result.mission, result.engagement, result.tactical);
    }

    private void logTransitions(MissionIntent mission,
                                EngagementIntent engagement, TacticalIntent tactical) {
        if (mission.missionType != lastLoggedMission) {
            if (logger != null) {
                logger.info(String.format("MISSION_CHANGE mission=%s reason=%s",
                    mission.missionType, mission.reason));
            }
            lastLoggedMission = mission.missionType;
        }
        if (engagement.engagementType != lastLoggedEngagement
                || engagement.attentionTarget != lastLoggedAttention) {
            if (logger != null) {
                logger.info(String.format("ENGAGEMENT_CHANGE engagement=%s attention=%s reason=%s",
                    engagement.engagementType, engagement.attentionTarget, engagement.reason));
            }
            lastLoggedEngagement = engagement.engagementType;
            lastLoggedAttention = engagement.attentionTarget;
        }
        if (tactical.tacticalType != lastLoggedTactical) {
            if (logger != null) {
              logger.info(String.format("TACTICAL_CHANGE tactical=%s constraint=%s boundary=%s reason=%s carrierLine=%.3f",
                    tactical.tacticalType, tactical.constraintMode,
                  tactical.territoryBoundary, tactical.reason,
                  tactical.carrierLineProgressNorm));
            }
            lastLoggedTactical = tactical.tacticalType;
        }
    }
}
